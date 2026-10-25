package com.autotap.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Quick dock: a small edge bubble that stays available (only while the user
 * keeps it enabled in Settings). Tapping it opens the favorite scripts;
 * tapping one starts/stops it without opening the app.
 */
class DockService : Service() {

    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var menu: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble != null) return START_STICKY
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WindowManager::class.java)
        val metrics = resources.displayMetrics
        val size = Ui.dp(this, (Store.bubbleSizeDp(this) - 12).coerceAtLeast(36))
        val b = TextView(this).apply {
            text = "A"
            textSize = 16f
            setTextColor(Ui.BG)
            gravity = Gravity.CENTER
            background = Ui.circle(this@DockService, Ui.ACCENT)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            alpha = 0.92f
            setOnClickListener { toggleMenu() }
        }
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = metrics.heightPixels / 3
        }
        try {
            wm?.addView(b, p)
            bubble = b
        } catch (e: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun toggleMenu() {
        if (menu != null) {
            hideMenu()
            return
        }
        val favorites = Store.getScripts(this).filter { it.favorite }
        if (favorites.isEmpty()) {
            Toast.makeText(this, "কোনো প্রিয় (star) script নেই - অ্যাপে গিয়ে star দাও", Toast.LENGTH_LONG).show()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@DockService, Ui.CARD, 16, stroke = true)
            setPadding(Ui.dp(this@DockService, 10), Ui.dp(this@DockService, 8),
                Ui.dp(this@DockService, 10), Ui.dp(this@DockService, 8))
        }
        favorites.take(6).forEach { script ->
            val runningThis = RunnerService.running && RunnerService.activeScriptId == script.id
            val item = TextView(this).apply {
                text = (if (runningThis) "বন্ধ করো: " else "চালাও: ") + script.name
                setTextColor(if (runningThis) Ui.DANGER else Ui.TEXT)
                textSize = 14f
                setPadding(Ui.dp(this@DockService, 10), Ui.dp(this@DockService, 9),
                    Ui.dp(this@DockService, 10), Ui.dp(this@DockService, 9))
                setOnClickListener {
                    if (RunnerService.running) {
                        RunnerService.sendCmd(this@DockService, RunnerService.CMD_STOP)
                    } else if (TapAccessibilityService.isEnabled() && script.steps.isNotEmpty()) {
                        RunnerService.start(this@DockService, script.id)
                    } else {
                        Toast.makeText(this@DockService, "Accessibility বন্ধ বা step নেই", Toast.LENGTH_LONG).show()
                    }
                    hideMenu()
                }
            }
            layout.addView(item)
        }
        val metrics = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Ui.dp(this@DockService, 52)
            y = metrics.heightPixels / 3
        }
        try {
            wm?.addView(layout, p)
            menu = layout
        } catch (e: Exception) { /* ignore */ }
    }

    private fun hideMenu() {
        try { menu?.let { wm?.removeView(it) } } catch (e: Exception) {}
        menu = null
    }

    override fun onDestroy() {
        hideMenu()
        try { bubble?.let { wm?.removeView(it) } } catch (e: Exception) {}
        bubble = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, DockService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DockService::class.java))
        }
    }
}
