package com.mthtamim.phonelink

import android.content.Context
import android.os.Build
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * v2.11: a tiny LAN web panel (port 37546), off by default. With the
 * user's own PIN it serves the inbox as JSON to browsers on the same
 * Wi-Fi and accepts "send a note" / "send a command" posts, so the
 * phone can be driven from a laptop on the home network. PIN-gated;
 * when no PIN is set the server refuses to start.
 *
 * v2.18 (WS1): this same server grows into the Web Companion — no
 * second server. A PIN login now issues a 7-day browser session
 * (random token in a cookie; the server-side store lives in the
 * phonelink_web prefs and sessions can be revoked from the app's
 * Settings → সংযোগ ও রিলে card). Pages: Inbox (unchanged), Status,
 * Files, Automation. The original ?pin= API behaviour is preserved
 * exactly: /inbox, /send and /cmd still accept ?pin= on every
 * request, and still answer 403 without it (a valid session cookie
 * is accepted as well).
 */
object WebInbox {

    const val PORT = 37546
    private const val SESSION_MS = 7L * 24 * 3600_000
    private const val MAX_BODY = 16 * 1024 * 1024
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        if (!Config.webInboxOn(ctx) || Config.webPin(ctx).length < 4) return
        running = true
        thread(name = "pl-web") {
            try {
                val ss = ServerSocket(PORT)
                server = ss
                while (running) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    thread { handle(ctx.applicationContext, sock) }
                }
            } catch (_: Exception) { }
            running = false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    fun restart(ctx: Context) { stop(); start(ctx) }

    // ---------------- v2.18 (WS1): browser sessions ----------------
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_web", Context.MODE_PRIVATE)

    private fun loadSessions(ctx: Context): JSONArray {
        val raw = prefs(ctx).getString("sessions", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (now - o.optLong("created") < SESSION_MS) kept.put(o)
        }
        if (kept.length() != arr.length())
            prefs(ctx).edit().putString("sessions", kept.toString()).apply()
        return kept
    }

    private fun saveSessions(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString("sessions", arr.toString()).apply()
    }

