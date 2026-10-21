package com.mthtamim.phonelink

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

/**
 * v2.9: a tiny plain-FTP client for the ftp_upload / ftp_download
 * automation actions. Param: host|user|pass|remotePath|localPath
 * (port 21, passive mode). Returns false on any failure.
 */
object Ftp {

    fun transfer(ctx: Context, param: String, upload: Boolean): Boolean {
        val parts = param.split("|")
        if (parts.size < 5) return false
        val host = parts[0].trim()
        val user = parts[1].trim().ifEmpty { "anonymous" }
        val pass = parts[2]
        val remote = parts[3].trim()
        val local = parts[4].trim()
        if (host.isEmpty() || remote.isEmpty() || local.isEmpty()) return false
        var ctrl: Socket? = null
        return try {
            ctrl = Socket(host, 21)
            ctrl.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(ctrl.getInputStream()))
            val out = ctrl.getOutputStream()
            fun reply(): String {
                var line = reader.readLine() ?: ""
                while (line.length >= 4 && line[3] == '-')
                    line = reader.readLine() ?: ""
                return line
            }
            fun cmd(s: String): String {
                out.write((s + "\r\n").toByteArray())
                out.flush()
                return reply()
            }
            reply() // greeting
            cmd("USER $user")
            cmd("PASS $pass")
            cmd("TYPE I")
            val pasv = cmd("PASV")
            val nums = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)")
                .find(pasv)?.groupValues ?: return false
            val dHost = "${nums[1]}.${nums[2]}.${nums[3]}.${nums[4]}"
            val dPort = nums[5].toInt() * 256 + nums[6].toInt()
            val data = Socket(dHost, dPort)
            data.soTimeout = 20000
            if (upload) {
                val f = File(local)
                if (!f.exists()) { data.close(); cmd("QUIT"); return false }
                cmd("STOR $remote")
                data.getOutputStream().use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
            } else {
                cmd("RETR $remote")
                val f = File(local)
                f.parentFile?.mkdirs()
                data.getInputStream().use { ins ->
                    f.outputStream().use { ins.copyTo(it) }
                }
            }
            data.close()
            cmd("QUIT")
            true
        } catch (e: Exception) {
            false
        } finally {
            try { ctrl?.close() } catch (_: Exception) { }
        }
    }
}
