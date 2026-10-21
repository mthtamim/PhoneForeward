package com.mthtamim.phonelink

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * v2.4 chunked large-file transfer. Files above the relay's attachment
 * limit are split into ~3.5 MB parts; each part travels as a normal
 * attachment named "plchunk-<id>-<idx>of<total>--<urlencoded name>".
 * The receiver stores parts under filesDir/chunks/<id>/, joins them
 * when complete and saves the result to Downloads. Missing parts are
 * requested with a CHUNK_NEED command (resume), and the sender keeps
 * its parts until CHUNK_DONE arrives or 24 h pass.
 */
object Chunks {

    private const val CHUNK = 3_500_000
    const val PREFIX = "plchunk-"

    private fun state(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    // ---------------- sender ----------------

    /** Splits and sends; returns false when the file is too large entirely. */
    fun sendLarge(ctx: Context, name: String, bytes: ByteArray): Boolean {
        if (bytes.size > 60 * 1024 * 1024) return false
        val id = Relay.newId().replace("-", "")
        val total = (bytes.size + CHUNK - 1) / CHUNK
        val dir = File(ctx.filesDir, "chunk_out/$id").apply { mkdirs() }
        Thread {
            try {
                for (i in 0 until total) {
                    val from = i * CHUNK
                    val to = minOf(from + CHUNK, bytes.size)
                    val part = bytes.copyOfRange(from, to)
                    File(dir, "$i").writeBytes(part)
                    val chunkName = PREFIX + id + "-" + i + "of" + total + "--" +
                        URLEncoder.encode(name, "UTF-8")
                    Relay.postFile(ctx, chunkName, part, "application/octet-stream")
                    Thread.sleep(400)
                }
                val sent = try { JSONArray(state(ctx).getString("chunks_sent", "[]")) }
                    catch (e: Exception) { JSONArray() }
                sent.put(JSONObject().put("id", id).put("at", System.currentTimeMillis()))
                state(ctx).edit().putString("chunks_sent", sent.toString()).apply()
                Relay.send(ctx, Relay.TYPE_INFO, "📦 বড় ফাইল পাঠানো হয়েছে",
                    "$name (${bytes.size / 1024} KB, $total অংশে)")
            } catch (e: Exception) { }
        }.start()
        return true
    }

    /** CHUNK_NEED handler: resend the parts the receiver still misses. */
    fun resend(ctx: Context, id: String, missing: JSONArray) {
        val dir = File(ctx.filesDir, "chunk_out/$id")
        if (!dir.exists()) return
        Thread {
            try {
                // The original name is recoverable from any sent registry? We
                // stored parts only; the name rides in each part's filename,
                // which the receiver echoes — resend uses a generic name and
                // the receiver matches parts by id+index, so it is safe.
                for (j in 0 until missing.length()) {
                    val idx = missing.optInt(j, -1)
                    if (idx < 0) continue
                    val f = File(dir, "$idx")
                    if (!f.exists()) continue
                    val total = dir.listFiles()?.size ?: 0
                    val chunkName = PREFIX + id + "-" + idx + "of" + total + "--" +
                        URLEncoder.encode("resend", "UTF-8")
                    Relay.postFile(ctx, chunkName, f.readBytes(), "application/octet-stream")
                    Thread.sleep(400)
                }
            } catch (e: Exception) { }
        }.start()
    }

    fun forgetSent(ctx: Context, id: String) {
        try { File(ctx.filesDir, "chunk_out/$id").deleteRecursively() } catch (e: Exception) { }
    }

    fun cleanupSent(ctx: Context) {
        val raw = state(ctx).getString("chunks_sent", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { return }
        val keep = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (now - o.optLong("at", 0) > 24 * 3600_000L) {
                forgetSent(ctx, o.optString("id"))
            } else keep.put(o)
        }
        state(ctx).edit().putString("chunks_sent", keep.toString()).apply()
    }

    // ---------------- receiver ----------------

    data class ChunkRef(val id: String, val idx: Int, val total: Int, val name: String)

    fun parse(name: String): ChunkRef? {
        if (!name.startsWith(PREFIX)) return null
        return try {
            val rest = name.removePrefix(PREFIX)
            val id = rest.substringBefore("-")
            val idxTotal = rest.substringAfter("-").substringBefore("--")
            val idx = idxTotal.substringBefore("of").toInt()
            val total = idxTotal.substringAfter("of").toInt()
            val orig = URLDecoder.decode(rest.substringAfter("--", "file"), "UTF-8")
            ChunkRef(id, idx, total, orig)
        } catch (e: Exception) { null }
    }

    /** Downloads one arrived part and joins the file when complete. */
    fun onChunkArrived(ctx: Context, ref: ChunkRef, url: String) {
        Thread {
            try {
                val dir = File(ctx.filesDir, "chunks/${ref.id}").apply { mkdirs() }
                val partFile = File(dir, "${ref.idx}")
                if (!partFile.exists()) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                    conn.inputStream.use { input ->
                        partFile.outputStream().use { input.copyTo(it) }
                    }
                    conn.disconnect()
                }
                val metaFile = File(dir, "meta.json")
                val meta = if (metaFile.exists()) {
                    try { JSONObject(metaFile.readText()) } catch (e: Exception) { JSONObject() }
                } else JSONObject()
                if (ref.name != "resend" || !meta.has("name")) meta.put("name", ref.name)
                meta.put("total", ref.total)
                meta.put("at", System.currentTimeMillis())
                metaFile.writeText(meta.toString())
                val have = (0 until ref.total).count { File(dir, "$it").exists() }
                Relay.send(ctx, Relay.TYPE_INFO, "📦 অংশ এসেছে ($have/${ref.total})",
                    meta.optString("name"))
                if (have == ref.total) {
                    val outName = meta.optString("name").ifEmpty { "file" }
                    val out = File(dir, "joined")
                    out.outputStream().use { os ->
                        for (i in 0 until ref.total) {
                            File(dir, "$i").inputStream().use { it.copyTo(os) }
                        }
                    }
                    val joinedBytes = out.readBytes()
                    saveToDownloads(ctx, outName, joinedBytes)
                    ocrIfImage(ctx, outName, joinedBytes)
                    Relay.sendCommand(ctx, "CHUNK_DONE", ref.id)
                    History.add(ctx, History.Item(
                        type = Relay.TYPE_INFO, title = "📦 বড় ফাইল এসেছে",
                        body = "$outName — Downloads-এ সেভ হয়েছে",
                        from = "", time = System.currentTimeMillis()))
                    dir.deleteRecursively()
                }
            } catch (e: Exception) { }
        }.start()
    }

