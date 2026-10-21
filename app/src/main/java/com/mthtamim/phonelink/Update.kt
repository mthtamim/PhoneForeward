package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * v2.11: checks the GitHub releases of mthtamim/PhoneForeward for a
 * version newer than the installed one. Version strings are read from
 * release tags, names and asset names (the CI release keeps one tag,
 * so all three places are scanned). Manual checks report back through
 * the callback; the daily background check only notifies on a hit.
 */
object Update {

    private val verRx = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")

    private fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun cmp(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun check(ctx: Context, manual: Boolean,
              onResult: ((newer: String?, releaseUrl: String?) -> Unit)? = null) {
        thread {
            var best: String? = null
            var bestUrl: String? = null
            try {
                val conn = URL(
                    "https://api.github.com/repos/mthtamim/PhoneForeward/releases?per_page=10"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "PhoneLink")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val rel = arr.optJSONObject(i) ?: continue
                    val hay = rel.optString("tag_name") + " " + rel.optString("name") +
                        " " + (rel.optJSONArray("assets")?.let { a ->
                            (0 until a.length()).joinToString(" ") {
                                a.optJSONObject(it)?.optString("name") ?: ""
                            }
                        } ?: "")
                    for (m in verRx.findAll(hay)) {
                        val v = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
                        if (best == null || cmp(v, best!!) > 0) {
                            best = v
                            bestUrl = rel.optString("html_url")
                        }
                    }
                }
            } catch (_: Exception) { }
            Config.setUpdateLastCheck(ctx, System.currentTimeMillis())
            val newer = best?.takeIf { cmp(it, currentVersion(ctx)) > 0 }
            if (onResult != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(newer, bestUrl)
                }
            } else if (newer != null) {
                notifyNewer(ctx, newer)
            }
        }
    }

    private fun notifyNewer(ctx: Context, version: String) {
        try {
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, LinkService.CHANNEL_INBOX)
                .setContentTitle("⬆️ PhoneLink-এর নতুন সংস্করণ")
                .setContentText("v$version পাওয়া গেছে — অ্যাপ খুলে আপডেট বোতামে দেখো।")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(777, n)
        } catch (_: Exception) { }
    }
}
