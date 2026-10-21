package com.mthtamim.phonelink

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * v2.9: optional Shizuku bridge. When the user runs the Shizuku app and
 * grants PhoneLink permission, the shell_cmd action gains system-level
 * reach (secure settings, service control) without root.
 */
object ShizukuHelper {

    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) { false }

    fun granted(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun request() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(9201)
        } catch (_: Throwable) { }
    }

    /** Runs a shell command via Shizuku; returns trimmed stdout ("" on failure). */
    fun run(command: String): String {
        if (!granted()) return ""
        return try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.trim()
        } catch (_: Throwable) { "" }
    }
}
