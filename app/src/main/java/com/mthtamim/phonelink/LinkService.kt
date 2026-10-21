package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
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
        createChannels()
        registerEventSources()
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
        }
        if (type == Relay.TYPE_HEARTBEAT) {
            val battery = payload.optInt("battery", -1)
            if (battery >= 0) Config.setLastSenderBattery(ctx, battery)
            StatusWidgetProvider.refresh(ctx)
            return // silent: only updates "last seen" + widget
        }

        val item = History.Item(
            type = type,
            title = payload.optString("title", "PhoneLink"),
            body = payload.optString("body", ""),
            from = from,
            time = payload.optLong("time", System.currentTimeMillis()),
            id = payload.optString("id", ""),
            code = payload.optString("code", ""),
            extra = payload.optString("extra", "")
        )
        History.add(ctx, item)
        showInboxNotification(item)
        // Delivery receipt back to the sender (commands topic), when the
        // payload carries an id (v2). Old/plain messages simply skip it.
        if (item.id.isNotEmpty()) Relay.sendAck(ctx, item.id, item.title)
        sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
    }

    private var notifId = 100

    private fun showInboxNotification(item: History.Item) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val lc = localized(applicationContext)
        val builder = Notification.Builder(this, CHANNEL_INBOX)
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
            } catch (_: Exception) { }
            try { Thread.sleep(25 * 60 * 1000L) } catch (_: InterruptedException) { break }
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "phonelink_service"
        const val CHANNEL_INBOX = "phonelink_inbox"
        const val ACTION_NEW_ITEM = "com.mthtamim.phonelink.NEW_ITEM"

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
