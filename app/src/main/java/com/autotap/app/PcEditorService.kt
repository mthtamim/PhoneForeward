package com.autotap.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny local-network script editor: while this service runs, a browser on
 * the same WiFi can open http://<phone-ip>:8765/?pin=XXXX and edit script
 * JSON on a big screen. Guarded by a 4-digit PIN shown in the app; serves
 * nothing outside the local network and stops with the app toggle.
 */
class PcEditorService : Service() {

    @Volatile private var running = false
    private var server: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            thread(isDaemon = true) { serve() }
        }
        return START_STICKY
    }

    private fun serve() {
        try {
            server = ServerSocket(PORT)
            while (running) {
                val socket = server?.accept() ?: break
                thread(isDaemon = true) { handle(socket) }
            }
        } catch (e: Exception) {
            // Port busy or closed on stop - nothing to do.
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: ""
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val target = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read)
            }
            val out: OutputStream = socket.getOutputStream()
            val pinOk = target.contains("pin=" + Store.pcPin(this))
            when {
                method == "GET" && target.startsWith("/api/scripts") && pinOk -> {
                    respond(out, 200, "application/json", Store.exportAll(this))
                }
                method == "POST" && target.startsWith("/api/import") && pinOk -> {
                    val n = Store.importJson(this, body)
                    respond(out, 200, "text/plain; charset=utf-8", "আমদানি হয়েছে: $n টি script")
                }
                method == "GET" && !target.startsWith("/api") -> {
                    respond(out, 200, "text/html; charset=utf-8", PAGE)
                }
                else -> respond(out, 403, "text/plain; charset=utf-8", "PIN ভুল বা পেজ নেই")
            }
        } catch (e: Exception) {
            // One bad request must not kill the server.
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
        serverRunning = false
        super.onDestroy()
    }

    companion object {
        const val PORT = 8765

        @Volatile var serverRunning = false

        fun start(ctx: Context) {
            serverRunning = true
            ctx.startService(Intent(ctx, PcEditorService::class.java))
        }

        fun stop(ctx: Context) {
            serverRunning = false
            ctx.stopService(Intent(ctx, PcEditorService::class.java))
        }

        /** This phone's LAN IPv4 address for the browser URL, or null. */
        fun localIp(): String? {
            return try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr.address.size == 4) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) { null }
        }
    }
}

private const val PAGE = """<!doctype html>
<html lang="bn"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AutoTap PC Editor</title>
<style>body{background:#101415;color:#D9EAE0;font-family:Roboto,sans-serif;margin:24px}
textarea{width:100%;height:55vh;background:#1C2326;color:#D9EAE0;border:1px solid #2A3336;border-radius:12px;padding:12px}
button{background:#10B981;color:#08110D;border:0;border-radius:10px;padding:10px 18px;font-weight:700;cursor:pointer;margin-right:8px}
</style></head><body>
<h2>AutoTap - PC Script Editor</h2>
<p>নিচের বাক্সে script JSON সম্পাদনা করে "আমদানি করো" চাপো। ফোনের অ্যাপে scriptগুলো আপডেট হয়ে যাবে।</p>
<textarea id="box">লোড হচ্ছে...</textarea><br><br>
<button onclick="load()">আবার লোড করো</button>
<button onclick="save()">আমদানি করো</button>
<p id="msg"></p>
<script>
const pin = new URLSearchParams(location.search).get('pin') || '';
async function load(){ const r = await fetch('/api/scripts?pin='+pin); document.getElementById('box').value = await r.text(); }
async function save(){ const r = await fetch('/api/import?pin='+pin, {method:'POST', body: document.getElementById('box').value}); document.getElementById('msg').textContent = await r.text(); }
load();
</script></body></html>"""