    private fun createSession(ctx: Context, ip: String, ua: String): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val arr = loadSessions(ctx)
        val now = System.currentTimeMillis()
        arr.put(JSONObject().put("token", token).put("ip", ip)
            .put("ua", ua.take(80)).put("created", now).put("last", now))
        saveSessions(ctx, arr)
        return token
    }

    /** Valid = exists and not expired. Touches last-seen (throttled). */
    private fun validSession(ctx: Context, token: String): Boolean {
        if (token.length < 16) return false
        val arr = loadSessions(ctx)
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("token") != token) continue
            if (now - o.optLong("last") > 60_000L) {
                o.put("last", now)
                saveSessions(ctx, arr)
            }
            return true
        }
        return false
    }

    /** Active sessions for the in-app list, newest first. */
    fun sessions(ctx: Context): List<JSONObject> {
        val arr = loadSessions(ctx)
        val out = mutableListOf<JSONObject>()
        for (i in arr.length() - 1 downTo 0)
            arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    fun revoke(ctx: Context, token: String) {
        val arr = loadSessions(ctx)
        val kept = JSONArray()
        var label = token.take(6)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("token") == token) {
                label = o.optString("ua").ifEmpty { o.optString("ip") }
                continue
            }
            kept.put(o)
        }
        saveSessions(ctx, kept)
        SecLog.add(ctx, "ওয়েব সেশন বাতিল: $label")
    }

    fun revokeAll(ctx: Context) {
        saveSessions(ctx, JSONArray())
        SecLog.add(ctx, "সব ওয়েব সেশন বাতিল করা হয়েছে")
    }

    /** The companion file folder: internal filesDir/companion — the
     *  app had no pre-existing shared folder, and internal storage
     *  needs no permission and is always writable. Web uploads land
     *  here and downloads are served from here. */
    fun companionDir(ctx: Context): File =
        File(ctx.filesDir, "companion").apply { mkdirs() }

    // ---------------- HTTP plumbing ----------------
    private fun esc(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun form(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val k = URLDecoder.decode(pair.substringBefore("="), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun handle(ctx: Context, sock: java.net.Socket) {
        try {
            sock.soTimeout = 20000
            val input = sock.getInputStream()
            // Read the header block byte-wise, then the exact body.
            val headBuf = java.io.ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                headBuf.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    else -> 0
                }
                if (state == 4 || headBuf.size() > 32768) break
            }
            val headText = headBuf.toString("ISO-8859-1")
            val headLines = headText.split("\r\n")
            val parts = (headLines.getOrNull(0) ?: "").split(" ")
            val method = parts.getOrNull(0) ?: ""
            val target = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            var contentType = ""
            var cookieHeader = ""
            var uaHeader = ""
            for (h in headLines.drop(1)) {
                val name = h.substringBefore(":").trim().lowercase()
                val value = h.substringAfter(":", "").trim()
                when (name) {
                    "content-length" ->
                        contentLength = value.toIntOrNull() ?: 0
                    "content-type" -> contentType = value
                    "cookie" -> cookieHeader = value
                    "user-agent" -> uaHeader = value
                }
            }
            if (contentLength > MAX_BODY) { sock.close(); return }
            val bodyBytes = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                if (read == contentLength) buf else buf.copyOf(read)
            } else ByteArray(0)
            val body = String(bodyBytes, Charsets.UTF_8)
            val clientIp = sock.inetAddress?.hostAddress ?: ""
            val localIp = sock.localAddress?.hostAddress ?: ""
            val uri = android.net.Uri.parse("http://x$target")
            val path = uri.path ?: "/"
            val pin = uri.getQueryParameter("pin") ?: ""
            val cookieToken = cookieHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("plsess=") }
                ?.substringAfter("=") ?: ""
            val out = sock.getOutputStream()
            fun reply(code: Int, type: String, payload: String,
                      extra: Map<String, String> = emptyMap()) {
                replyBytes(code, type, payload.toByteArray(Charsets.UTF_8),
                    extra)
            }
            fun replyBytes(code: Int, type: String, bytes: ByteArray,
                           extra: Map<String, String> = emptyMap()) {
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(code).append(" OK\r\n")
                sb.append("Content-Type: ").append(type)
                    .append("; charset=utf-8\r\n")
                sb.append("Content-Length: ").append(bytes.size)
                    .append("\r\n")
                for ((k, v) in extra) sb.append(k).append(": ")
                    .append(v).append("\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                out.write(bytes)
                out.flush()
            }
            fun redirect(to: String, extra: Map<String, String> = emptyMap()) {
                reply(302, "text/plain", "",
                    mapOf("Location" to to) + extra)
            }
            fun sessionCookie(token: String) =
                "plsess=$token; Path=/; HttpOnly; Max-Age=604800"
            val pinOk = pin.isNotEmpty() && pin == Config.webPin(ctx)
            val sessionOk = cookieToken.isNotEmpty() &&
                validSession(ctx, cookieToken)
            val authed = pinOk || sessionOk

            // ---- login (the only unauthenticated page) ----
            if (path == "/login" && method == "GET") {
                reply(200, "text/html", LOGIN_PAGE.replace("%ERR%", ""))
                sock.close(); return
            }
            if (path == "/login" && method == "POST") {
                val given = form(body)["pin"] ?: ""
                if (given.isNotEmpty() && given == Config.webPin(ctx)) {
                    val token = createSession(ctx, clientIp, uaHeader)
                    SecLog.add(ctx, "ওয়েব কম্প্যানিয়ন লগইন: $clientIp")
                    redirect("/", mapOf("Set-Cookie" to sessionCookie(token)))
                } else {
                    reply(200, "text/html", LOGIN_PAGE.replace("%ERR%",
                        "<p style='color:#e57373'>ভুল PIN — আবার চেষ্টা করো</p>"))
                }
                sock.close(); return
            }
            if (path == "/logout" && method == "POST") {
                if (cookieToken.isNotEmpty()) revoke(ctx, cookieToken)
                redirect("/login", mapOf("Set-Cookie" to
                    "plsess=; Path=/; HttpOnly; Max-Age=0"))
                sock.close(); return
            }

            // ---- everything below needs auth ----
            if (!authed) {
                val isPage = method == "GET" && (path == "/" ||
                    path == "/index" || path == "/status" ||
                    path == "/files" || path == "/auto")
                if (isPage) redirect("/login")
                else reply(403, "text/plain", "PIN দরকার বা ভুল PIN")
                sock.close(); return
            }
            // A valid ?pin= visit upgrades the browser to a session,
            // so old bookmark URLs keep working seamlessly.
            val upgradeCookie = if (pinOk && !sessionOk)
                mapOf("Set-Cookie" to sessionCookie(
                    createSession(ctx, clientIp, uaHeader)))
            else emptyMap()

            when {
                method == "GET" && (path == "/" || path == "/index") ->
                    reply(200, "text/html", PAGE, upgradeCookie)
                method == "GET" && path == "/inbox" -> {
                    val arr = JSONArray()
                    for (item in History.all(ctx).take(150)) {
                        arr.put(JSONObject()
                            .put("type", item.type).put("title", item.title)
                            .put("body", item.body).put("from", item.from)
                            .put("time", item.time))
                    }
                    reply(200, "application/json", arr.toString())
                }
                method == "POST" && path == "/send" -> {
                    val o = JSONObject(body)
                    Relay.send(ctx, Relay.TYPE_INFO,
                        o.optString("title", "ওয়েব প্যানেল"),
                        o.optString("body", ""))
                    reply(200, "text/plain", "ok")
                }
                method == "POST" && path == "/cmd" -> {
                    val o = JSONObject(body)
                    Relay.sendCommand(ctx, o.optString("command", "STATUS"))
                    reply(200, "text/plain", "ok")
                }
                method == "GET" && path == "/api/status" ->
                    reply(200, "application/json",
                        statusJson(ctx, localIp).toString())
                method == "GET" && path == "/status" ->
                    reply(200, "text/html", statusPage(ctx, localIp),
                        upgradeCookie)
                method == "GET" && path == "/files" ->
                    reply(200, "text/html", filesPage(ctx), upgradeCookie)
                method == "GET" && path == "/file" -> {
                    val name = File(uri.getQueryParameter("name") ?: "").name
                    val f = File(companionDir(ctx), name)
                    if (name.isEmpty() || !f.isFile) {
                        reply(404, "text/plain", "not found")
                    } else {
                        SecLog.add(ctx, "ওয়েব কম্প্যানিয়ন ফাইল " +
                            "ডাউনলোড: $name")
                        replyBytes(200, "application/octet-stream",
                            f.readBytes(), mapOf(
                                "Content-Disposition" to
                                    "attachment; filename=\"$name\""))
                    }
                }
                method == "POST" && path == "/upload" -> {
                    val saved = saveUpload(ctx, contentType, bodyBytes)
                    if (saved != null)
                        SecLog.add(ctx, "ওয়েব কম্প্যানিয়ন ফাইল " +
                            "আপলোড: $saved")
                    redirect("/files")
                }
                method == "GET" && path == "/auto" ->
                    reply(200, "text/html", autoPage(ctx), upgradeCookie)
                method == "POST" && path == "/auto_toggle" -> {
                    val f = form(body)
                    val id = f["id"] ?: ""
                    val on = f["on"] == "1"
                    val rule = Automation.rules(ctx)
                        .firstOrNull { it.optString("id") == id }
                    if (rule != null) {
                        Automation.setEnabled(ctx, id, on)
                        SecLog.add(ctx, "ওয়েব থেকে রুল " +
                            (if (on) "চালু" else "বন্ধ") + ": " +
                            rule.optString("name"))
                    }
                    redirect("/auto")
                }
                method == "POST" && path == "/auto_run" -> {
                    val id = form(body)["id"] ?: ""
                    val rule = Automation.rules(ctx)
                        .firstOrNull { it.optString("id") == id }
                    if (rule != null) {
                        Automation.runActions(ctx, rule, JSONObject()
                            .put("title", rule.optString("name"))
                            .put("body", "test"), 0)
                        SecLog.add(ctx, "ওয়েব থেকে রুল টেস্ট-রান: " +
                            rule.optString("name"))
                    }
                    redirect("/auto")
                }
                else -> reply(404, "text/plain", "not found")
            }
            sock.close()
        } catch (_: Exception) {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    /** Extracts the uploaded file from a multipart/form-data body.
     *  Works on the ISO-8859-1 view of the raw bytes so binary
     *  content survives intact. Returns the saved name, or null. */
    private fun saveUpload(ctx: Context, contentType: String,
                           bodyBytes: ByteArray): String? {
        val boundary = contentType.substringAfter("boundary=", "")
            .trim().trim('"')
        if (boundary.isEmpty() || bodyBytes.isEmpty()) return null
        val raw = String(bodyBytes, Charsets.ISO_8859_1)
        val marker = "--$boundary"
        var idx = raw.indexOf(marker)
        if (idx < 0) return null
        idx += marker.length
        val headEnd = raw.indexOf("\r\n\r\n", idx)
        if (headEnd < 0) return null
        val partHead = raw.substring(idx, headEnd)
        val nameMatch = Regex("filename=\"([^\"]*)\"").find(partHead)
            ?: return null
        val rawName = nameMatch.groupValues[1]
        if (rawName.isEmpty()) return null
        val safe = File(rawName).name
        if (safe.isEmpty()) return null
        val contentStart = headEnd + 4
        val endMark = "\r\n$marker"
        val contentEnd = raw.indexOf(endMark, contentStart)
        if (contentEnd < 0) return null
        val data = raw.substring(contentStart, contentEnd)
            .toByteArray(Charsets.ISO_8859_1)
        val target = File(companionDir(ctx), safe)
        target.writeBytes(data)
        return safe
    }

    // ---------------- v2.18 (WS1): companion pages ----------------
    private fun nav(active: String): String {
        fun tab(href: String, label: String) =
            if (href == active)
                "<b style='color:#0e9f6e'>$label</b>"
            else "<a href='$href' style='color:#8fb3a3'>$label</a>"
        return "<div style='margin:10px 0'>" +
            tab("/", "ইনবক্স") + " • " + tab("/status", "স্ট্যাটাস") +
            " • " + tab("/files", "ফাইল") + " • " + tab("/auto", "অটোমেশন") +
            " • <form method='post' action='/logout' style='display:inline'>" +
            "<button type='submit' style='background:none;border:none;" +
            "color:#8fb3a3;cursor:pointer;padding:0'>লগআউট</button></form></div>"
    }

    private val HEAD = "<!doctype html><html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>PhoneLink Companion</title><style>" +
        "body{font-family:sans-serif;background:#0d1512;color:#e6f2ec;margin:16px}" +
        ".card{background:#16211c;border-radius:12px;padding:10px 14px;margin:8px 0}" +
        "small{color:#8fb3a3}input,button{padding:8px;border-radius:8px;" +
        "border:1px solid #2c4}button{background:#0e9f6e;color:#fff;" +
        "border:none;cursor:pointer}a{color:#8fb3a3}table{border-collapse:collapse}" +
        "td,th{padding:6px 10px;text-align:left;border-bottom:1px solid #223}" +
        "</style></head><body>"

    private fun statusJson(ctx: Context, localIp: String): JSONObject {
        val stat = StatFs(ctx.filesDir.path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) { "?" }
        val rules = Automation.rules(ctx)
        return JSONObject()
            .put("device", Config.deviceName(ctx))
            .put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim())
            .put("android", Build.VERSION.RELEASE)
            .put("battery", Automation.batteryPct(ctx))
            .put("app_version", ver ?: "?")
            .put("ip", localIp)
            .put("port", PORT)
            .put("storage_free", free)
            .put("storage_total", total)
            .put("rules", rules.size)
            .put("rules_enabled", rules.count { it.optBoolean("enabled", true) })
            .put("mqtt", MqttManager.state().name)
    }

    private fun statusPage(ctx: Context, localIp: String): String {
        val j = statusJson(ctx, localIp)
        fun gb(b: Long): String =
            String.format("%.1f GB", b / 1_000_000_000.0)
        val rows = listOf(
            "ডিভাইস" to j.optString("device"),
            "মডেল" to (j.optString("model") + " • Android " +
                j.optString("android")),
            "ব্যাটারি" to (j.optInt("battery").toString() + "%"),
            "স্টোরেজ ফ্রি" to (gb(j.optLong("storage_free")) + " / " +
                gb(j.optLong("storage_total"))),
            "PhoneLink" to ("v" + j.optString("app_version")),
            "ঠিকানা" to (j.optString("ip") + ":" + j.optInt("port")),
            "রুল" to (j.optInt("rules_enabled").toString() + " চালু / " +
                j.optInt("rules").toString() + " মোট"),
            "MQTT" to j.optString("mqtt"))
        val sb = StringBuilder(HEAD)
        sb.append("<h2>PhoneLink স্ট্যাটাস</h2>").append(nav("/status"))
        sb.append("<div class='card'><table>")
        for ((k, v) in rows)
            sb.append("<tr><td><small>").append(esc(k)).append("</small></td><td>")
                .append(esc(v)).append("</td></tr>")
        sb.append("</table></div></body></html>")
        return sb.toString()
    }

    private fun filesPage(ctx: Context): String {
        val dir = companionDir(ctx)
        val files = dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val sb = StringBuilder(HEAD)
        sb.append("<h2>PhoneLink ফাইল</h2>").append(nav("/files"))
        sb.append("<div class='card'><form method='post' action='/upload' ")
            .append("enctype='multipart/form-data'>")
            .append("<input type='file' name='file'> ")
            .append("<button type='submit'>আপলোড</button></form>")
            .append("<br><small>ফোল্ডার: ").append(esc(dir.absolutePath))
            .append("</small></div>")
        if (files.isEmpty())
            sb.append("<div class='card'>কোনো ফাইল নেই</div>")
        for (f in files) {
            val link = "/file?name=" + URLEncoder.encode(f.name, "UTF-8")
            sb.append("<div class='card'><a href='").append(link)
                .append("'><b>").append(esc(f.name)).append("</b></a><br>")
                .append("<small>").append(f.length() / 1024)
                .append(" KB • ")
                .append(java.text.SimpleDateFormat("dd MMM HH:mm",
                    java.util.Locale.US).format(java.util.Date(f.lastModified())))
                .append("</small></div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun autoPage(ctx: Context): String {
        val rules = Automation.rules(ctx)
        val sb = StringBuilder(HEAD)
        sb.append("<h2>PhoneLink অটোমেশন</h2>").append(nav("/auto"))
        if (rules.isEmpty())
            sb.append("<div class='card'>কোনো রুল নেই</div>")
        for (r in rules) {
            val id = esc(r.optString("id"))
            val on = r.optBoolean("enabled", true)
            sb.append("<div class='card'><b>").append(esc(r.optString("name")))
                .append("</b> ")
                .append(if (on) "<small>চালু</small>" else "<small>বন্ধ</small>")
            val cat = r.optString("cat")
            if (cat.isNotEmpty())
                sb.append("<br><small>").append(esc(cat)).append("</small>")
            sb.append("<br><form method='post' action='/auto_toggle' ")
                .append("style='display:inline'>")
                .append("<input type='hidden' name='id' value='").append(id)
                .append("'><input type='hidden' name='on' value='")
                .append(if (on) "0" else "1").append("'>")
                .append("<button type='submit'>")
                .append(if (on) "বন্ধ করো" else "চালু করো")
                .append("</button></form> ")
                .append("<form method='post' action='/auto_run' ")
                .append("style='display:inline'>")
                .append("<input type='hidden' name='id' value='").append(id)
                .append("'><button type='submit'>টেস্ট রান</button></form>")
                .append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private val LOGIN_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhoneLink Companion — লগইন</title>
<style>body{font-family:sans-serif;background:#0d1512;color:#e6f2ec;margin:16px}
.card{background:#16211c;border-radius:12px;padding:14px;margin:8px 0;max-width:420px}
input,button{padding:10px;border-radius:8px;border:1px solid #2c4;font-size:16px}
button{background:#0e9f6e;color:#fff;border:none;cursor:pointer}</style></head>
<body><h2>PhoneLink Companion</h2>
<div class="card">%ERR%
<form method="post" action="/login">
<label>ওয়েব PIN</label><br><br>
<input type="password" name="pin" size="16" autocomplete="off">
<button type="submit">লগইন</button>
</form>
<p><small>লগইন ৭ দিন থাকে। অ্যাপের Settings → সংযোগ ও রিলে থেকে যেকোনো সেশন বাতিল করা যায়।</small></p>
</div></body></html>"""

    private val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhoneLink ওয়েব ইনবক্স</title>
<style>body{font-family:sans-serif;background:#0d1512;color:#e6f2ec;margin:16px}
.card{background:#16211c;border-radius:12px;padding:10px 14px;margin:8px 0}
small{color:#8fb3a3}input,button{padding:8px;border-radius:8px;border:1px solid #2c4}
button{background:#0e9f6e;color:#fff;border:none;cursor:pointer}
a{color:#8fb3a3}</style></head>
<body><h2>PhoneLink ইনবক্স</h2>
<div style="margin:10px 0"><b style="color:#0e9f6e">ইনবক্স</b> •
<a href="/status">স্ট্যাটাস</a> • <a href="/files">ফাইল</a> •
<a href="/auto">অটোমেশন</a></div>
<div><input id="t" placeholder="শিরোনাম" size="24">
<input id="b" placeholder="বার্তা" size="40">
<button onclick="send()">পাঠাও</button>
<button onclick="cmd('STATUS')">স্ট্যাটাস</button>
<button onclick="cmd('RING')">রিং</button>
<button onclick="cmd('LOCATION')">লোকেশন</button></div>
<div id="list"></div>
<script>
const pin=new URLSearchParams(location.search).get('pin')||'';
const q=pin?('?pin='+pin):'';
async function load(){const r=await fetch('/inbox'+q);const d=await r.json();
document.getElementById('list').innerHTML=d.map(x=>'<div class="card"><b>'+
x.title+'</b><br>'+x.body+'<br><small>'+x.type+' • '+x.from+' • '+
new Date(x.time).toLocaleString()+'</small></div>').join('');}
async function send(){await fetch('/send'+q,{method:'POST',
body:JSON.stringify({title:document.getElementById('t').value,
body:document.getElementById('b').value})});}
async function cmd(c){await fetch('/cmd'+q,{method:'POST',
body:JSON.stringify({command:c})});}
load();setInterval(load,8000);
</script></body></html>"""
}
