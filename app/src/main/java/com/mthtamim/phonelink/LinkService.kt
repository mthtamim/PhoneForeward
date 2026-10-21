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
            thread(name = "pl-flush") {
                try { Relay.flushQueue(applicationContext) } catch (_: Exception) { }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        dataThread?.interrupt(); cmdThread?.interrupt(); heartbeatThread?.interrupt()
        unregisterEventSources()
        try {
            mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) { }
        mediaObserver = null
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
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun handleLine(line: String, cmd: Boolean) {
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
                val filePayload = JSONObject().apply {
                    put("type", Relay.TYPE_FILE)
                    put("title", "📁 $name")
                    put("body", sizeText)
                    put("extra", urlStr)
                    put("from", "")
                    put("time", eventTime * 1000)
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
        val type = payload.optString("type", "INFO")
        val from = payload.optString("from", "")
        if (from.isNotEmpty()) {
            Config.setLastSenderSeen(ctx, System.currentTimeMillis())
            Config.setLastSenderName(ctx, from)
            Peers.touch(ctx, from, payload.optInt("battery", -1))
        }
        if (type == Relay.TYPE_HEARTBEAT) {
            val battery = payload.optInt("battery", -1)
            if (battery >= 0) Config.setLastSenderBattery(ctx, battery)
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
        showInboxNotification(item, priority)
        // A ringing call gets a full-screen alert with quick SMS replies,
        // and (when enabled) the caller's name is spoken aloud.
        if (ringing && type == Relay.TYPE_CALL && extraStr.startsWith("tel:")) {
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

    private fun handleCommand(payload: JSONObject) {
        val ctx = applicationContext
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
        // Ignore commands this phone itself sent (same code on both roles).
        // The receiver phone is the one sending commands, so a phone that is
        // ONLY a sender still processes them; a both-role phone processes
        // commands only when they come from a different device name is not a
        // reliable test, so we process all — a command sent from this same
        // phone in receiver mode is a deliberate action anyway.
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
            "PING" -> Relay.send(ctx, Relay.TYPE_INFO, "ফোন অনলাইন আছে",
                "${Config.deviceName(ctx)} — ${SystemReceiver.batteryStatusText(ctx)}")
        }
    }

    private fun heartbeatLoop() {
        // First heartbeat shortly after start so the receiver sees us online.
        try { Thread.sleep(8000) } catch (_: InterruptedException) { return }
        while (running) {
            try {
                val extra = JSONObject().put("battery", SystemReceiver.batteryPercent(applicationContext))
                Relay.send(applicationContext, Relay.TYPE_HEARTBEAT, "heartbeat", "", extra)
                Relay.flushQueue(applicationContext)
                checkSimChanged()
                flushDigestIfDue()
                checkGeofence()
                checkDailySummary()
                checkLowBatteryLocation()
                checkRoutine()
                checkUsageLimits()
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
        val body = entries.take(25).joinToString("\n") {
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
                } else if (prev == 0 && inside) {
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "📍 জিওফেন্স: এলাকায় ফিরেছে",
                        "${Config.deviceName(ctx)} আবার নির্ধারিত এলাকায়।")
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
