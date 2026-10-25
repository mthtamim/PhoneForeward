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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Macro recorder (v2). A full-screen recording layer watches the user's own
 * taps and swipes: each touch is recorded as a step (with the wait since the
 * previous step as its delay) and then replayed into the app underneath via
 * the accessibility service - the layer hides itself for a moment so the
 * replayed gesture reaches the real app. Done appends the recorded steps to
 * the edited script.
 */
class RecordOverlayService : Service() {

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private var layer: View? = null
    private var toolbar: View? = null
    private var layerParams: WindowManager.LayoutParams? = null
    private val steps = mutableListOf<Step>()
    private var lastTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private val pathPoints = mutableListOf<IntArray>()
    private var lastPointMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (layer != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "আগে Overlay permission দাও", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!TapAccessibilityService.isEnabled()) {
            Toast.makeText(this, "আগে Accessibility চালু করো", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        showLayer()
        showToolbar()
        lastTime = System.currentTimeMillis()
        Toast.makeText(this, "রেকর্ড হচ্ছে - যেখানে tap/swipe করবে সেটাই step হবে", Toast.LENGTH_LONG).show()
        return START_NOT_STICKY
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showLayer() {
        val v = View(this).apply {
            setBackgroundColor(0x00000000)
        }
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    pathPoints.clear()
                    pathPoints.add(intArrayOf(downX.toInt(), downY.toInt()))
                    lastPointMs = downTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.rawX - downX) > 24 || kotlin.math.abs(ev.rawY - downY) > 24) moved = true
                    val nowP = System.currentTimeMillis()
                    if (nowP - lastPointMs >= 24 && pathPoints.size < 200) {
                        pathPoints.add(intArrayOf(ev.rawX.toInt(), ev.rawY.toInt()))
                        lastPointMs = nowP
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val delay = (downTime - lastTime).coerceIn(0, 3600000)
                    lastTime = now
                    if (moved) {
                        pathPoints.add(intArrayOf(ev.rawX.toInt(), ev.rawY.toInt()))
                        val dur = (now - downTime).coerceIn(50, 5000)
                        // Freehand: a curvy path (path length much longer than
                        // the straight distance) becomes a PATH step.
                        var pathLen = 0.0
                        for (k in 1 until pathPoints.size) {
                            pathLen += kotlin.math.hypot(
                                (pathPoints[k][0] - pathPoints[k - 1][0]).toDouble(),
                                (pathPoints[k][1] - pathPoints[k - 1][1]).toDouble()
                            )
                        }
                        val straight = kotlin.math.hypot(
                            (ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()
                        )
                        if (pathPoints.size >= 8 && straight > 10 && pathLen > straight * 1.6) {
                            val ptsCopy = pathPoints.map { intArrayOf(it[0], it[1]) }
                            val encoded = ptsCopy.joinToString(";") { "${it[0]},${it[1]}" }
                            steps.add(Step(
                                StepType.PATH, durationMs = dur,
                                delayAfterMs = delay, text = encoded
                            ))
                            replay { svc -> svc.tracePath(ptsCopy, dur) }
                        } else {
                            steps.add(Step(
                                StepType.SWIPE, downX.toInt(), downY.toInt(),
                                ev.rawX.toInt(), ev.rawY.toInt(),
                                durationMs = dur,
                                delayAfterMs = delay
                            ))
                            replay { svc -> svc.swipe(downX.toInt(), downY.toInt(), ev.rawX.toInt(), ev.rawY.toInt(), dur) }
                        }
                    } else {
                        val hold = (now - downTime).coerceIn(50, 5000)
                        val anchorLabel = TapAccessibilityService.instance
                            ?.nodeAt(downX.toInt(), downY.toInt())?.getOrNull(0) ?: ""
                        steps.add(Step(
                            StepType.TAP, downX.toInt(), downY.toInt(),
                            durationMs = hold, delayAfterMs = delay, anchor = anchorLabel
                        ))
                        replay { svc -> svc.tap(downX.toInt(), downY.toInt(), hold) }
                    }
                    updateToolbarCount()
                    true
                }
                else -> false
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        layerParams = p
        layer = v
        try { wm.addView(v, p) } catch (e: Exception) { stopSelf() }
    }

    /** Hide the layer, let the recorded gesture land on the real app, then come back. */
    private fun replay(action: (TapAccessibilityService) -> Unit) {
        val v = layer ?: return
        v.visibility = View.INVISIBLE
        handler.postDelayed({
            TapAccessibilityService.instance?.let { action(it) }
            handler.postDelayed({ v.visibility = View.VISIBLE }, 350)
        }, 120)
    }

    private var countText: TextView? = null

    private fun updateToolbarCount() {
        countText?.text = "● REC ${steps.size}"
    }

    private fun showToolbar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(this@RecordOverlayService, Ui.CARD, 20, stroke = true)
            setPadding(Ui.dp(this@RecordOverlayService, 10), Ui.dp(this@RecordOverlayService, 8),
                Ui.dp(this@RecordOverlayService, 10), Ui.dp(this@RecordOverlayService, 8))
        }
        countText = TextView(this).apply {
            text = "● REC 0"
            textSize = 13f
            setTextColor(Ui.DANGER)
        }
        bar.addView(countText)
        fun toolBtn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Ui.TEXT)
            background = Ui.rounded(this@RecordOverlayService, Ui.RAISED, 999, stroke = true)
            setPadding(Ui.dp(this@RecordOverlayService, 12), Ui.dp(this@RecordOverlayService, 6),
                Ui.dp(this@RecordOverlayService, 12), Ui.dp(this@RecordOverlayService, 6))
            setOnClickListener { action() }
        }
        val undoBtn = toolBtn("শেষ মোছো") {
            if (steps.isNotEmpty()) {
                steps.removeAt(steps.size - 1)
                updateToolbarCount()
            }
        }
        undoBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(this@RecordOverlayService, 8) }
        bar.addView(undoBtn)
        val doneBtn = toolBtn("Done") { done() }
        doneBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(this@RecordOverlayService, 8) }
        bar.addView(doneBtn)
        val cancelBtn = toolBtn("বাতিল") { cleanup(); stopSelf() }
        cancelBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(this@RecordOverlayService, 8) }
        bar.addView(cancelBtn)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(this@RecordOverlayService, 48)
        }
        try { wm.addView(bar, p) } catch (e: Exception) {}
        toolbar = bar
    }

    private fun done() {
        val scriptId = Store.editScriptId(this)
        val script = scriptId?.let { Store.getScript(this, it) }
        if (script != null && steps.isNotEmpty()) {
            // The recorder stores each step's PRE-wait in its delayAfterMs;
            // the runner's model is delay AFTER a step, so shift them by one:
            // step i's delay becomes the wait that preceded step i+1.
            val fixed = steps.mapIndexed { i, s ->
                s.copy(delayAfterMs = if (i < steps.size - 1) steps[i + 1].delayAfterMs else 1000)
            }
            script.steps.addAll(fixed)
            Store.upsert(this, script)
            Store.setSelectedId(this, script.id)
            Toast.makeText(this, "${steps.size}টি step রেকর্ড হয়ে যোগ হলো", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "কিছু রেকর্ড হয়নি", Toast.LENGTH_LONG).show()
        }
        cleanup()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        stopSelf()
    }

    private fun cleanup() {
        layer?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        layer = null
        toolbar?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        toolbar = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
