package com.autotap.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whole-store backup (v5.2 reliability): every script change schedules an
 * automatic backup of ALL scripts + image templates (base64) to two places:
 *  1. a private file (guards against a corrupt preferences write), and
 *  2. MediaStore Downloads/AutoTap/autotap-backup.json, which SURVIVES an
 *     uninstall - reinstall cycles no longer wipe the user's scripts.
 * On a fresh start with an empty store, tryRestore() pulls it back.
 */
object Backup {

    private const val FILE_NAME = "autotap-backup.json"
    private const val KEY_LAST = "last_backup_at"
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun lastBackupAt(ctx: Context): Long =
        ctx.getSharedPreferences("autotap", Context.MODE_PRIVATE).getLong(KEY_LAST, 0)

    private fun setLastBackupAt(ctx: Context, at: Long) {
        ctx.getSharedPreferences("autotap", Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, at).apply()
    }

    /** Debounced auto-backup, called from Store.saveScripts. */
    fun schedule(ctx: Context) {
        val appCtx = ctx.applicationContext
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { writeAuto(appCtx) }
        pending = r
        handler.postDelayed(r, 2500)
    }

    fun buildBackupJson(ctx: Context): String {
        val scripts = Store.getScripts(ctx)
        val root = JSONObject()
        root.put("app", "AutoTap")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        val arr = JSONArray()
        scripts.forEach { arr.put(it.toJson()) }
        root.put("scripts", arr)
        val paths = linkedSetOf<String>()
        scripts.forEach { s -> s.steps.forEach { st -> if (st.imagePath.isNotEmpty()) paths.add(st.imagePath) } }
        val tarr = JSONArray()
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists()) {
                    tarr.put(JSONObject()
                        .put("path", path)
                        .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
                }
            } catch (e: Exception) { /* one bad template must not sink the backup */ }
        }
        root.put("templates", tarr)
        return root.toString()
    }

    fun writeAuto(ctx: Context) {
        try {
            val json = buildBackupJson(ctx)
            val dir = File(ctx.filesDir, "backup").apply { mkdirs() }
            File(dir, FILE_NAME).writeText(json)
            writeMediaStore(ctx, json)
            setLastBackupAt(ctx, System.currentTimeMillis())
        } catch (e: Exception) { /* backup is best-effort */ }
    }

    private fun mediaUri(ctx: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
            MediaStore.Downloads.RELATIVE_PATH + "=?"
        val args = arrayOf(FILE_NAME, "Download/AutoTap/")
        ctx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun writeMediaStore(ctx: Context, json: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val existing = mediaUri(ctx)
            if (existing != null) {
                ctx.contentResolver.openOutputStream(existing, "wt")?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AutoTap")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) { /* MediaStore may refuse; private copy still exists */ }
    }

    private fun readMediaStore(ctx: Context): String? {
        return try {
            val uri = mediaUri(ctx) ?: return null
            ctx.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) { null }
    }

    /** Restores only when the store is empty (fresh install). Returns count. */
    fun tryRestore(ctx: Context): Int {
        if (Store.getScripts(ctx).isNotEmpty()) return 0
        val json = readMediaStore(ctx) ?: try {
            val f = File(File(ctx.filesDir, "backup"), FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) { null } ?: return 0
        return importBackup(ctx, json)
    }

    /** Imports the backup format; returns how many scripts were restored. */
    fun importBackup(ctx: Context, json: String): Int {
        return try {
            val root = JSONObject(json)
            val tarr = root.optJSONArray("templates") ?: JSONArray()
            val pathMap = mutableMapOf<String, String>()
            val tdir = File(ctx.filesDir, "templates").apply { mkdirs() }
            for (i in 0 until tarr.length()) {
                val o = tarr.optJSONObject(i) ?: continue
                val orig = o.optString("path")
                val data = o.optString("data")
                if (orig.isEmpty() || data.isEmpty()) continue
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val out = File(tdir, "restored_" + System.currentTimeMillis() + "_" + i + ".img")
                out.writeBytes(bytes)
                pathMap[orig] = out.absolutePath
            }
            val sarr = root.optJSONArray("scripts") ?: JSONArray()
            val existing = Store.getScripts(ctx)
            var count = 0
            for (i in 0 until sarr.length()) {
                val o = sarr.optJSONObject(i) ?: continue
                // Remap template paths to the restored files.
                val stepsArr = o.optJSONArray("steps")
                if (stepsArr != null) {
                    for (j in 0 until stepsArr.length()) {
                        val so = stepsArr.optJSONObject(j) ?: continue
                        val ip = so.optString("imagePath", "")
                        val mapped = pathMap[ip]
                        if (mapped != null) so.put("imagePath", mapped)
                    }
                }
                val s = Script.fromJson(o)
                if (existing.any { it.id == s.id }) {
                    val merged = Script.fromJson(JSONObject(o.toString()).apply {
                        put("id", Script.newId())
                        put("name", s.name + " (ফিরিয়ে আনা)")
                    })
                    existing.add(merged)
                } else {
                    existing.add(s)
                }
                count++
            }
            if (count > 0) Store.saveScripts(ctx, existing)
            count
        } catch (e: Exception) {
            0
        }
    }
}
