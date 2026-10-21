package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * v2.8: tiny LAN webhook server. A GET to http://<phone-ip>:37544/hook/<name>
 * from the same Wi-Fi fires the automation event "webhook" with title <name>.
 * (The internet-side equivalent already exists: peer commands over the relay.)
 */
object WebhookServer {
    private const val PORT = 37544
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        running = true
        thread {
            try {
                val ss = ServerSocket(PORT)
                server = ss
                while (running) {
                    val sock = try { ss.accept() } catch (e: Exception) { break }
                    thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                            val line = reader.readLine() ?: ""
                            val parts = line.split(" ")
                            if (parts.size >= 2 && parts[0] == "GET" &&
                                parts[1].startsWith("/hook/")) {
                                var name = parts[1].removePrefix("/hook/")
                                val q = name.indexOf('?')
                                if (q >= 0) name = name.substring(0, q)
                                name = try { URLDecoder.decode(name, "UTF-8") }
                                    catch (e: Exception) { name }
                                Automation.onEvent(app, "webhook",
                                    JSONObject().put("title", name).put("body", name))
                            }
                            val out = sock.getOutputStream()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: 2\r\n\r\nok").toByteArray())
                            out.flush()
                        } catch (_: Exception) { }
                        try { sock.close() } catch (_: Exception) { }
                    }
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
}
