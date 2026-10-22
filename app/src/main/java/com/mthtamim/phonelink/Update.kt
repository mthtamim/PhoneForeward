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
            var bestApk: String? = null
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
                            val assets = rel.optJSONArray("assets")
                            if (assets != null) {
                                for (j in 0 until assets.length()) {
                                    val a = assets.optJSONObject(j) ?: continue
                                    if (a.optString("name").endsWith(".apk")) {
                                        bestApk = a.optString(
                                            "browser_download_url")
                                        break
                                    }
                                }
                            }
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
                // v2.16: actually UPDATE, don't just announce —
                // download the release APK and install it (silent
                // via Shizuku when available, else one-tap).
                if (Config.autoUpdate(ctx) && bestApk != null)
                    downloadAndInstall(ctx, bestApk!!, newer)
                else notifyNewer(ctx, newer)
            }
        }
    }

    /** v2.16: download the release APK and install it. Shizuku
     *  installs silently (pm install -r); without Shizuku the user
     *  gets a notification that opens the system installer once. */
    fun downloadAndInstall(ctx: Context, url: String, version: String) {
        thread {
            try {
                val dir = ctx.externalCacheDir ?: ctx.cacheDir
                val file = java.io.File(dir, "phonelink-update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PhoneLink")
                conn.inputStream.use { ins ->
                    file.outputStream().use { out -> ins.copyTo(out) } }
                if (file.length() < 1_000_000) {
                    notifyUpdate(ctx, "⬆️ আপডেট ডাউনলোড অসম্পূর্ণ",
                        "v$version-এর ফাইলটি ঠিকমতো আসেনি।", null)
                    return@thread
                }
                if (ShizukuHelper.granted()) {
                    ShizukuHelper.run("pm install -r '" +
                        file.absolutePath.replace("'", "") + "'")
                    if (ShizukuHelper.lastError == null) {
                        notifyUpdate(ctx, "⬆️ PhoneLink আপডেট হয়ে গেছে",
                            "v$version নিজে নিজে ইনস্টল হয়েছে।", null)
                        return@thread
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", file)
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri,
                        "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(ctx, 778, install,
                    PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT)
                notifyUpdate(ctx, "⬆️ নতুন সংস্করণ v$version তৈরি",
                    "ট্যাপ করলেই ইনস্টল হবে (APK ডাউনলোড হয়ে গেছে)।", pi)
            } catch (e: Exception) {
                notifyUpdate(ctx, "⬆️ আপডেট ডাউনলোড হয়নি",
                    e.message ?: e.javaClass.simpleName, null)
            }
        }
    }

    private fun notifyUpdate(ctx: Context, title: String, text: String,
                             pi: PendingIntent?) {
        try {
            val intent = Intent(ctx, MainActivity::class.java)
            val openPi = pi ?: PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, LinkService.CHANNEL_INBOX)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)
                .notify(777, n)
        } catch (_: Exception) { }
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
