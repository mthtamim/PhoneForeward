package com.autotap.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Calibration wizard: shows five crosshair targets one by one; the user
 * taps each center. The average difference between where targets were and
 * where fingers landed becomes the calibration offset applied to every
 * dispatched gesture, cancelling device-specific touch drift.
 */
class CalibrateService : Service() {

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private val windows = mutableListOf<View>()
    private var target: TextView? = null
    private var targets: List<IntArray> = emptyList()
    private var stepIndex = 0
    private var sumDx = 0
    private var sumDy = 0

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (target != null) return START_NOT_STICKY
        val m = resources.displayMetrics
        val inset = Ui.dp(this, 70)
        targets = listOf(
            intArrayOf(inset, inset),
            intArrayOf(m.widthPixels - inset, inset),
            intArrayOf(m.widthPixels / 2, m.heightPixels / 2),
            intArrayOf(inset, m.heightPixels - inset),
            intArrayOf(m.widthPixels - inset, m.heightPixels - inset)
        )
        stepIndex = 0; sumDx = 0; sumDy = 0
        // Full-screen touch layer.
        val layer = View(this)
        val lp = WindowManager.LayoutParams(
            m.widthPixels, m.heightPixels, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layer.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                val t = targets[stepIndex]
                sumDx += (ev.rawX.toInt() - t[0])
                sumDy += (ev.rawY.toInt() - t[1])
                stepIndex++
                if (stepIndex >= targets.size) {
                    val n = targets.size
                    // Gestures shift opposite to the measured finger drift.
                    Store.setCalib(this, -(sumDx / n), -(sumDy / n))
                    toast("Calibration সেভ হলো: offset (${-(sumDx / n)}, ${-(sumDy / n)}) px")
                    cleanup(); stopSelf()
                } else {
                    moveTarget()
                }
            }
            true
        }
        addWindow(layer, lp)
        // Target crosshair.
        val tv = TextView(this).apply {
            text = "+"
            textSize = 44f
            setTextColor(Ui.DANGER)
            gravity = Gravity.CENTER
        }
        val size = Ui.dp(this, 64)
        val tp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        addWindow(tv, tp)
        target = tv
        moveTarget()
        toast("লাল + চিহ্নের ঠিক মাঝখানে 5 বার tap করো")
        return START_NOT_STICKY
    }

    private fun moveTarget() {
        val tv = target ?: return
        val t = targets[stepIndex]
        val p = tv.layoutParams as WindowManager.LayoutParams
        val half = Ui.dp(this, 32)
        p.x = t[0] - half
        p.y = t[1] - half
        try { wm.updateViewLayout(tv, p) } catch (e: Exception) {}
    }

    private fun addWindow(v: View, p: WindowManager.LayoutParams) {
        try {
            wm.addView(v, p)
            windows.add(v)
        } catch (e: Exception) { stopSelf() }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun cleanup() {
        windows.forEach { v -> try { wm.removeView(v) } catch (e: Exception) {} }
        windows.clear()
        target = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, CalibrateService::class.java))
        }
    }
}
