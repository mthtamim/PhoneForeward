package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
        super.onDestroy()
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
                val url = URL(Config.serverUrl(applicationContext) + "/" + topic + "/json")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 90000 // ntfy sends keepalives; a dead link times out here
                conn.setRequestProperty("Accept", "application/x-ndjson")
                if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
                backoff = 3000L
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
            if (raw.isBlank()) return
            val payload = try { JSONObject(raw) } catch (e: Exception) {
                // A plain-text message posted to the topic manually.
                JSONObject().apply {
                    put("type", "INFO"); put("title", outer.optString("title", "PhoneLink"))
                    put("body", raw); put("from", ""); put("time", eventTime * 1000)
                }
            }
            if (cmd) handleCommand(payload) else handleData(payload)
        } catch (_: Exception) { }
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
        if (type == Relay.TYPE_HEARTBEAT) return // silent: only updates "last seen"

        val item = History.Item(
            type = type,
            title = payload.optString("title", "PhoneLink"),
            body = payload.optString("body", ""),
            from = from,
            time = payload.optLong("time", System.currentTimeMillis())
        )
        History.add(ctx, item)
        showInboxNotification(item)
        sendBroadcast(Intent(ACTION_NEW_ITEM).setPackage(packageName))
    }

    private var notifId = 100

    private fun showInboxNotification(item: History.Item) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(this, CHANNEL_INBOX)
            .setContentTitle(item.title)
            .setContentText(item.body.take(120))
            .setStyle(Notification.BigTextStyle().bigText(item.body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        synchronized(this) { notifId += 1; nm.notify(notifId, notif) }
    }

    // ---------------- sender side: remote commands ----------------

    private fun handleCommand(payload: JSONObject) {
        val ctx = applicationContext
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
            "STOP_RING" -> { RingHelper.stop(); Relay.send(ctx, Relay.TYPE_INFO, "রিং বন্ধ", "রিং বন্ধ করা হয়েছে।") }
            "PING" -> Relay.send(ctx, Relay.TYPE_INFO, "ফোন অনলাইন আছে",
                "${Config.deviceName(ctx)} — ${SystemReceiver.batteryStatusText(ctx)}")
        }
    }

    private fun heartbeatLoop() {
        // First heartbeat shortly after start so the receiver sees us online.
        try { Thread.sleep(8000) } catch (_: InterruptedException) { return }
        while (running) {
            try {
                Relay.send(applicationContext, Relay.TYPE_HEARTBEAT, "heartbeat", "")
                Relay.flushQueue(applicationContext)
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
