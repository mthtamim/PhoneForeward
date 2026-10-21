package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service holding the two realtime links:
 *  - receiver mode: listens on the DATA topic, stores to inbox + notifies
 *  - sender mode:   listens on the COMMAND topic, runs remote commands,
 *                   sends a heartbeat every 25 minutes
 * Connections auto-reconnect with backoff. Uses ntfy's JSON stream
 * (GET /<topic>/json) — no account, no API key, self-hostable.
 */
class LinkService : Service() {

    @Volatile private var running = false
    private var dataThread: Thread? = null
    private var cmdThread: Thread? = null
    private var heartbeatThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        createChannels()
        registerEventSources()
        registerMediaObserver()
        registerGuardSensor()
        Automation.refreshWatchers(applicationContext)
        registerSmsObserver()
        instance = this
        Lan.startServer(applicationContext)
        if (Config.btReceiveOn(applicationContext)) BtShare.startServer(applicationContext)
        if (Config.automationOn(applicationContext)) WebhookServer.start(applicationContext)
    }

    // ------------- v2.7: SMS-sent observer (automation trigger) -------------
    private val smsSentObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            try {
                val ctx = applicationContext
                val cur = contentResolver.query(
                    android.provider.Telephony.Sms.Sent.CONTENT_URI,
                    arrayOf("_id", "address", "body"), null, null, "_id DESC LIMIT 1")
                cur?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        val prefs = getSharedPreferences("phonelink_state", MODE_PRIVATE)
                        val last = prefs.getLong("sms_sent_last_id", -1)
                        if (last < 0) {
                            prefs.edit().putLong("sms_sent_last_id", id).apply()
                        } else if (id > last) {
                            prefs.edit().putLong("sms_sent_last_id", id).apply()
                            Automation.onEvent(ctx, "sms_sent", JSONObject()
                                .put("from", it.getString(1) ?: "")
                                .put("body", it.getString(2) ?: "")
                                .put("title", it.getString(1) ?: ""))
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun registerSmsObserver() {
        try {
            contentResolver.registerContentObserver(
                android.provider.Telephony.Sms.CONTENT_URI, true, smsSentObserver)
        } catch (_: Exception) { }
    }

    // ------------- guard mode (movement alarm) -------------

    private var guardListener: android.hardware.SensorEventListener? = null
    private var lastAccel = 0f
    @Volatile private var lastGuardTrigger = 0L

    private fun registerGuardSensor() {
        if (!Config.guardOn(applicationContext)) return
        try {
            val sm = getSystemService(Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
                ?: return
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent?) {
                    e ?: return
                    val m = kotlin.math.sqrt(
                        e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                            e.values[2] * e.values[2])
                    val delta = kotlin.math.abs(m - lastAccel)
                    lastAccel = m
                    val now = System.currentTimeMillis()
                    if (delta > 11f && now - lastGuardTrigger > 60_000L) {
                        lastGuardTrigger = now
                        guardTriggered()
                    }
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) { }
            }
            sm.registerListener(listener, sensor,
                android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            guardListener = listener
        } catch (_: Exception) { }
    }

    private fun guardTriggered() {
        val ctx = applicationContext
        SecLog.add(ctx, "পাহারা মোড: ফোন নাড়ানো হয়েছে")
        RingHelper.start(ctx, maxAll = true)
        if (Config.isSender(ctx)) {
            Relay.send(ctx, Relay.TYPE_SYSTEM, "🚨 পাহারা মোড — ফোন নাড়ানো হয়েছে",
                "পাহারা মোড চালু থাকা অবস্থায় ফোনটা সরানো হয়েছে।")
            LocationHelper.fetchAndSend(ctx)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!running) {
            running = true
            if (Config.isReceiver(applicationContext)) {
                dataThread = thread(name = "pl-data") { listenLoop(cmd = false) }
            }
            if (Config.isSender(applicationContext)) {
                cmdThread = thread(name = "pl-cmd") { listenLoop(cmd = true) }
                heartbeatThread = thread(name = "pl-heartbeat") { heartbeatLoop() }
            }
            if (Config.lostOn(applicationContext) && !lostRunning) {
                lostRunning = true
                thread(name = "pl-lost") { lostLoop() }
            }
            thread(name = "pl-flush") {
                try { Relay.flushQueue(applicationContext) } catch (_: Exception) { }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        Lan.stopServer()
        dataThread?.interrupt(); cmdThread?.interrupt(); heartbeatThread?.interrupt()
        unregisterEventSources()
        try {
            mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) { }
        mediaObserver = null
        try {
            guardListener?.let {
                (getSystemService(Context.SENSOR_SERVICE)
                    as android.hardware.SensorManager).unregisterListener(it)
            }
        } catch (_: Exception) { }
        guardListener = null
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not kill the link: restart the service.
        val ctx = applicationContext
        if (Config.isSender(ctx) || Config.isReceiver(ctx)) {
            try { start(ctx) } catch (_: Exception) { }
        }
        super.onTaskRemoved(rootIntent)
    }

    // ------------- network / ringer event sources (sender side) -------------

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var ringerReceiver: BroadcastReceiver? = null
    private var presenceReceiver: BroadcastReceiver? = null
    private var mediaObserver: ContentObserver? = null
    private var lastWifiSsid: String? = null

    private fun registerEventSources() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ctx = applicationContext
                    if (!Config.isSender(ctx) || !Config.fwdNetwork(ctx)) return
                    val ssid = currentSsid()
                    if (ssid == lastWifiSsid) return
                    lastWifiSsid = ssid
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "Wi-Fi সংযুক্ত হয়েছে",
                        if (ssid.isNotEmpty()) "নেটওয়ার্ক: $ssid" else "")
                }
                override fun onLost(network: Network) {
                    val ctx = applicationContext
                    if (!Config.isSender(ctx) || !Config.fwdNetwork(ctx)) return
                    lastWifiSsid = null
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "Wi-Fi ছেড়ে গেছে", "")
                }
            }
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        } catch (_: Exception) { }
        try {
            val rr = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val ctx = applicationContext
                    if (!Config.isSender(ctx) || !Config.fwdSecurity(ctx)) return
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val mode = when (am.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "সাইলেন্ট"
                        AudioManager.RINGER_MODE_VIBRATE -> "ভাইব্রেট"
                        else -> "সাধারণ (রিংসহ)"
                    }
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "রিংগার মোড বদলেছে", "নতুন মোড: $mode")
                }
            }
            registerReceiver(rr, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
            ringerReceiver = rr
        } catch (_: Exception) { }
        try {
            val pr = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val ctx = applicationContext
                    when (i?.action) {
                        Intent.ACTION_USER_PRESENT -> {
                            Automation.onEvent(ctx, "screen_on", JSONObject())
                            getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                                .edit().remove("screen_off_since").apply()
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            Automation.onEvent(ctx, "screen_off", JSONObject())
                            getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                                .edit().putLong("screen_off_since", System.currentTimeMillis()).apply()
                        }
                    }
                    if (!Config.isSender(ctx) || !Config.fwdPresence(ctx)) return
                    when (i?.action) {
                        Intent.ACTION_USER_PRESENT ->
                            Relay.send(ctx, Relay.TYPE_SYSTEM, "🔓 ফোন আনলক হয়েছে", "")
                        Intent.ACTION_SCREEN_OFF ->
                            Relay.send(ctx, Relay.TYPE_SYSTEM, "🔒 ফোন লক হয়েছে", "")
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(pr, f)
            presenceReceiver = pr
        } catch (_: Exception) { }
    }

    /** Watches the gallery for new photos/screenshots (sender side). */
    private fun registerMediaObserver() {
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    thread { checkNewMedia() }
                }
            }
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            mediaObserver = observer
            val videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    thread { checkNewVideo() }
                }
            }
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver)
        } catch (_: Exception) { }
    }

    /** v2.4: new videos are auto-backed-up too (chunks when large). */
    private fun checkNewVideo() {
        val ctx = applicationContext
        if (!Config.isSender(ctx) || !Config.fwdMedia(ctx)) return
        try {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val cursor = contentResolver.query(collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED),
                null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC") ?: return
            cursor.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(0)
                val name = c.getString(1) ?: "video.mp4"
                val addedSec = c.getLong(2)
                val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                val lastId = prefs.getLong("last_video_id", -1L)
                if (lastId == -1L) {
                    prefs.edit().putLong("last_video_id", id).apply()
                    return
                }
                if (id == lastId) return
                prefs.edit().putLong("last_video_id", id).apply()
                if (System.currentTimeMillis() / 1000 - addedSec > 300) return
                val itemUri = ContentUris.withAppendedId(collection, id)
                val bytes = contentResolver.openInputStream(itemUri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    var overflow = false
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 60 * 1024 * 1024) { overflow = true; break }
                        buf.write(chunk, 0, n)
                    }
                    if (overflow) null else buf.toByteArray()
                } ?: return
                if (bytes.size > 4_500_000) {
                    Chunks.sendLarge(ctx, name, bytes)
                } else {
                    Relay.postFile(ctx, name, bytes, "video/mp4")
                }
            }
        } catch (_: Exception) { }
    }

    private fun checkNewMedia() {
        val ctx = applicationContext
        if (!Config.isSender(ctx) || !Config.fwdMedia(ctx)) return
        try {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cursor = contentResolver.query(collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC") ?: return
            cursor.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(0)
                val name = c.getString(1) ?: "photo.jpg"
                val addedSec = c.getLong(2)
                val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                val lastId = prefs.getLong("last_media_id", -1L)
                if (lastId == -1L) {
                    prefs.edit().putLong("last_media_id", id).apply()
                    return
                }
                if (id == lastId) return
                prefs.edit().putLong("last_media_id", id).apply()
                // Only forward genuinely new captures, not library rescans.
                if (System.currentTimeMillis() / 1000 - addedSec > 180) return
                val itemUri = ContentUris.withAppendedId(collection, id)
                val bytes = contentResolver.openInputStream(itemUri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    var overflow = false
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 5 * 1024 * 1024) { overflow = true; break }
                        buf.write(chunk, 0, n)
                    }
                    if (overflow) null else buf.toByteArray()
                } ?: return
                Relay.postFile(ctx, name, bytes, "image/jpeg")
            }
        } catch (_: Exception) { }
    }

    private fun unregisterEventSources() {
        try {
            netCallback?.let {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        } catch (_: Exception) { }
        netCallback = null
        try { ringerReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        ringerReceiver = null
        try { presenceReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) { }
        presenceReceiver = null
    }

    private fun currentSsid(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid?.trim('"') ?: ""
            if (ssid == "<unknown ssid>") "" else ssid
        } catch (_: Exception) { "" }
    }

    /** Detects SIM changes by comparing the active subscription signature. */
    private fun checkSimChanged() {
        val ctx = applicationContext
        if (!Config.isSender(ctx) || !Config.fwdSecurity(ctx)) return
        try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val subs = sm.activeSubscriptionInfoList ?: return
            val sig = subs.map { "${it.simSlotIndex}:${it.carrierName}:${it.iccId}" }
                .sorted().joinToString("|")
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            val old = prefs.getString("sim_sig", null)
            if (old == null) {
                prefs.edit().putString("sim_sig", sig).apply()
            } else if (old != sig) {
                prefs.edit().putString("sim_sig", sig).apply()
                Relay.send(ctx, Relay.TYPE_SYSTEM, "সিমের তথ্য বদলেছে",
                    "আগের: ${old.ifEmpty { "—" }}\nএখন: ${sig.ifEmpty { "কোনো সিম নেই" }}")
            }
        } catch (_: Exception) { }
    }

    private fun startForegroundCompat() {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lc = localized(applicationContext)
        val roles = mutableListOf<String>()
        if (Config.isSender(applicationContext)) roles.add(lc.getString(R.string.svc_sending))
        if (Config.isReceiver(applicationContext)) roles.add(lc.getString(R.string.svc_receiving))
        val notif = Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(lc.getString(R.string.svc_running))
            .setContentText(if (roles.isEmpty()) lc.getString(R.string.svc_no_mode) else roles.joinToString(" + "))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
    }

    private fun createChannels() {
        val lc = localized(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SERVICE, lc.getString(R.string.svc_channel_service), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_INBOX, lc.getString(R.string.svc_channel_messages), NotificationManager.IMPORTANCE_HIGH))
        // Distinct sounds per message kind (v2.1): calls and priority
        // contacts ring like a phone call, OTP messages use the alarm
        // tone, and quiet-hours deliveries are fully silent.
        val urgent = NotificationChannel(
            CHANNEL_URGENT, lc.getString(R.string.svc_channel_urgent), NotificationManager.IMPORTANCE_HIGH)
        urgent.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        nm.createNotificationChannel(urgent)
        val otp = NotificationChannel(
            CHANNEL_OTP, lc.getString(R.string.svc_channel_otp), NotificationManager.IMPORTANCE_HIGH)
        otp.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        nm.createNotificationChannel(otp)
        val low = NotificationChannel(
            CHANNEL_LOW, lc.getString(R.string.svc_channel_quiet), NotificationManager.IMPORTANCE_LOW)
        low.setSound(null, null)
        low.enableVibration(false)
        nm.createNotificationChannel(low)
    }

    /** Service contexts ignore the activity locale wrap, so build one from the saved language. */
    private fun localized(ctx: Context): Context {
        val lang = Config.language(ctx)
        if (lang == "system") return ctx
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(java.util.Locale(lang))
        return ctx.createConfigurationContext(cfg)
    }

    // ---------------- listening loops ----------------

    private fun listenLoop(cmd: Boolean) {
        var backoff = 3000L
        while (running) {
            try {
                val topic = if (cmd) Config.cmdTopic(applicationContext) else Config.dataTopic(applicationContext)
                val url = URL(Relay.baseUrl(applicationContext) + "/" + topic + "/json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 90000 // ntfy sends keepalives; a dead link times out here
                conn.setRequestProperty("Accept", "application/x-ndjson")
                if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
                backoff = 3000L
                Relay.markRelayOk(applicationContext)
                onNetRestored()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleLine(line, cmd)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (!running) break
                try {
                    val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                    if (prefs.getLong("net_down_since", 0L) == 0L) {
                        prefs.edit().putLong("net_down_since", System.currentTimeMillis()).apply()
                    }
                } catch (_: Exception) { }
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** When the link returns after a long outage, tell the peer how long. */
    private fun onNetRestored() {
        try {
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            val downSince = prefs.getLong("net_down_since", 0L)
            if (downSince == 0L) return
            prefs.edit().remove("net_down_since").apply()
            val mins = (System.currentTimeMillis() - downSince) / 60000
            if (mins >= 10 && Config.isSender(applicationContext)) {
                Relay.send(applicationContext, Relay.TYPE_SYSTEM, "🌐 নেট ফিরে এসেছে",
                    "এই ফোন $mins মিনিট অফলাইনে ছিল।")
            }
        } catch (_: Exception) { }
    }

    fun handleLine(line: String, cmd: Boolean) {
        try {
            val outer = JSONObject(line)
            if (outer.optString("event") != "message") return
            val eventTime = outer.optLong("time", 0L) // seconds on ntfy

            // Skip backlog/replays: keep a per-topic watermark.
            val key = if (cmd) "wm_cmd" else "wm_data"
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            var wm = prefs.getLong(key, 0L)
            if (wm == 0L) {
                // First ever connect: start from now, don't flood old messages.
                wm = System.currentTimeMillis() / 1000
                prefs.edit().putLong(key, wm).apply()
                if (eventTime < wm - 5) return
            }
            if (eventTime <= wm) return
            prefs.edit().putLong(key, eventTime).apply()

            val raw = outer.optString("message", "")
            val attachment = outer.optJSONObject("attachment")
            if (attachment != null && !cmd) {
                // A file uploaded to the topic: the event itself is the item.
                val name = attachment.optString("name", "file")
                val urlStr = attachment.optString("url", "")
                val size = attachment.optLong("size", 0L)
                val sizeText = when {
                    size <= 0 -> ""
                    size < 1024 -> "$size B"
                    size < 1024 * 1024 -> "${size / 1024} KB"
                    else -> String.format("%.1f MB", size / 1048576.0)
                }
                val chunkRef = Chunks.parse(name)
                if (chunkRef != null && !cmd) {
                    Chunks.onChunkArrived(applicationContext, chunkRef, urlStr)
                    return
                }
                val filePayload = JSONObject().apply {
                    put("type", Relay.TYPE_FILE)
                    put("title", "📁 $name")
                    put("body", sizeText)
                    put("extra", urlStr)
                    put("from", "")
                    put("time", eventTime * 1000)
                    if (outer.optString("title", "") == "PhoneLink wallpaper") {
                        put("wallpaper", 1)
                    }
                }
                handleData(filePayload)
                return
            }
            if (raw.isBlank()) return
            val payload = decodePayload(raw, outer, eventTime)
            if (payload == null) {
                // Encrypted but unreadable with this phone's code/settings.
                if (!cmd) {
                    val lc = localized(applicationContext)
                    handleData(JSONObject().apply {
                        put("type", Relay.TYPE_INFO)
                        put("title", lc.getString(R.string.enc_unreadable_title))
                        put("body", lc.getString(R.string.enc_unreadable_body))
                        put("from", "")
                        put("time", eventTime * 1000)
                    })
                }
                return
            }
            if (cmd) handleCommand(payload) else handleData(payload)
        } catch (_: Exception) { }
    }

    private fun decodePayload(raw: String, outer: JSONObject, eventTime: Long): JSONObject? {
        val looksJson = try { JSONObject(raw); true } catch (e: Exception) { false }
        if (!looksJson) {
            // A plain-text message posted to the topic manually.
            return JSONObject().apply {
                put("type", "INFO"); put("title", outer.optString("title", "PhoneLink"))
                put("body", raw); put("from", ""); put("time", eventTime * 1000)
            }
        }
        // Crypto.decrypt passes plain JSON through and returns null only for
        // an encrypted payload this phone cannot read.
        return Crypto.decrypt(applicationContext, raw)
    }

    // ---------------- receiver side ----------------

    private fun handleData(payload: JSONObject) {
        val ctx = applicationContext
        // Group safety: never process our own traffic (3+ phones share a
        // code; device ids keep everyone from hearing themselves).
        val dev = payload.optString("dev", "")
        if (dev.isNotEmpty() && dev == Config.deviceId(ctx)) return
        val type = payload.optString("type", "INFO")
        val from = payload.optString("from", "")
        if (from.isNotEmpty()) {
            val prevSeen = Config.lastSenderSeen(ctx)
            Config.setLastSenderSeen(ctx, System.currentTimeMillis())
            Config.setLastSenderName(ctx, from)
            if (prevSeen > 0 && System.currentTimeMillis() - prevSeen > 10 * 60_000L) {
                Automation.onEvent(ctx, "peer_online",
                    JSONObject().put("title", from))
            }
            Peers.touch(ctx, from, payload.optInt("battery", -1),
                payload.optString("cfg", ""), payload.optString("status", ""),
                payload.optString("dev", ""))
        }
        // v2.9: peer rule sync (only lands when this phone opted in too)
        if (type == "RULESYNC") {
            if (Config.rulesSyncOn(ctx)) {
                val body = payload.optString("body", "")
                if (body.isNotEmpty()) Automation.applyRemoteRules(ctx, body)
            }
            return
        }

        if (type == Relay.TYPE_HEARTBEAT) {
            val battery = payload.optInt("battery", -1)
            if (battery >= 0) {
                Config.setLastSenderBattery(ctx, battery)
                BatteryHist.add(ctx, battery)
            }
            StatusWidgetProvider.refresh(ctx)
            return // silent: only updates "last seen" + widget
        }

        // SMS/call items carry the number so the inbox can offer a
        // two-way reply; notification items carry their reply key.
        // Both ride in the extra field with a tel:/notif: prefix.
        var extraStr = payload.optString("extra", "")
        if ((type == Relay.TYPE_SMS || type == Relay.TYPE_CALL) && payload.has("number")) {
            extraStr = "tel:" + payload.optString("number")
        }
        if (type == Relay.TYPE_NOTIF && payload.has("notifKey")) {
            extraStr = "notif:" + payload.optString("notifKey")
        }
        val priority = payload.optInt("priority", 0) == 1
        val ringing = payload.optInt("ringing", 0) == 1

        val item = History.Item(
            type = type,
            title = payload.optString("title", "PhoneLink"),
            body = payload.optString("body", ""),
            from = from,
            time = payload.optLong("time", System.currentTimeMillis()),
            id = payload.optString("id", ""),
            code = payload.optString("code", ""),
            extra = extraStr
        )
        History.add(ctx, item)
        Stats.incRecv(ctx, type)
        // v2.4 per-peer rules on this receiver: mute = store silently,
        // digest = hold notifications from this peer for the bundle.
        val peerMode = Config.peerMode(ctx, payload.optString("dev", ""))
        if (peerMode == "digest" && type == Relay.TYPE_NOTIF) {
            DigestStore.add(ctx, from, item.title, item.body)
            if (item.id.isNotEmpty()) Relay.sendAck(ctx, item.id, item.title)
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        val peerMuted = peerMode == "mute"
        if (!peerMuted) showInboxNotification(item, priority)
        // Siren messages demand attention: full screen + loud ringing
        // until the page is opened (opening it stops the ring).
        if (type == Relay.TYPE_URGENT && !peerMuted) {
            showScreenMessage(item.title + "\n" + item.body)
            RingHelper.start(ctx, maxAll = true)
        }
        // A file sent as a wallpaper is applied right away.
        if (type == Relay.TYPE_FILE && payload.optInt("wallpaper", 0) == 1 &&
            item.extra.startsWith("http")) {
            thread { applyWallpaper(item.extra) }
        }
        // Auto-save incoming files to Downloads when enabled.
        if (type == Relay.TYPE_FILE && Config.autosaveMedia(ctx) &&
            item.extra.startsWith("http")) {
            downloadToDevice(item.extra, item.title.removePrefix("📁 "))
        }
        // v2.4 clipboard auto-sync: apply a received clip right away
        // (Android may block background clipboard writes; then the
        // inbox item remains as the manual path).
        if (type == Relay.TYPE_CLIP && Config.clipSyncOn(ctx) && item.body.isNotEmpty()) {
            try {
                startActivity(Intent(this, CopyActivity::class.java)
                    .putExtra("text", item.body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
        // A ringing call gets a full-screen alert with quick SMS replies,
        // and (when enabled) the caller's name is spoken aloud.
        if (ringing && type == Relay.TYPE_CALL && extraStr.startsWith("tel:") && !peerMuted) {
            showCallAlert(item, extraStr.removePrefix("tel:"))
            if (Config.ttsCalls(ctx) && !Config.isQuietNow(ctx)) {
                val lc = localized(ctx)
                speakText(lc.getString(R.string.tts_call_prefix) + " " + item.body)
            }
        }
        // Delivery receipt back to the sender (commands topic), when the
        // payload carries an id (v2). Old/plain messages simply skip it.
        if (item.id.isNotEmpty()) Relay.sendAck(ctx, item.id, item.title)
        sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
    }

    /** Downloads a received wallpaper image and applies it. */
    private fun applyWallpaper(urlStr: String) {
        val ctx = applicationContext
        try {
            val bytes = URL(urlStr).openStream().use { it.readBytes() }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                android.app.WallpaperManager.getInstance(ctx).setBitmap(bmp)
                Relay.send(ctx, Relay.TYPE_INFO, "🖼️ ওয়ালপেপার সেট হয়েছে", "")
                return
            }
        } catch (_: Exception) { }
        Relay.send(ctx, Relay.TYPE_INFO, "ওয়ালপেপার সেট করা যায়নি", "")
    }

    /** Saves a received file straight to Downloads (auto-save mode). */
    private fun downloadToDevice(urlStr: String, name: String) {
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE)
                as android.app.DownloadManager
            val req = android.app.DownloadManager.Request(Uri.parse(urlStr))
                .setTitle(name)
                .setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name)
            dm.enqueue(req)
        } catch (_: Exception) { }
    }

    private var notifId = 100

    private fun showInboxNotification(item: History.Item, priority: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lc = localized(applicationContext)
        // Channel (and therefore sound) by kind: quiet hours silence
        // everything except calls and priority contacts; OTP codes get
        // their own tone; urgent items ring like a call.
        val urgent = priority || item.type == Relay.TYPE_CALL
        val quiet = Config.isQuietNow(applicationContext) && !urgent
        val channel = when {
            quiet -> CHANNEL_LOW
            item.code.isNotEmpty() -> CHANNEL_OTP
            urgent -> CHANNEL_URGENT
            else -> CHANNEL_INBOX
        }
        val builder = Notification.Builder(this, channel)
            .setContentTitle(item.title)
            .setContentText(item.body.take(120))
            .setStyle(Notification.BigTextStyle().bigText(item.body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setAutoCancel(true)
        synchronized(this) { notifId += 1 }
        val myId = notifId
        // OTP items get a one-tap "copy code" action (opens a tiny activity,
        // because background clipboard writes are blocked on Android 10+).
        if (item.code.isNotEmpty()) {
            val copyIntent = Intent(this, CopyActivity::class.java)
                .putExtra("text", item.code)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val copyPi = PendingIntent.getActivity(this, myId, copyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_menu_add,
                lc.getString(R.string.action_copy_code), copyPi)
        }
        // File items get a direct Download Manager action.
        if (item.type == Relay.TYPE_FILE && item.extra.isNotEmpty()) {
            val dlIntent = Intent(this, ActionReceiver::class.java)
                .setAction(ActionReceiver.ACTION_DOWNLOAD)
                .putExtra("url", item.extra)
                .putExtra("name", item.title.removePrefix("📁 "))
            val dlPi = PendingIntent.getBroadcast(this, myId, dlIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.stat_sys_download,
                lc.getString(R.string.action_download), dlPi)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(myId, builder.build())
        // Spoken OTP: read the code aloud so it can be heard hands-free.
        if (item.code.isNotEmpty() && Config.ttsCodes(applicationContext) && !quiet) {
            speakCode(item.code)
        }
    }

    // ------------- text-to-speech (OTP codes + caller announce) -------------

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var pendingSpeak: String? = null

    private fun speakCode(code: String) {
        val lc = localized(applicationContext)
        speakText(lc.getString(R.string.tts_code_prefix) + " " +
            code.toCharArray().joinToString(" "))
    }

    private fun speakText(text: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            try { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pl-tts") } catch (_: Exception) { }
            return
        }
        if (engine == null) {
            pendingSpeak = text
            try {
                tts = TextToSpeech(applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        try { tts?.language = java.util.Locale("bn", "BD") } catch (_: Exception) { }
                        pendingSpeak?.let {
                            try { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "pl-tts") }
                            catch (_: Exception) { }
                        }
                        pendingSpeak = null
                    }
                }
            } catch (_: Exception) { }
        } else {
            pendingSpeak = text
        }
    }

    /** Full-screen incoming-call alert with one-tap SMS quick replies. */
    private fun showCallAlert(item: History.Item, number: String) {
        val lc = localized(applicationContext)
        val screenIntent = Intent(this, ScreenActivity::class.java)
            .putExtra("text", "📞 ${item.title}\n${item.body}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        synchronized(this) { notifId += 1 }
        val myId = notifId
        val screenPi = PendingIntent.getActivity(this, myId, screenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_URGENT)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(Notification.BigTextStyle().bigText(item.body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(screenPi)
            .setFullScreenIntent(screenPi, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
        val presets = listOf(
            lc.getString(R.string.quick_callback),
            lc.getString(R.string.quick_busy)
        )
        presets.forEachIndexed { idx, preset ->
            val qi = Intent(this, ActionReceiver::class.java)
                .setAction(ActionReceiver.ACTION_QUICK_SMS)
                .putExtra("number", number)
                .putExtra("text", preset)
            val qpi = PendingIntent.getBroadcast(this, myId * 10 + idx, qi,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_menu_send, preset, qpi)
        }
        getSystemService(NotificationManager::class.java).notify(myId, builder.build())
    }

    /** Big on-screen message requested by the other phone (SCREEN command). */
    private fun showScreenMessage(text: String) {
        val lc = localized(applicationContext)
        val intent = Intent(this, ScreenActivity::class.java)
            .putExtra("text", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        synchronized(this) { notifId += 1 }
        val myId = notifId
        val pi = PendingIntent.getActivity(this, myId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(this, CHANNEL_INBOX)
            .setContentTitle(lc.getString(R.string.screen_msg_title))
            .setContentText(text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(myId, notif)
    }

    // ---------------- sender side: remote commands ----------------

    /**
     * Sensitive commands (snapshot, lock, remote settings, modes) need the
     * command PIN. The payload carries the PIN digest; this phone compares
     * it with its own. Three failures lock gated commands for 30 minutes.
     */
    private fun pinOk(payload: JSONObject): Boolean {
        val ctx = applicationContext
        val stored = Config.cmdPinDigest(ctx)
        if (stored == null) {
            Relay.send(ctx, Relay.TYPE_INFO, "❌ কমান্ড বাতিল",
                "এই ফোনে কমান্ড PIN সেট করা নেই — সংবেদনশীল কমান্ড চলবে না।")
            return false
        }
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now < prefs.getLong("pin_lock_until", 0L)) {
            Relay.send(ctx, Relay.TYPE_INFO, "❌ কমান্ড বাতিল",
                "ভুল PIN-এর কারণে সংবেদনশীল কমান্ড সাময়িক বন্ধ আছে।")
            return false
        }
        if (payload.optString("pin", "") == stored) {
            prefs.edit().putInt("pin_fails", 0).apply()
            return true
        }
        val fails = prefs.getInt("pin_fails", 0) + 1
        val e = prefs.edit().putInt("pin_fails", fails)
        if (fails >= 3) {
            e.putLong("pin_lock_until", now + 30 * 60 * 1000L)
            e.putInt("pin_fails", 0)
        }
        e.apply()
        SecLog.add(ctx, "ভুল কমান্ড PIN দিয়ে চেষ্টা")
        Relay.send(ctx, Relay.TYPE_INFO, "❌ কমান্ড বাতিল — PIN মেলেনি", "")
        try {
            val lc = localized(ctx)
            val warn = History.Item(
                type = Relay.TYPE_INFO,
                title = lc.getString(R.string.pin_warn_title),
                body = lc.getString(R.string.pin_warn_body),
                from = "", time = now, id = "", code = "", extra = "")
            History.add(ctx, warn)
            showInboxNotification(warn, priority = true)
        } catch (_: Exception) { }
        return false
    }

    private fun handleCommand(payload: JSONObject) {
        val ctx = applicationContext
        // Never act on our own command-topic traffic (group safety).
        val dev = payload.optString("dev", "")
        if (dev.isNotEmpty() && dev == Config.deviceId(ctx)) return
        // Reactions to our messages.
        if (payload.optString("type") == "REACT") {
            val emoji = payload.optString("emoji", "👍")
            val title = payload.optString("title", "")
            val item = History.Item(
                type = Relay.TYPE_INFO,
                title = "$emoji রিঅ্যাকশন",
                body = "${payload.optString("from", "")} তোমার মেসেজে রিঅ্যাকশন দিয়েছে: $title",
                from = payload.optString("from", ""),
                time = System.currentTimeMillis(), id = "", code = "", extra = "")
            History.add(ctx, item)
            showInboxNotification(item)
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        // Shared note updates.
        if (payload.optString("type") == "NOTE") {
            Config.setSharedNote(ctx, payload.optString("text", ""))
            val item = History.Item(
                type = Relay.TYPE_INFO, title = "📝 যৌথ নোট আপডেট হয়েছে",
                body = payload.optString("text", "").take(200),
                from = payload.optString("from", ""),
                time = System.currentTimeMillis(), id = "", code = "", extra = "")
            History.add(ctx, item)
            showInboxNotification(item)
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        // Photo-list replies for the gallery browser.
        if (payload.optString("type") == "PHOTOS") {
            getSharedPreferences("phonelink_state", Context.MODE_PRIVATE).edit()
                .putString("last_photos", payload.optString("items", "[]"))
                .putLong("last_photos_at", System.currentTimeMillis())
                .apply()
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        // Delivery receipts ride the command topic too.
        if (payload.optString("type") == "ACK") {
            Relay.onAck(ctx, payload.optString("ack"), payload.optString("title"))
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        // Seen receipts: the other phone has opened its inbox.
        if (payload.optString("type") == "SEEN") {
            val n = payload.optString("ids").split(",").count { it.isNotBlank() }
            Relay.onSeen(ctx, n)
            sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
            return
        }
        if (payload.optString("type") != "COMMAND") return
        // v2.4 group roles: when a commander list exists, only those
        // devices may run actual commands here (viewers only receive).
        if (!Config.commandsAllowedFrom(ctx, dev)) {
            Relay.send(ctx, Relay.TYPE_INFO, "⛔ কমান্ডের অনুমতি নেই",
                "এই ফোনে তোমার ডিভাইস কমান্ড চালানোর তালিকায় নেই (শুধু দেখার ভূমিকা)।")
            return
        }
        // (Our own commands were already filtered by device id above.)
        when (payload.optString("command")) {
            "LOCATION" -> if (Config.allowLocation(ctx)) LocationHelper.fetchAndSend(ctx)
            "BATTERY" -> Relay.send(ctx, Relay.TYPE_BATTERY, "ব্যাটারি স্ট্যাটাস",
                SystemReceiver.batteryStatusText(ctx))
            "RING" -> if (Config.allowRing(ctx)) {
                RingHelper.start(ctx)
                Relay.send(ctx, Relay.TYPE_INFO, "রিং বাজছে", "এই ফোনে রিং চালু করা হয়েছে (৪৫ সেকেন্ড)।")
            }
            "STOP_RING" -> {
                RingHelper.stop(); FlashHelper.stopStrobe()
                Relay.send(ctx, Relay.TYPE_INFO, "রিং বন্ধ", "রিং বন্ধ করা হয়েছে।")
            }
            "LOST" -> if (Config.allowRing(ctx)) {
                RingHelper.start(ctx, maxAll = true)
                if (Config.allowFlash(ctx)) FlashHelper.startStrobe(ctx, 45)
                Relay.send(ctx, Relay.TYPE_INFO, "লস্ট মোড চালু", "সর্বোচ্চ শব্দে রিং + ফ্ল্যাশ জ্বলছে (৪৫ সেকেন্ড)।")
            }
            "FLASH_ON" -> if (Config.allowFlash(ctx)) {
                FlashHelper.setTorch(ctx, true)
                Relay.send(ctx, Relay.TYPE_INFO, "ফ্ল্যাশলাইট অন", "")
            }
            "FLASH_OFF" -> if (Config.allowFlash(ctx)) {
                FlashHelper.setTorch(ctx, false)
                Relay.send(ctx, Relay.TYPE_INFO, "ফ্ল্যাশলাইট অফ", "")
            }
            "INFO" -> Relay.send(ctx, Relay.TYPE_INFO, "ফোনের তথ্য", PhoneInfo.text(ctx))
            "SNAP" -> {
                when {
                    !pinOk(payload) -> { }
                    !Config.allowSnap(ctx) ->
                        Relay.send(ctx, Relay.TYPE_INFO, "স্ন্যাপশট বন্ধ আছে",
                            "ওই ফোনে Settings থেকে 'রিমোট ছবি' অনুমতি দেওয়া হয়নি।")
                    ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED ->
                        Relay.send(ctx, Relay.TYPE_INFO, "ক্যামেরা permission নেই",
                            "ছবি তুলতে ওই ফোনে ক্যামেরা permission লাগবে।")
                    else -> try {
                        androidx.core.content.ContextCompat.startForegroundService(
                            ctx, Intent(ctx, SnapService::class.java))
                    } catch (_: Exception) { }
                }
            }
            "REPLY_SMS" -> {
                val number = payload.optString("number", "")
                val text = payload.optString("text", "")
                when {
                    !Config.allowSmsReply(ctx) ->
                        Relay.send(ctx, Relay.TYPE_INFO, "SMS রিপ্লাই বন্ধ আছে",
                            "ওই ফোনে Settings থেকে SMS রিপ্লাইয়ের অনুমতি দেওয়া হয়নি।")
                    number.isEmpty() || text.isEmpty() -> { }
                    ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                        != PackageManager.PERMISSION_GRANTED ->
                        Relay.send(ctx, Relay.TYPE_INFO, "SMS পাঠানোর permission নেই",
                            "রিপ্লাই পাঠাতে ওই ফোনে SEND_SMS permission লাগবে।")
                    else -> try {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault().sendTextMessage(number, null, text.take(600), null, null)
                        Relay.send(ctx, Relay.TYPE_INFO, "✉️ রিপ্লাই পাঠানো হয়েছে",
                            "$number নম্বরে: ${text.take(80)}")
                    } catch (e: Exception) {
                        Relay.send(ctx, Relay.TYPE_INFO, "রিপ্লাই পাঠানো যায়নি", "${e.message}")
                    }
                }
            }
            "SUMMARY" -> thread {
                Relay.send(ctx, Relay.TYPE_SYSTEM, "🗓️ দৈনিক সারাংশ", DailySummary.build(ctx))
            }
            "REPLY_NOTIF" -> {
                val key = payload.optString("key", "")
                val text = payload.optString("text", "")
                val ok = key.isNotEmpty() && text.isNotEmpty() &&
                    NotifListener.fireReply(ctx, key, text)
                Relay.send(ctx, Relay.TYPE_INFO,
                    if (ok) "✉️ রিপ্লাই পাঠানো হয়েছে" else "রিপ্লাই পাঠানো যায়নি",
                    if (ok) text.take(80)
                    else "নোটিফিকেশনটা আর সক্রিয় নেই, বা ওই অ্যাপে রিপ্লাই সুবিধা নেই।")
            }
            "CHUNK_NEED" -> {
                val missing = payload.optJSONArray("missing")
                if (missing != null) Chunks.resend(ctx, payload.optString("arg", ""), missing)
            }
            "CHUNK_DONE" -> Chunks.forgetSent(ctx, payload.optString("arg", ""))
            "STATUS" -> thread {
                val upMin = (System.currentTimeMillis() - startedAt) / 60000
                val body = PhoneInfo.text(ctx) +
                    "\nসার্ভিস আপটাইম: $upMin মিনিট" +
                    "\nরিলে ল্যাটেন্সি: ${Relay.lastLatencyMs(ctx)} ms" +
                    "\nঅফলাইন কিউ: ${Relay.queueSize(ctx)} টা" +
                    "\nমোট পাঠানো: ${Stats.sent(ctx)} • পাওয়া: ${Stats.recv(ctx)}"
                Relay.send(ctx, Relay.TYPE_INFO, "🩺 হেলথ রিপোর্ট", body)
            }
            "SCREEN" -> {
                val text = payload.optString("arg", "")
                if (text.isNotEmpty()) showScreenMessage(text)
            }
            "LOCK" -> if (pinOk(payload)) {
                val ok = AdminReceiver.lockNow(ctx)
                SecLog.add(ctx, if (ok) "রিমোট LOCK কমান্ডে ফোন লক হয়েছে"
                    else "রিমোট LOCK চেষ্টা — admin চালু নেই")
                if (!ok) Relay.send(ctx, Relay.TYPE_INFO, "লক করা যায়নি",
                    "ওই ফোনে Device admin চালু করা নেই।")
            }
            "SET" -> if (pinOk(payload)) {
                val key = payload.optString("key", "")
                val value = payload.optInt("value", -1)
                if (key in Config.remoteKeys() && (value == 0 || value == 1)) {
                    Config.applyRemote(ctx, key, value == 1)
                    SecLog.add(ctx, "রিমোট সেটিং: $key → $value")
                    if (key == "sender_on" || key == "receiver_on") restart(ctx)
                    Relay.send(ctx, Relay.TYPE_INFO, "🎛️ সেটিং বদল হয়েছে",
                        "$key → ${if (value == 1) "চালু" else "বন্ধ"}")
                }
            }
            "MODE" -> if (pinOk(payload)) {
                val mode = payload.optString("mode", "off")
                Config.applyMode(ctx, mode)
                Relay.send(ctx, Relay.TYPE_INFO, "🎛️ মোড চালু হয়েছে", mode)
            }
            "PAUSE" -> {
                val mins = payload.optInt("minutes", 60).coerceIn(5, 720)
                Relay.setPauseMinutes(ctx, mins)
                Relay.send(ctx, Relay.TYPE_INFO, "⏸️ ফরোয়ার্ড বিরতি",
                    "$mins মিনিটের জন্য ফরোয়ার্ড বন্ধ রাখা হয়েছে।")
            }
            "SPEAK" -> {
                val text = payload.optString("text", "")
                if (text.isNotEmpty()) {
                    speakText(text)
                    Relay.send(ctx, Relay.TYPE_INFO, "🔊 বলা হয়েছে", text.take(80))
                }
            }
            "MEDIA" -> mediaControl(payload.optString("action", ""))
            "RINGER" -> ringerControl(payload.optString("mode", ""))
            "ALARM" -> scheduleAlarm(payload.optInt("minutes", 0),
                payload.optString("text", ""))
            "SOS_FLASH" -> {
                FlashHelper.blinkSos(ctx)
                Relay.send(ctx, Relay.TYPE_INFO, "🔦 SOS ফ্ল্যাশ চলছে", "")
            }
            "LIST_RECENT" -> thread { sendRecentList(payload.optString("kind", "calls")) }
            "LIST_PHOTOS" -> thread { sendPhotoList() }
            "FETCH_PHOTO" -> thread { fetchPhoto(payload.optLong("mediaId", -1L)) }
            "DISMISS" -> {
                val key = payload.optString("key", "")
                val ok = if (key.isEmpty()) NotifListener.dismissAll()
                    else NotifListener.dismiss(key)
                Relay.send(ctx, Relay.TYPE_INFO,
                    if (ok) "🧹 নোটিফিকেশন সরানো হয়েছে" else "ডিসমিস করা যায়নি",
                    if (ok) "" else "নোটিফিকেশন অ্যাক্সেস চালু নেই বা আইটেমটা আর নেই।")
            }
            "GET_CLIP" -> {
                var text: String? = null
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                } catch (_: Exception) { }
                if (!text.isNullOrEmpty()) {
                    Relay.send(ctx, Relay.TYPE_CLIP, "📋 ক্লিপবোর্ড (ওই ফোনের)", text)
                } else {
                    Relay.send(ctx, Relay.TYPE_INFO, "ক্লিপবোর্ড পড়া যায়নি",
                        "Android নিরাপত্তা: অ্যাপ সামনে খোলা না থাকলে ক্লিপবোর্ড পড়া যায় না। ওই ফোনে PhoneLink একবার খুলে আবার চেষ্টা করো।")
                }
            }
            "OPEN" -> openLinkNotification(payload.optString("url", ""))
            "LOST_ON" -> if (pinOk(payload)) setLostMode(ctx, true)
            "LOST_OFF" -> if (pinOk(payload)) setLostMode(ctx, false)
            "SCREENSHOT" -> {
                if (!pinOk(payload)) {
                    // pinOk already replied
                } else if (!ScreenShareService.capture(applicationContext)) {
                    Relay.send(ctx, Relay.TYPE_INFO, "স্ক্রিন সেশন চালু নেই",
                        "স্ক্রিনশটের জন্য ওই ফোনে PhoneLink খুলে 'স্ক্রিন শেয়ার সেশন' একবার চালু করতে হবে (Android-এর সম্মতি লাগে)।")
                }
            }
            "DELETE_MSG" -> {
                val id = payload.optString("msgId", "")
                if (id.isNotEmpty()) {
                    History.removeById(ctx, id)
                    sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
                    Relay.send(ctx, Relay.TYPE_INFO, "🗑️ মেসেজ মোছা হয়েছে", "")
                }
            }
            "PING" -> Relay.send(ctx, Relay.TYPE_INFO, "ফোন অনলাইন আছে",
                "${Config.deviceName(ctx)} — ${SystemReceiver.batteryStatusText(ctx)}")
        }
    }

    // ---------------- v2.3 command helpers ----------------

    private fun mediaControl(action: String) {
        val ctx = applicationContext
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            fun key(code: Int) {
                val now = android.os.SystemClock.uptimeMillis()
                am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, code, 0))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_UP, code, 0))
            }
            when (action) {
                "play" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                "pause" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                "playpause" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "next" -> key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                "prev" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "volup" -> am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "voldown" -> am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                else -> return
            }
            Relay.send(ctx, Relay.TYPE_INFO, "🎵 মিডিয়া কমান্ড চলেছে", action)
        } catch (_: Exception) { }
    }

    private fun ringerControl(mode: String) {
        val ctx = applicationContext
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val target = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                else -> return
            }
            if (target == AudioManager.RINGER_MODE_SILENT) {
                val nm = getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    Relay.send(ctx, Relay.TYPE_INFO, "সাইলেন্ট করা যায়নি",
                        "সাইলেন্ট মোডের জন্য ওই ফোনে Do Not Disturb access লাগবে (Settings → Sound → Do Not Disturb access)।")
                    return
                }
            }
            am.ringerMode = target
            Relay.send(ctx, Relay.TYPE_INFO, "🔔 রিঙ্গার মোড বদল হয়েছে", mode)
        } catch (_: Exception) { }
    }

    private fun scheduleAlarm(minutes: Int, text: String) {
        val ctx = applicationContext
        if (minutes <= 0) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE)
                as android.app.AlarmManager
            val intent = Intent(ctx, AlarmReceiver::class.java)
                .putExtra("text", text.ifEmpty { "⏰ রিমোট অ্যালার্ম" })
            val pi = PendingIntent.getBroadcast(ctx, 778, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + minutes * 60_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            }
            val hhmm = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(at))
            Relay.send(ctx, Relay.TYPE_INFO, "⏰ অ্যালার্ম সেট হয়েছে", "$hhmm টায় বাজবে।")
        } catch (e: Exception) {
            Relay.send(ctx, Relay.TYPE_INFO, "অ্যালার্ম সেট করা যায়নি", "${e.message}")
        }
    }

    private fun sendRecentList(kind: String) {
        val ctx = applicationContext
        val sb = StringBuilder()
        try {
            if (kind == "sms") {
                val cursor = ctx.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"), null, null, "date DESC")
                cursor?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 10) {
                        n++
                        val addr = c.getString(0) ?: ""
                        val body = (c.getString(1) ?: "").replace("\n", " ").take(60)
                        val date = c.getLong(2)
                        val whenText = java.text.SimpleDateFormat("dd MMM hh:mm a",
                            java.util.Locale.getDefault()).format(java.util.Date(date))
                        sb.append("$n. ${Contacts.labelForNumber(ctx, addr)}\n   $body\n   $whenText\n")
                    }
                }
                if (sb.isEmpty()) sb.append("কোনো SMS পাওয়া যায়নি।")
                Relay.send(ctx, Relay.TYPE_SYSTEM, "💬 সাম্প্রতিক SMS", sb.toString().trim())
            } else {
                val cursor = ctx.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION),
                    null, null, "${android.provider.CallLog.Calls.DATE} DESC")
                cursor?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 10) {
                        n++
                        val num = c.getString(0) ?: ""
                        val type = c.getInt(1)
                        val date = c.getLong(2)
                        val dur = c.getLong(3)
                        val arrow = when (type) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "📥"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "📤"
                            android.provider.CallLog.Calls.MISSED_TYPE -> "🚫 মিসড"
                            else -> "•"
                        }
                        val whenText = java.text.SimpleDateFormat("dd MMM hh:mm a",
                            java.util.Locale.getDefault()).format(java.util.Date(date))
                        sb.append("$n. $arrow ${Contacts.labelForNumber(ctx, num)} — ${dur}s\n   $whenText\n")
                    }
                }
                if (sb.isEmpty()) sb.append("কোনো কল পাওয়া যায়নি।")
                Relay.send(ctx, Relay.TYPE_SYSTEM, "📞 সাম্প্রতিক কল", sb.toString().trim())
            }
        } catch (e: Exception) {
            Relay.send(ctx, Relay.TYPE_INFO, "তালিকা আনা যায়নি",
                "permission নেই বা এই ফোনে পড়া যাচ্ছে না।")
        }
    }

    private fun sendPhotoList() {
        val ctx = applicationContext
        try {
            val items = org.json.JSONArray()
            val cursor = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            cursor?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 20) {
                    n++
                    items.put(JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1) ?: "photo.jpg")
                        .put("date", c.getLong(2) * 1000)
                        .put("size", c.getLong(3)))
                }
            }
            val payload = JSONObject().apply {
                put("v", 2); put("type", "PHOTOS")
                put("items", items.toString())
                put("from", Config.deviceName(ctx)); put("dev", Config.deviceId(ctx))
                put("time", System.currentTimeMillis())
            }
            Relay.post(ctx, Config.cmdTopic(ctx), payload)
        } catch (_: Exception) { }
    }

    private fun fetchPhoto(mediaId: Long) {
        val ctx = applicationContext
        if (mediaId < 0) return
        try {
            val itemUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            var name = "photo.jpg"
            ctx.contentResolver.query(itemUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val bytes = ctx.contentResolver.openInputStream(itemUri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0
                var overflow = false
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > 5 * 1024 * 1024) { overflow = true; break }
                    buf.write(chunk, 0, n)
                }
                if (overflow) null else buf.toByteArray()
            } ?: return
            Relay.postFile(ctx, name, bytes, "image/jpeg")
        } catch (_: Exception) { }
    }

    private fun openLinkNotification(urlRaw: String) {
        val ctx = applicationContext
        var url = urlRaw.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 779, view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            synchronized(this) { notifId += 1 }
            val notif = Notification.Builder(this, CHANNEL_INBOX)
                .setContentTitle("🔗 লিংক এসেছে")
                .setContentText(url)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(notifId, notif)
            Relay.send(ctx, Relay.TYPE_INFO, "লিংক পাঠানো হয়েছে",
                "ওই ফোনে নোটিফিকেশনে ট্যাপ করলেই লিংকটা খুলবে।")
        } catch (_: Exception) { }
    }

    private fun heartbeatLoop() {
        // First heartbeat shortly after start so the receiver sees us online.
        try { Thread.sleep(8000) } catch (_: InterruptedException) { return }
        while (running) {
            try {
                val hctx = applicationContext
                val cfg = Config.remoteKeys().joinToString(",") { k ->
                    "$k:${if (Config.remoteState(hctx, k)) 1 else 0}"
                }
                val extra = JSONObject()
                    .put("battery", SystemReceiver.batteryPercent(hctx))
                    .put("cfg", cfg)
                    .put("status", Config.myStatus(hctx).ifEmpty { autoSleepStatus(hctx) })
                Relay.send(hctx, Relay.TYPE_HEARTBEAT, "heartbeat", "", extra)
                Relay.flushQueue(applicationContext)
                checkSimChanged()
                flushDigestIfDue()
                checkGeofence()
                checkDailySummary()
                checkLowBatteryLocation()
                checkRoutine()
                checkUsageLimits()
                checkSchedules()
                checkDevOptions()
                checkChargeFull()
                checkDataUsage()
                checkWeeklyReport()
                checkCheckin()
                Automation.tick(hctx)
                Chunks.requestMissing(hctx)
                Chunks.cleanupSent(hctx)
                if (Lan.peerIp(hctx).isEmpty()) Lan.discoverAsync(hctx)
            } catch (_: Exception) { }
            // Battery-friendly: the poorer the battery, the rarer the beat.
            val pct = SystemReceiver.batteryPercent(applicationContext)
            val sleepMin = when {
                pct in 0..19 -> 60L
                pct in 20..50 -> 40L
                else -> 25L
            }
            try { Thread.sleep(sleepMin * 60 * 1000L) } catch (_: InterruptedException) { break }
        }
    }

    // ------------- v2.4 lost mode -------------

    @Volatile private var lostRunning = false
    private var lostFrontNext = true
    private var lostDarkShown = false

    private fun lostLoop() {
        val ctx = applicationContext
        while (running && Config.lostOn(ctx)) {
            try {
                LocationHelper.fetchAndSend(ctx)
                // Alternate front/back photos (camera permission needed;
                // lost mode itself is the owner's consent).
                if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                    SessionState.snapFront = lostFrontNext
                    lostFrontNext = !lostFrontNext
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(
                            ctx, Intent(ctx, SnapService::class.java))
                    } catch (_: Exception) { }
                }
                recordAmbient(ctx)
                if (Config.lostDarkScreen(ctx) && !lostDarkShown) {
                    lostDarkShown = true
                    startActivity(Intent(ctx, DarkActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // Dying battery: one last immediate location burst.
                if (SystemReceiver.batteryPercent(ctx) in 1..5) {
                    LocationHelper.fetchAndSend(ctx)
                }
            } catch (_: Exception) { }
            try { Thread.sleep(3 * 60_000L) } catch (_: InterruptedException) { break }
        }
        lostRunning = false
        lostDarkShown = false
    }

    /** Records ~25 s of ambient audio and sends it as a file. */
    private fun recordAmbient(ctx: Context) {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            val file = java.io.File(ctx.cacheDir, "lost-audio.m4a")
            @Suppress("DEPRECATION")
            val rec = android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare(); rec.start()
            Thread.sleep(25_000)
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            if (file.exists() && file.length() > 0) {
                Relay.postFile(ctx, "lost-audio-${System.currentTimeMillis()}.m4a",
                    file.readBytes(), "audio/mp4")
            }
        } catch (_: Exception) { }
    }

    /** Expected check-in watchdog: alert the peer when it never came. */
    private fun checkCheckin() {
        val ctx = applicationContext
        val expect = Config.checkinExpectMin(ctx)
        if (expect < 0) return
        val cal = java.util.Calendar.getInstance()
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        if (mins < expect + 30) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (Config.lastCheckinDay(ctx) == today) return
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        if (prefs.getString("checkin_alert_day", "") == today) return
        prefs.edit().putString("checkin_alert_day", today).apply()
        Relay.send(ctx, Relay.TYPE_SYSTEM, "⚠️ আজকের চেক-ইন আসেনি",
            "${Config.deviceName(ctx)} আজ প্রত্যাশিত সময়ে চেক-ইন করেনি।")
    }

    /** v2.4 inferred status: long screen-off at night reads as sleep. */
    private fun autoSleepStatus(ctx: Context): String {
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        val offSince = prefs.getLong("screen_off_since", 0L)
        if (offSince == 0L) return ""
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= 22 || hour < 6
        return if (night && System.currentTimeMillis() - offSince > 90 * 60_000L) {
            "💤 সম্ভবত ঘুমিয়ে"
        } else ""
    }

    /** Sends any scheduled messages that are due. */
    private fun checkSchedules() {
        val ctx = applicationContext
        val due = SchedStore.takeDue(ctx, System.currentTimeMillis())
        for (e in due) {
            Relay.send(ctx, Relay.TYPE_NOTIF, "⏰ ${e.text}", "")
        }
    }

    /** Warns when Developer options / USB debugging get switched on. */
    private fun checkDevOptions() {
        val ctx = applicationContext
        try {
            val adb = android.provider.Settings.Global.getInt(
                ctx.contentResolver, "adb_enabled", 0)
            val dev = android.provider.Settings.Global.getInt(
                ctx.contentResolver, "development_settings_enabled", 0)
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            val key = "dev_opt_seen"
            val prev = prefs.getInt(key, -1)
            val nowVal = adb * 2 + dev
            if (prev == -1) {
                prefs.edit().putInt(key, nowVal).apply()
                return
            }
            if (nowVal > 0 && prev == 0) {
                SecLog.add(ctx, "Developer options / USB debugging চালু হয়েছে")
                if (Config.fwdSecurity(ctx)) {
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "⚠️ Developer options চালু",
                        "এই ফোনে USB debugging / Developer options চালু করা হয়েছে।")
                }
            }
            prefs.edit().putInt(key, nowVal).apply()
        } catch (_: Exception) { }
    }

    /** One "charge full" note per charge cycle. */
    private fun checkChargeFull() {
        val ctx = applicationContext
        val pct = SystemReceiver.batteryPercent(ctx)
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        if (pct >= 100) {
            if (!prefs.getBoolean("full_alerted", false)) {
                prefs.edit().putBoolean("full_alerted", true).apply()
                if (Config.fwdBattery(ctx)) {
                    Relay.send(ctx, Relay.TYPE_BATTERY, "🔋 চার্জ পূর্ণ", "ব্যাটারি 100% হয়েছে।")
                }
            }
        } else if (pct in 0..94) {
            if (prefs.getBoolean("full_alerted", false)) {
                prefs.edit().putBoolean("full_alerted", false).apply()
            }
        }
    }

    /** Daily mobile-data usage alert (needs usage access). */
    private fun checkDataUsage() {
        val ctx = applicationContext
        val limit = Config.dataLimitMb(ctx)
        if (limit <= 0 || !DailySummary.usageAccess(ctx)) return
        try {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE)
                as android.app.usage.NetworkStatsManager
            val bucket = nsm.querySummaryForDevice(
                android.net.ConnectivityManager.TYPE_MOBILE, null,
                cal.timeInMillis, System.currentTimeMillis())
            val usedMb = (bucket.rxBytes + bucket.txBytes) / (1024 * 1024)
            if (usedMb < limit) return
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            if (prefs.getString("data_alert_day", "") == today) return
            prefs.edit().putString("data_alert_day", today).apply()
            Relay.send(ctx, Relay.TYPE_SYSTEM, "📶 ডেটা সীমা ছাড়িয়েছে",
                "আজ মোবাইল ডেটা ব্যবহার: $usedMb MB (সীমা $limit MB)")
        } catch (_: Exception) { }
    }

    /** One weekly roundup on Sunday evening. */
    private fun checkWeeklyReport() {
        val ctx = applicationContext
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.SUNDAY) return
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        if (mins < 21 * 60 + 30) return
        val weekId = java.text.SimpleDateFormat("yyyy-ww", java.util.Locale.US)
            .format(java.util.Date())
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        if (prefs.getString("last_weekly", "") == weekId) return
        prefs.edit().putString("last_weekly", weekId).apply()
        thread {
            val sb = StringBuilder()
            sb.append("মোট পাঠানো: ${Stats.sent(ctx)} • পাওয়া: ${Stats.recv(ctx)} • কমান্ড: ${Stats.cmds(ctx)}")
            BatteryHist.summary(ctx)?.let { sb.append("\nসঙ্গীর ব্যাটারি এই সময়ে: ").append(it) }
            sb.append("\n\n").append(DailySummary.build(ctx))
            Relay.send(ctx, Relay.TYPE_SYSTEM, "📅 সাপ্তাহিক রিপোর্ট", sb.toString())
        }
    }

    /** One last location before the battery dies (3-hour cooldown). */
    private fun checkLowBatteryLocation() {
        val ctx = applicationContext
        if (!Config.lowLocOn(ctx)) return
        val pct = SystemReceiver.batteryPercent(ctx)
        if (pct < 0 || pct > 5) return
        try {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL) return
        } catch (_: Exception) { }
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_lowloc", 0L) < 3 * 3600_000L) return
        prefs.edit().putLong("last_lowloc", now).apply()
        Relay.send(ctx, Relay.TYPE_SYSTEM, "🔋 ব্যাটারি প্রায় শেষ ($pct%)",
            "ফোন বন্ধ হওয়ার আগের শেষ লোকেশন পাঠানো হচ্ছে।")
        LocationHelper.fetchAndSend(ctx)
    }

    /** Morning routine: one automatic status at the chosen time. */
    private fun checkRoutine() {
        val ctx = applicationContext
        if (!Config.routineOn(ctx)) return
        val cal = java.util.Calendar.getInstance()
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (mins < Config.routineMinutes(ctx)) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        if (prefs.getString("last_routine_day", "") == today) return
        prefs.edit().putString("last_routine_day", today).apply()
        Relay.send(ctx, Relay.TYPE_SYSTEM, "🌅 সকালের স্ট্যাটাস",
            SystemReceiver.batteryStatusText(ctx))
        LocationHelper.fetchAndSend(ctx)
    }

    /** Per-app daily usage limits (set by long-press in the app picker). */
    private fun checkUsageLimits() {
        val ctx = applicationContext
        val pkgs = Config.usageLimitedPkgs(ctx)
        if (pkgs.isEmpty() || !DailySummary.usageAccess(ctx)) return
        try {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                cal.timeInMillis, System.currentTimeMillis())
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            for (s in stats) {
                val limit = Config.usageLimit(ctx, s.packageName)
                if (limit <= 0) continue
                val usedMin = s.totalTimeInForeground / 60000
                if (usedMin < limit) continue
                val key = "usage_alert_${s.packageName}"
                if (prefs.getString(key, "") == today) continue
                prefs.edit().putString(key, today).apply()
                Relay.send(ctx, Relay.TYPE_SYSTEM, "⏰ অ্যাপ ব্যবহারের সীমা পার",
                    "${Contacts.appLabel(ctx, s.packageName)}: আজ $usedMin মিনিট (সীমা $limit মিনিট)")
            }
        } catch (_: Exception) { }
    }

    /** Bundled digest-mode notifications go out as one message when due. */
    private fun flushDigestIfDue() {
        val ctx = applicationContext
        val mins = Config.digestMinutes(ctx)
        if (mins <= 0 || DigestStore.size(ctx) == 0) return
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_digest", 0L)
        if (System.currentTimeMillis() - last < mins * 60000L) return
        val entries = DigestStore.drain(ctx)
        if (entries.isEmpty()) return
        prefs.edit().putLong("last_digest", System.currentTimeMillis()).apply()
        // v2.4 smart digest: a one-line category summary on top.
        val bankWords = listOf("বিকাশ", "নগদ", "ব্যাংক", "bkash", "nagad", "bank", "টাকা", "otp", "কোড")
        val deliveryWords = listOf("ডেলিভারি", "কুরিয়ার", "delivery", "courier", "daraz", "parcel", "পাঠাও")
        val socialWords = listOf("facebook", "messenger", "whatsapp", "instagram", "telegram", "imo")
        var bank = 0; var delivery = 0; var social = 0
        for (e in entries) {
            val hay = (e.app + " " + e.title + " " + e.body).lowercase()
            when {
                bankWords.any { hay.contains(it) } -> bank++
                deliveryWords.any { hay.contains(it) } -> delivery++
                socialWords.any { hay.contains(it) } -> social++
            }
        }
        val other = entries.size - bank - delivery - social
        val summary = "সারসংক্ষেপ: " +
            listOfNotNull(
                if (bank > 0) "💰 $bank ব্যাংক/টাকা" else null,
                if (delivery > 0) "📦 $delivery ডেলিভারি" else null,
                if (social > 0) "💬 $social সামাজিক" else null,
                if (other > 0) "🔔 $other অন্যান্য" else null
            ).joinToString(" • ")
        val body = summary + "\n\n" + entries.take(25).joinToString("\n") {
            "• ${it.app}: ${it.title} — ${it.body}".take(160)
        }
        Relay.send(ctx, Relay.TYPE_SYSTEM, "🗞️ নোটিফিকেশন সারাংশ (${entries.size})", body)
    }

    /** Geofence transitions, checked against the last known fix. */
    private fun checkGeofence() {
        val ctx = applicationContext
        if (!Config.geoOn(ctx) || !Config.geoIsSet(ctx)) return
        LocationHelper.fetchCoords(ctx, { lat, lng ->
            try {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(
                    lat, lng, Config.geoLat(ctx), Config.geoLng(ctx), res)
                val inside = res[0] <= Config.geoRadius(ctx)
                val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                val prev = prefs.getInt("geo_inside", -1)
                prefs.edit().putInt("geo_inside", if (inside) 1 else 0).apply()
                if (prev == 1 && !inside) {
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "📍 জিওফেন্স: এলাকা ছেড়েছে",
                        "${Config.deviceName(ctx)} নির্ধারিত এলাকার বাইরে (প্রায় ${res[0].toInt()} মিটার দূরে)।")
                    Automation.onEvent(ctx, "geofence_out", JSONObject())
                } else if (prev == 0 && inside) {
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "📍 জিওফেন্স: এলাকায় ফিরেছে",
                        "${Config.deviceName(ctx)} আবার নির্ধারিত এলাকায়।")
                    Automation.onEvent(ctx, "geofence_in", JSONObject())
                }
            } catch (_: Exception) { }
        }, { })
    }

    /** Daily summary once a day after 21:30 local time. */
    private fun checkDailySummary() {
        val ctx = applicationContext
        if (!Config.dailySummaryOn(ctx)) return
        val cal = java.util.Calendar.getInstance()
        val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (mins < 21 * 60 + 30) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        if (prefs.getString("last_summary_day", "") == today) return
        prefs.edit().putString("last_summary_day", today).apply()
        Relay.send(ctx, Relay.TYPE_SYSTEM, "🗓️ দৈনিক সারাংশ", DailySummary.build(ctx))
    }

    companion object {
        /** The live service instance, used by the LAN transport. */
        @Volatile var instance: LinkService? = null

        /** v2.4 lost mode on/off (also used by automation actions). */
        fun setLostMode(ctx: Context, on: Boolean) {
            Config.setLostOn(ctx, on)
            start(ctx)
            Relay.send(ctx, Relay.TYPE_SYSTEM,
                if (on) "🚨 হারানো মোড চালু হয়েছে" else "✅ হারানো মোড বন্ধ হয়েছে",
                if (on) "লোকেশন, ছবি ও শব্দ পাঠানো শুরু হয়েছে।" else "")
        }

        const val CHANNEL_SERVICE = "phonelink_service"
        const val CHANNEL_INBOX = "phonelink_inbox"
        const val CHANNEL_URGENT = "phonelink_urgent"
        const val CHANNEL_OTP = "phonelink_otp"
        const val CHANNEL_LOW = "phonelink_quiet"
        const val ACTION_NEW_ITEM = "com.mthtamim.phonelink.NEW_ITEM"

        @Volatile var startedAt: Long = 0L

        fun start(ctx: Context) {
            val intent = Intent(ctx, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LinkService::class.java))
        }

        fun restart(ctx: Context) {
            stop(ctx)
            // small delay so the old threads exit before the new instance starts
            thread { try { Thread.sleep(600) } catch (_: Exception) {}; start(ctx) }
        }
    }
}
