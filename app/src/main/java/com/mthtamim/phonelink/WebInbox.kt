package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * v2.11: a tiny LAN web panel (port 37546), off by default. With the
 * user's own PIN it serves the inbox as JSON to browsers on the same
 * Wi-Fi and accepts "send a note" / "send a command" posts, so the
 * phone can be driven from a laptop on the home network. PIN-gated;
 * when no PIN is set the server refuses to start.
 */
object WebInbox {

    const val PORT = 37546
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

    private fun handle(ctx: Context, sock: java.net.Socket) {
        try {
            sock.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val line = reader.readLine() ?: run { sock.close(); return }
            val parts = line.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val target = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", true))
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""
            val uri = android.net.Uri.parse("http://x$target")
            val pin = uri.getQueryParameter("pin") ?: ""
            val out = sock.getOutputStream()
            fun reply(code: Int, type: String, payload: String) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $code OK\r\nContent-Type: $type; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                out.write(bytes)
                out.flush()
            }
            if (pin != Config.webPin(ctx)) {
                reply(403, "text/plain", "PIN দরকার বা ভুল PIN")
                sock.close(); return
            }
            when {
                method == "GET" && (uri.path == "/" || uri.path == "/index") ->
                    reply(200, "text/html", PAGE)
                method == "GET" && uri.path == "/inbox" -> {
                    val arr = JSONArray()
                    for (item in History.all(ctx).take(150)) {
                        arr.put(JSONObject()
                            .put("type", item.type).put("title", item.title)
                            .put("body", item.body).put("from", item.from)
                            .put("time", item.time))
                    }
                    reply(200, "application/json", arr.toString())
                }
                method == "POST" && uri.path == "/send" -> {
                    val o = JSONObject(body)
                    Relay.send(ctx, Relay.TYPE_INFO,
                        o.optString("title", "ওয়েব প্যানেল"),
                        o.optString("body", ""))
                    reply(200, "text/plain", "ok")
                }
                method == "POST" && uri.path == "/cmd" -> {
                    val o = JSONObject(body)
                    Relay.sendCommand(ctx, o.optString("command", "STATUS"))
                    reply(200, "text/plain", "ok")
                }
                else -> reply(404, "text/plain", "not found")
            }
            sock.close()
        } catch (_: Exception) {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhoneLink ওয়েব ইনবক্স</title>
<style>body{font-family:sans-serif;background:#0d1512;color:#e6f2ec;margin:16px}
.card{background:#16211c;border-radius:12px;padding:10px 14px;margin:8px 0}
small{color:#8fb3a3}input,button{padding:8px;border-radius:8px;border:1px solid #2c4}
button{background:#0e9f6e;color:#fff;border:none;cursor:pointer}</style></head>
<body><h2>📥 PhoneLink ইনবক্স</h2>
<div><input id="t" placeholder="শিরোনাম" size="24">
<input id="b" placeholder="বার্তা" size="40">
<button onclick="send()">পাঠাও</button>
<button onclick="cmd('STATUS')">স্ট্যাটাস</button>
<button onclick="cmd('RING')">রিং</button>
<button onclick="cmd('LOCATION')">লোকেশন</button></div>
<div id="list"></div>
<script>
const pin=new URLSearchParams(location.search).get('pin')||'';
async function load(){const r=await fetch('/inbox?pin='+pin);const d=await r.json();
document.getElementById('list').innerHTML=d.map(x=>'<div class="card"><b>'+
x.title+'</b><br>'+x.body+'<br><small>'+x.type+' • '+x.from+' • '+
new Date(x.time).toLocaleString()+'</small></div>').join('');}
async function send(){await fetch('/send?pin='+pin,{method:'POST',
body:JSON.stringify({title:document.getElementById('t').value,
body:document.getElementById('b').value})});}
async function cmd(c){await fetch('/cmd?pin='+pin,{method:'POST',
body:JSON.stringify({command:c})});}
load();setInterval(load,8000);
</script></body></html>"""
}
