package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends one event from the sender phone to the receiver phone through the
 * ntfy relay (default https://ntfy.sh, self-hostable). If the network is
 * down, the event is queued on disk and flushed later by LinkService.
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

    fun send(ctx: Context, type: String, title: String, body: String) {
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 1)
            put("type", type)
            put("title", title)
            put("body", body)
            put("from", Config.deviceName(appCtx))
            put("time", System.currentTimeMillis())
        }
        executor.execute {
            if (!post(appCtx, Config.dataTopic(appCtx), payload)) {
                enqueue(appCtx, payload)
            }
        }
    }

    fun sendCommand(ctx: Context, command: String) {
        val appCtx = ctx.applicationContext
        val payload = JSONObject().apply {
            put("v", 1)
            put("type", "COMMAND")
            put("command", command)
            put("from", Config.deviceName(appCtx))
            put("time", System.currentTimeMillis())
        }
        executor.execute { post(appCtx, Config.cmdTopic(appCtx), payload) }
    }

    /** Returns true when the relay accepted the message. */
    fun post(ctx: Context, topic: String, payload: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(Config.serverUrl(ctx) + "/" + topic)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            // ntfy shows Title as the notification heading on the receiver.
            val shortTitle = payload.optString("title").ifEmpty { "PhoneLink" }
            conn.setRequestProperty("Title", encodeHeader(shortTitle))
            conn.setRequestProperty("Tags", "iphone,mobile_phone")
            conn.setRequestProperty("Priority", "high")
            val wire = payload.toString()
            conn.outputStream.use { it.write(wire.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code in 200..299
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
            val ok = try { post(ctx, Config.dataTopic(ctx), JSONObject(line)) } catch (e: Exception) { false }
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
}
