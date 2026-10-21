package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sends one event from the sender phone to the receiver phone through the
 * ntfy relay (default https://ntfy.sh, self-hostable). If the network is
 * down, the event is queued on disk and flushed later by LinkService.
 *
 * v2: every payload carries an id; the receiver ACKs data messages so the
 * sender can show delivery ✓✓. Payloads are end-to-end encrypted with a
 * key derived from the pair code when encryption is on (the default).
 * A backup relay is used automatically when the primary is unreachable.
 */
object Relay {
    private val executor = Executors.newSingleThreadExecutor()

    const val TYPE_SMS = "SMS"
    const val TYPE_NOTIF = "NOTIF"
    const val TYPE_CALL = "CALL"
    const val TYPE_BATTERY = "BATTERY"
    const val TYPE_APP = "APP"
    const val TYPE_SYSTEM = "SYSTEM"
    const val TYPE_LOCATION = "LOCATION"
    const val TYPE_HEARTBEAT = "HEARTBEAT"
    const val TYPE_TEST = "TEST"
    const val TYPE_INFO = "INFO"
    const val TYPE_CLIP = "CLIP"
    const val TYPE_FILE = "FILE"
    const val TYPE_URGENT = "URGENT"

    fun newId(): String = System.currentTimeMillis().toString() + "-" +
        (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)] }.joinToString("")

    fun send(ctx: Context, type: String, title: String, body: String, extra: JSONObject? = null) {
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 2)
            put("id", newId())
            put("type", type)
            put("title", title)
            put("body", body)
            put("from", Config.deviceName(appCtx))
            put("dev", Config.deviceId(appCtx))
            put("time", System.currentTimeMillis())
            extra?.let { e -> e.keys().forEach { k -> put(k, e.get(k)) } }
        }
        if (type != TYPE_HEARTBEAT) Stats.incSent(appCtx, type)
        executor.execute {
            if (isPaused(appCtx) && type != TYPE_HEARTBEAT && type != TYPE_INFO) {
                enqueue(appCtx, payload)
                return@execute
            }
            if (post(appCtx, Config.dataTopic(appCtx), payload)) {
                if (type != TYPE_HEARTBEAT) addPendingAck(appCtx, payload)
            } else {
                enqueue(appCtx, payload)
            }
        }
    }

    fun sendCommand(ctx: Context, command: String, arg: String? = null, extra: JSONObject? = null) {
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 2)
            put("id", newId())
            put("type", "COMMAND")
            put("command", command)
            if (!arg.isNullOrEmpty()) put("arg", arg)
            extra?.let { e -> e.keys().forEach { k -> put(k, e.get(k)) } }
            put("from", Config.deviceName(appCtx))
            put("dev", Config.deviceId(appCtx))
            put("time", System.currentTimeMillis())
        }
        Stats.incCmd(appCtx)
        executor.execute { post(appCtx, Config.cmdTopic(appCtx), payload) }
    }

    /** Receiver → sender delivery receipt, carried on the command topic. */
    fun sendAck(ctx: Context, id: String, title: String) {
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 2)
            put("type", "ACK")
            put("ack", id)
            put("title", title)
            put("from", Config.deviceName(appCtx))
            put("dev", Config.deviceId(appCtx))
            put("time", System.currentTimeMillis())
        }
        executor.execute { post(appCtx, Config.cmdTopic(appCtx), payload) }
    }

    // ---------------- server selection / failover ----------------

    private fun state(ctx: Context) = ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    /** The relay currently in use (learned by failover; defaults to primary). */
    fun baseUrl(ctx: Context): String {
        val active = state(ctx).getString("active_server", "") ?: ""
        val primary = Config.serverUrl(ctx)
        val backup = Config.backupServerUrl(ctx)
        return if (active == backup && backup.isNotEmpty()) backup else primary
    }

    private fun markOk(ctx: Context, url: String) {
        state(ctx).edit()
            .putString("active_server", url)
            .putLong("last_relay_ok", System.currentTimeMillis())
            .apply()
    }

    fun lastRelayOk(ctx: Context): Long = state(ctx).getLong("last_relay_ok", 0L)

    /** Stream connections call this on a successful connect. */
    fun markRelayOk(ctx: Context) { markOk(ctx, baseUrl(ctx)) }

    /** Returns true when the relay accepted the message. */
    fun post(ctx: Context, topic: String, payload: JSONObject): Boolean {
        val primary = baseUrl(ctx)
        if (postTo(ctx, primary, topic, payload)) {
            markOk(ctx, primary)
            mirrorTelegram(ctx, topic, payload)
            return true
        }
        val backup = Config.backupServerUrl(ctx)
        val main = Config.serverUrl(ctx)
        val other = if (primary == main) backup else main
        if (other.isNotEmpty() && other != primary && postTo(ctx, other, topic, payload)) {
            markOk(ctx, other)
            mirrorTelegram(ctx, topic, payload)
            return true
        }
        return false
    }

    fun lastLatencyMs(ctx: Context): Long = state(ctx).getLong("last_latency_ms", 0L)

    // ---------------- privacy pause (remote or local) ----------------

    fun setPauseMinutes(ctx: Context, minutes: Int) {
        state(ctx).edit()
            .putLong("pause_until", System.currentTimeMillis() + minutes * 60_000L)
            .apply()
    }

    fun isPaused(ctx: Context): Boolean =
        state(ctx).getLong("pause_until", 0L) > System.currentTimeMillis()

    fun pauseLeftMin(ctx: Context): Long {
        val left = state(ctx).getLong("pause_until", 0L) - System.currentTimeMillis()
        return if (left > 0) left / 60_000L + 1 else 0L
    }

    /**
     * Optional permanent archive: mirrors every forwarded message to a
     * Telegram chat through the user's own bot (title + body as text).
     */
    private fun mirrorTelegram(ctx: Context, topic: String, payload: JSONObject) {
        try {
            if (topic != Config.dataTopic(ctx)) return
            val type = payload.optString("type")
            if (type == TYPE_HEARTBEAT || type.isEmpty()) return
            val token = Config.tgToken(ctx)
            val chat = Config.tgChat(ctx)
            if (token.isEmpty() || chat.isEmpty()) return
            val text = "📲 ${payload.optString("title")}\n${payload.optString("body")}"
                .trim().take(3800)
            if (text.isEmpty()) return
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bodyJson = JSONObject().put("chat_id", chat).put("text", text).toString()
            conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            conn.responseCode // fire and forget
            conn.disconnect()
        } catch (_: Exception) { }
    }

    private fun postTo(ctx: Context, base: String, topic: String, payload: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val started = System.currentTimeMillis()
            val url = URL(base + "/" + topic)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val encrypted = Config.encryptionOn(ctx)
            // ntfy shows Title as the notification heading on the receiver.
            // When encrypted, the header stays generic so nothing leaks.
            val shortTitle = if (encrypted) "PhoneLink"
                else payload.optString("title").ifEmpty { "PhoneLink" }
            conn.setRequestProperty("Title", encodeHeader(shortTitle))
            conn.setRequestProperty("Tags", "iphone,mobile_phone")
            conn.setRequestProperty("Priority", "high")
            val wire = if (encrypted) Crypto.encrypt(ctx, payload.toString()).toString()
                else payload.toString()
            conn.outputStream.use { it.write(wire.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                state(ctx).edit()
                    .putLong("last_latency_ms", System.currentTimeMillis() - started + 1)
                    .apply()
            }
            code in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Uploads a file as an ntfy attachment on the data topic. Subscribers
     * receive a message event whose attachment object carries the URL.
     * With wallpaper=true the receiver sets the image as its wallpaper.
     */
    fun postFile(ctx: Context, fileName: String, bytes: ByteArray, mime: String,
                 wallpaper: Boolean = false): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val base = baseUrl(ctx)
            val url = URL(base + "/" + Config.dataTopic(ctx))
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Filename", encodeHeader(fileName))
            conn.setRequestProperty("Title", if (wallpaper) "PhoneLink wallpaper" else "PhoneLink file")
            if (mime.isNotEmpty()) conn.setRequestProperty("Content-Type", mime)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code in 200..299) { markOk(ctx, base); true } else false
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun encodeHeader(s: String): String =
        // HTTP headers must be latin-1 safe; ntfy also accepts the JSON body,
        // and the receiver reads title/body from the JSON, not the header.
        s.map { if (it.code in 32..126) it else '?' }.joinToString("").take(120)

    // ---------------- delivery tracking ----------------

    private fun addPendingAck(ctx: Context, payload: JSONObject) {
        try {
            val arr = JSONArray(state(ctx).getString("pending_acks", "[]"))
            arr.put(JSONObject().apply {
                put("id", payload.getString("id"))
                put("title", payload.optString("title"))
                put("time", payload.optLong("time"))
            })
            while (arr.length() > 100) arr.remove(0)
            state(ctx).edit().putString("pending_acks", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** Called on the sender phone when the receiver's ACK arrives. */
    fun onAck(ctx: Context, id: String, title: String) {
        try {
            val arr = JSONArray(state(ctx).getString("pending_acks", "[]"))
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") != id) kept.put(o)
            }
            state(ctx).edit()
                .putString("pending_acks", kept.toString())
                .putLong("last_ack_time", System.currentTimeMillis())
                .putString("last_ack_title", title)
                .apply()
        } catch (_: Exception) { }
    }

    fun pendingAckCount(ctx: Context): Int =
        try { JSONArray(state(ctx).getString("pending_acks", "[]")).length() } catch (e: Exception) { 0 }

    // ---------------- seen receipts ----------------

    /** Receiver → sender: these inbox items have been looked at. */
    fun sendSeen(ctx: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 2)
            put("type", "SEEN")
            put("ids", ids.joinToString(","))
            put("from", Config.deviceName(appCtx))
            put("time", System.currentTimeMillis())
        }
        executor.execute { post(appCtx, Config.cmdTopic(appCtx), payload) }
    }

    fun onSeen(ctx: Context, count: Int) {
        state(ctx).edit()
            .putLong("last_seen_time", System.currentTimeMillis())
            .putInt("last_seen_count", count)
            .apply()
    }

    fun lastSeenTime(ctx: Context): Long = state(ctx).getLong("last_seen_time", 0L)
    fun lastSeenCount(ctx: Context): Int = state(ctx).getInt("last_seen_count", 0)

    fun lastAckTime(ctx: Context): Long = state(ctx).getLong("last_ack_time", 0L)
    fun lastAckTitle(ctx: Context): String = state(ctx).getString("last_ack_title", "") ?: ""

    // ---------------- offline queue ----------------
    private fun queueFile(ctx: Context): File = File(ctx.filesDir, "pending_queue.jsonl")

    @Synchronized
    private fun enqueue(ctx: Context, payload: JSONObject) {
        try {
            val f = queueFile(ctx)
            // keep the queue bounded — oldest events are dropped past 300
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            lines.add(payload.toString())
            while (lines.size > 300) lines.removeAt(0)
            f.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun flushQueue(ctx: Context) {
        val f = queueFile(ctx)
        if (!f.exists()) return
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) { f.delete(); return }
        val remaining = mutableListOf<String>()
        var sentAll = true
        for (line in lines) {
            if (!sentAll) { remaining.add(line); continue }
            val ok = try {
                val payload = JSONObject(line)
                val posted = post(ctx, Config.dataTopic(ctx), payload)
                if (posted && payload.optString("type") != TYPE_HEARTBEAT) addPendingAck(ctx, payload)
                posted
            } catch (e: Exception) { false }
            if (!ok) { sentAll = false; remaining.add(line) }
        }
        if (remaining.isEmpty()) f.delete()
        else f.writeText(remaining.joinToString("\n") + "\n")
    }

    fun queueSize(ctx: Context): Int {
        val f = queueFile(ctx)
        if (!f.exists()) return 0
        return f.readLines().count { it.isNotBlank() }
    }

    fun queueItems(ctx: Context): List<JSONObject> {
        val f = queueFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull {
            try { JSONObject(it) } catch (e: Exception) { null }
        }
    }

    @Synchronized
    fun clearQueue(ctx: Context) { try { queueFile(ctx).delete() } catch (_: Exception) { } }
}
