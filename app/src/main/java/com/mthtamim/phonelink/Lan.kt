package com.mthtamim.phonelink

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * v2.4 LAN direct mode. When both phones share a Wi-Fi network they can
 * exchange the very same payloads without the public relay:
 *
 * - Discovery: UDP broadcast on port 37542 carrying our data topic; any
 *   group member answers with its name, and its IP is cached.
 * - Transport: TCP on port 37541, one JSON payload per line, prefixed
 *   with the target topic. Payloads keep their usual end-to-end
 *   encryption, so the LAN sees ciphertext only.
 * - The topic hash itself is the capability check: lines addressed to
 *   a foreign topic are dropped.
 */
object Lan {

    const val TCP_PORT = 37541
    const val UDP_PORT = 37542
    private const val PEER_TTL = 10 * 60_000L

    private fun state(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    fun peerIp(ctx: Context): String {
        val at = state(ctx).getLong("lan_peer_at", 0)
        if (System.currentTimeMillis() - at > PEER_TTL) return ""
        return state(ctx).getString("lan_peer_ip", "") ?: ""
    }

    private fun cachePeer(ctx: Context, ip: String) {
        state(ctx).edit().putString("lan_peer_ip", ip)
            .putLong("lan_peer_at", System.currentTimeMillis()).apply()
    }

    fun forgetPeer(ctx: Context) {
        state(ctx).edit().remove("lan_peer_ip").remove("lan_peer_at").apply()
    }

    /** Tries the LAN route; false means "fall back to the relay". */
    fun trySend(ctx: Context, topic: String, payload: JSONObject): Boolean {
        if (!Config.lanOn(ctx)) return false
        if (topic != Config.dataTopic(ctx) && topic != Config.cmdTopic(ctx)) return false
        val ip = peerIp(ctx)
        if (ip.isEmpty()) {
            discoverAsync(ctx)
            return false
        }
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, TCP_PORT), 1200)
                val line = topic + " " + payload.toString() + "\n"
                socket.getOutputStream().write(line.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        } catch (e: Exception) {
            forgetPeer(ctx)
            discoverAsync(ctx)
            false
        }
    }

    @Volatile private var discovering = false

    fun discoverAsync(ctx: Context) {
        if (discovering || !Config.lanOn(ctx)) return
        discovering = true
        val app = ctx.applicationContext
        Thread {
            try {
                val topic = Config.dataTopic(app)
                val msg = "PLDISC1 $topic"
                val socket = DatagramSocket()
                socket.broadcast = true
                val data = msg.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(data, data.size,
                    InetAddress.getByName("255.255.255.255"), UDP_PORT)
                socket.send(packet)
                socket.soTimeout = 2500
                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                if (text.startsWith("PLHERE1")) {
                    cachePeer(app, reply.address.hostAddress ?: "")
                }
                socket.close()
            } catch (e: Exception) { }
            discovering = false
        }.start()
    }

    // ---------------- server side ----------------

    @Volatile private var serverRunning = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startServer(ctx: Context) {
        if (serverRunning || !Config.lanOn(ctx)) return
        serverRunning = true
        val app = ctx.applicationContext
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("phonelink-lan").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { }
        Thread { discoveryLoop(app) }.start()
        Thread { tcpLoop(app) }.start()
    }

    fun stopServer() {
        serverRunning = false
        try { multicastLock?.release() } catch (e: Exception) { }
        multicastLock = null
    }

    private fun discoveryLoop(ctx: Context) {
        try {
            val socket = DatagramSocket(UDP_PORT)
            socket.broadcast = true
            val buf = ByteArray(512)
            while (serverRunning) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("PLDISC1 ")) continue
                val topic = text.substringAfter("PLDISC1 ").trim()
                if (topic != Config.dataTopic(ctx) && topic != Config.cmdTopic(ctx)) continue
                val replyText = "PLHERE1 " + Config.deviceName(ctx)
                // Answer on a fresh socket to the sender's discovery port.
                DatagramSocket().use { it.send(
                    DatagramPacket(replyText.toByteArray(Charsets.UTF_8),
                        replyText.length, packet.address, packet.port)) }
            }
            socket.close()
        } catch (e: Exception) { serverRunning = false }
    }

    private fun tcpLoop(ctx: Context) {
        try {
            val server = ServerSocket(TCP_PORT)
            while (serverRunning) {
                val client = server.accept()
                Thread { handleClient(ctx, client) }.start()
            }
            server.close()
        } catch (e: Exception) { serverRunning = false }
    }

    private fun handleClient(ctx: Context, client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            var line = reader.readLine()
            while (line != null) {
                val sp = line.indexOf(' ')
                if (sp > 0) {
                    val topic = line.substring(0, sp)
                    val payload = line.substring(sp + 1)
                    val service = LinkService.instance
                    // Wrap in the same envelope the relay stream delivers,
                    // so the one handler serves both transports.
                    val envelope = JSONObject()
                        .put("event", "message")
                        .put("time", System.currentTimeMillis() / 1000)
                        .put("message", payload).toString()
                    when (topic) {
                        Config.dataTopic(ctx) -> {
                            cachePeer(ctx, client.inetAddress.hostAddress ?: "")
                            service?.handleLine(envelope, cmd = false)
                        }
                        Config.cmdTopic(ctx) -> {
                            cachePeer(ctx, client.inetAddress.hostAddress ?: "")
                            service?.handleLine(envelope, cmd = true)
                        }
                    }
                }
                line = reader.readLine()
            }
        } catch (e: Exception) { }
        try { client.close() } catch (e: Exception) { }
    }
}
