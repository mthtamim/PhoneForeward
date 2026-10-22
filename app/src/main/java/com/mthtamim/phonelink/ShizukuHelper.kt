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

    /** Why the last run() failed (v2.14) — surfaced to the user
     *  instead of failing silently. Null when the last run worked. */
    @Volatile var lastError: String? = null
        private set

    /** Runs a shell command via Shizuku; returns trimmed stdout ("" on failure). */
    fun run(command: String): String {
        lastError = null
        if (!granted()) {
            lastError = "not granted"
            return ""
        }
        return try {
            val proc = newProcess(arrayOf("sh", "-c", command))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.trim()
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)
                ?.targetException ?: t
            lastError = cause.javaClass.simpleName + ": " +
                (cause.message ?: "").take(120)
            ""
        }
    }

    /** A harmless real command, proving the whole shell path works. */
    fun selfTest(): Boolean = run("echo phonelink-ok") == "phonelink-ok"

    /** v2.16: dumpsys battery, trimmed to the useful lines — a
     *  supplement to BatteryInfo (which needs no root at all). */
    fun batteryDump(): String {
        if (!granted()) return ""
        val out = run("dumpsys battery")
        if (out.isEmpty() || lastError != null) return ""
        val keep = listOf("level:", "scale:", "health:", "temperature:",
            "voltage:", "status:", "present:", "AC powered:",
            "USB powered:", "Wireless powered:", "Charge counter:",
            "current now:")
        val lines = out.lines().map { it.trim() }
            .filter { l -> keep.any { l.startsWith(it) } }
        return if (lines.isEmpty()) ""
            else "Shizuku dumpsys battery:\n" + lines.joinToString("\n")
    }

    /** Shizuku.newProcess is private in API 13.1.5 (verified in the
     *  AAR: private static, (String[], String[], String)), which the
     *  Kotlin compiler refuses to call directly; reach it reflectively. */
    private fun newProcess(cmd: Array<String>): Process {
        val types = arrayOf<Class<*>>(Array<String>::class.java,
            Array<String>::class.java, String::class.java)
        val m = try {
            Shizuku::class.java.getDeclaredMethod("newProcess", *types)
        } catch (e: NoSuchMethodException) {
            Shizuku::class.java.getMethod("newProcess", *types)
        }
        m.isAccessible = true
        val args: Array<Any?> = arrayOf(cmd, null, null)
        return m.invoke(null, *args) as Process
    }
}