    /** Asks the sender for parts that never arrived (resume). */
    fun requestMissing(ctx: Context) {
        val root = File(ctx.filesDir, "chunks")
        if (!root.exists()) return
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { dir ->
            try {
                val metaFile = File(dir, "meta.json")
                if (!metaFile.exists()) return@forEach
                val meta = JSONObject(metaFile.readText())
                if (now - meta.optLong("at", 0) < 5 * 60_000L) return@forEach
                val total = meta.optInt("total", 0)
                if (total == 0) return@forEach
                val missing = JSONArray()
                for (i in 0 until total) if (!File(dir, "$i").exists()) missing.put(i)
                if (missing.length() == 0) return@forEach
                val extra = JSONObject().put("missing", missing)
                Relay.sendCommand(ctx, "CHUNK_NEED", dir.name, extra)
                meta.put("at", now)
                metaFile.writeText(meta.toString())
            } catch (e: Exception) { }
        }
    }

    /** v2.11: OCR for images that arrive through the chunk channel. */
    private fun ocrIfImage(ctx: Context, name: String, bytes: ByteArray) {
        val lower = name.lowercase()
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") &&
            !lower.endsWith(".png") && !lower.endsWith(".webp")) return
        try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return
            Ocr.scanBitmap(ctx, bmp) { text ->
                History.add(ctx, History.Item(
                    Relay.TYPE_INFO, "🔤 ছবির লেখা — $name", text,
                    Config.deviceName(ctx), System.currentTimeMillis()))
            }
        } catch (_: Exception) { }
    }

    private fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).writeBytes(bytes)
            }
        } catch (e: Exception) { }
    }
}
