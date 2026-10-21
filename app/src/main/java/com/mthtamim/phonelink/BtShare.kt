package com.mthtamim.phonelink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * v2.7: Bluetooth offline share. When two phones are near each other, the
 * receiver keeps an RFCOMM listener (toggle); the sender picks a bonded
 * device and pushes the clipboard text or a file (<= 20 MB) with no
 * internet at all. Received text goes through CopyActivity (foreground,
 * so the clipboard write is allowed); files land in the app's Downloads
 * folder and in the inbox.
 */
object BtShare {
    private val SERVICE_UUID: UUID =
        UUID.fromString("4d2f6b1e-7a3c-4f0e-9b2d-1a2b3c4d5e6f")
    private const val MAX_BYTES = 20 * 1024 * 1024

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false

    fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<BluetoothDevice> {
        return try {
            adapter()?.bondedDevices?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun startServer(ctx: Context) {
        if (running) return
        val ad = adapter() ?: return
        val app = ctx.applicationContext
        running = true
        thread {
            try {
                val ss = ad.listenUsingRfcommWithServiceRecord("PhoneLink", SERVICE_UUID)
                serverSocket = ss
                while (running) {
                    val socket = try { ss.accept() } catch (e: Exception) { break }
                    if (socket != null) handle(app, socket)
                }
            } catch (_: Exception) { }
            running = false
        }
    }

    fun stopServer() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
    }

    fun isRunning() = running

    private fun handle(ctx: Context, socket: BluetoothSocket) {
        thread {
            try {
                val peerName = try { socket.remoteDevice?.name }
                    catch (_: Exception) { null } ?: "Bluetooth"
                val input = DataInputStream(socket.inputStream)
                val header = JSONObject(input.readUTF())
                val kind = header.optString("kind")
                val name = header.optString("name", "file")
                val size = header.optInt("size", 0).coerceIn(0, MAX_BYTES)
                if (kind == "clip") {
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    ctx.startActivity(Intent(ctx, CopyActivity::class.java)
                        .putExtra("text", text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    History.add(ctx, History.Item(Relay.TYPE_CLIP,
                        "📋 ব্লুটুথ ক্লিপবোর্ড", text.take(300),
                        peerName, System.currentTimeMillis()))
                } else if (kind == "file") {
                    val dir = ctx.getExternalFilesDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, safe)
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(8192)
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, left))
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            left -= n
                        }
                    }
                    History.add(ctx, History.Item(Relay.TYPE_FILE,
                        "📩 ব্লুটুথ ফাইল — $safe",
                        "${out.absolutePath} • ${size / 1024} KB",
                        peerName, System.currentTimeMillis(), extra = out.absolutePath))
                }
            } catch (_: Exception) { }
            try { socket.close() } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendText(ctx: Context, device: BluetoothDevice, text: String,
                 done: (Boolean) -> Unit) {
        sendBytes(ctx, device, "clip", "", text.toByteArray(Charsets.UTF_8), done)
    }

    @SuppressLint("MissingPermission")
    fun sendFile(ctx: Context, device: BluetoothDevice, uri: Uri,
                 done: (Boolean) -> Unit) {
        thread {
            try {
                var name = "file"
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx) ?: name
                    }
                }
                val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes == null || bytes.size > MAX_BYTES) {
                    done(false); return@thread
                }
                sendBytes(ctx, device, "file", name, bytes, done)
            } catch (_: Exception) { done(false) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBytes(ctx: Context, device: BluetoothDevice, kind: String,
                          name: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        thread {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                adapter()?.cancelDiscovery()
                socket.connect()
                val out = DataOutputStream(socket.outputStream)
                out.writeUTF(JSONObject()
                    .put("kind", kind).put("name", name).put("size", bytes.size)
                    .toString())
                out.write(bytes)
                out.flush()
                done(true)
            } catch (_: Exception) {
                done(false)
            } finally {
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }
}
