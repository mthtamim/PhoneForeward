package com.autotap.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku input path. When the user enables it in Settings and
 * Shizuku is running with permission granted, taps/swipes are sent as shell
 * `input` commands, which some games/overlays accept more reliably than
 * accessibility gestures. Every failure falls back to gestures silently.
 */
object ShizukuTap {

    private const val REQUEST_CODE = 4701

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) { false }

    fun hasPermission(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun requestPermission() {
        try {
            if (binderAlive() && !hasPermission()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) { /* Shizuku app missing or too old */ }
    }

    fun ready(ctx: Context): Boolean =
        Store.shizukuEnabled(ctx) && hasPermission()

    private fun exec(cmd: String): Boolean {
        return try {
            // Shizuku 13.x marks newProcess private in the API stubs, but
            // the method still exists in the client library at runtime;
            // invoke it reflectively. Any failure falls back to gestures.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            proc.waitFor() == 0
        } catch (e: Throwable) { false }
    }

    /** Returns true when the tap was sent through Shizuku. */
    fun tryTap(ctx: Context, x: Int, y: Int, holdMs: Long): Boolean {
        if (!ready(ctx)) return false
        return if (holdMs > 120) {
            exec("input swipe $x $y $x $y ${holdMs.coerceIn(121, 5000)}")
        } else {
            exec("input tap $x $y")
        }
    }

    fun trySwipe(ctx: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (!ready(ctx)) return false
        return exec("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(1, 10000)}")
    }
}
