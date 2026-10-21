package com.mthtamim.phonelink

import android.content.Context
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * v2.8: minimal SMTP sender for the automation "send_email" action.
 * Supports port 465 (implicit TLS) and 587 (STARTTLS), AUTH LOGIN.
 * Server and account come from the user SMTP settings in Config.
 */
object Smtp {
    fun send(ctx: Context, to: String, subject: String, body: String): Boolean {
        val host = Config.smtpHost(ctx)
        val port = Config.smtpPort(ctx)
        val user = Config.smtpUser(ctx)
        val pass = Config.smtpPass(ctx)
        val from = Config.smtpFrom(ctx).ifEmpty { user }
        if (host.isEmpty() || user.isEmpty() || to.isEmpty()) return false
        var socket: Socket? = null
        try {
            socket = if (port == 465) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(host, port) as Socket
            } else Socket(host, port)
            socket.soTimeout = 20000
            var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var writer = PrintWriter(socket.getOutputStream(), true)
            fun expect(vararg codes: String): Boolean {
                val line = reader.readLine() ?: return false
                return codes.any { line.startsWith(it) }
            }
            fun cmd(s: String) { writer.print("$s\r\n"); writer.flush() }
            if (!expect("220")) return false
            cmd("EHLO phonelink")
            // consume the (possibly multi-line) EHLO reply
            var line = ""
            do { line = reader.readLine() ?: return false } while (line.startsWith("250-"))
            if (port != 465) {
                cmd("STARTTLS")
                if (!expect("220")) return false
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                tls.startHandshake()
                socket = tls
                reader = BufferedReader(InputStreamReader(tls.getInputStream()))
                writer = PrintWriter(tls.getOutputStream(), true)
                cmd("EHLO phonelink")
                do { line = reader.readLine() ?: return false } while (line.startsWith("250-"))
            }
            cmd("AUTH LOGIN"); if (!expect("334")) return false
            cmd(Base64.encodeToString(user.toByteArray(), Base64.NO_WRAP))
            if (!expect("334")) return false
            cmd(Base64.encodeToString(pass.toByteArray(), Base64.NO_WRAP))
            if (!expect("235")) return false
            cmd("MAIL FROM:<$from>"); if (!expect("250")) return false
            cmd("RCPT TO:<$to>"); if (!expect("250", "251")) return false
            cmd("DATA"); if (!expect("354")) return false
            val encSubject = "=?UTF-8?B?" + Base64.encodeToString(
                subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
            writer.print("From: <$from>\r\nTo: <$to>\r\nSubject: $encSubject\r\n" +
                "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n")
            writer.print(Base64.encodeToString(
                body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
            writer.print("\r\n.\r\n"); writer.flush()
            if (!expect("250")) return false
            cmd("QUIT")
            return true
        } catch (_: Exception) {
            return false
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }
}
