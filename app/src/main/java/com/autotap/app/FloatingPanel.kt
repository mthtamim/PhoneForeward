package com.autotap.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Run control overlay (v4 redesign).
 * Two forms: a compact BUBBLE (default) with a progress ring - tap expands,
 * double-tap stops, swipe toggles pause, long-press expands - and the full
 * BOX panel with buttons. The bubble snaps to the nearest screen edge and
 * dodges out of the way when a tap would land under it. Auto-hide collapses
 * the box back to the bubble.
 */
object FloatingPanel {

    /** Circular bubble with a progress ring and a play/pause glyph. */
    class BubbleView(ctx: Context) : View(ctx) {
        var fraction: Float = 0f
        var pausedGlyph: Boolean = false
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CARD; style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.ACCENT; style = Paint.Style.STROKE; strokeWidth = Ui.dp(ctx, 3).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.TEXT; textSize = Ui.dp(ctx, 17).toFloat()
            textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = Ui.dp(context, 4).toFloat()
            val rect = RectF(pad, pad, width - pad, height - pad)
            canvas.drawOval(rect, bgPaint)
            if (fraction > 0f) {
                canvas.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, ringPaint)
            }
            val glyph = if (pausedGlyph) "▶" else "❚❚"
            val y = height / 2f - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
            canvas.drawText(glyph, width / 2f, y, glyphPaint)
        }
    }

    private var wm: WindowManager? = null
    private var panelView: LinearLayout? = null
    private var bubbleView: BubbleView? = null
    private var titleView: TextView? = null
    private var statsView: TextView? = null
    private var statusView: TextView? = null
    private var progressView: TextView? = null
    private var pauseBtn: TextView? = null
    private var bubbleRow: LinearLayout? = null
    private var stepPill: TextView? = null
    private var barFill: View? = null
    private var barRest: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var paused = false
    private var appCtx: Context? = null

    @Volatile private var taps = 0
    @Volatile private var loops = 0
    @Volatile private var startedAt = 0L
    @Volatile private var progressFraction = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun show(ctx: Context) {
        hide()
        appCtx = ctx.applicationContext
        val context = appCtx ?: return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        wm = manager
        paused = false

        buildPanel(context)
        buildBubble(context)
        if (Store.panelForm(context) == 0) {
            panelView?.visibility = View.VISIBLE
            bubbleRow?.visibility = View.GONE
        } else {
            panelView?.visibility = View.GONE
            bubbleRow?.visibility = View.VISIBLE
        }
        refreshStats()
        handler.removeCallbacks(tick)
        handler.post(tick)
        scheduleAutoHide(context)
    }

    private fun buildPanel(context: Context) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 10))
            background = Ui.rounded(context, Ui.CARD, 14, stroke = true)
            alpha = Store.panelOpacity(context) / 100f
        }
        // Header: title + collapse button.
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        titleView = TextView(context).apply {
            text = "AutoTap - ${RunnerService.activeScriptName}"
            setTextColor(Ui.TEXT)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)
        panel.addView(header)
        statsView = TextView(context).apply { setTextColor(Ui.MUTED); textSize = 12f }
        progressView = TextView(context).apply {
            setTextColor(Ui.TEXT); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = TextView(context).apply { setTextColor(Ui.BRIGHT); textSize = 12f }
        panel.addView(statsView)
        panel.addView(progressView)
        panel.addView(statusView)
        // Slim progress bar (the old mini-map strip is gone - it read as
        // a big empty void on most scripts).
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(context, Ui.RAISED, 999)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 5)
            ).apply { topMargin = Ui.dp(context, 6); bottomMargin = Ui.dp(context, 2) }
        }
        barFill = View(context).apply {
            background = Ui.rounded(context, Ui.ACCENT, 999)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        barRest = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        bar.addView(barFill)
        bar.addView(barRest)
        panel.addView(bar)

        // Controls: ONE row of six icon buttons (mock design) - no text.
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = panelButton(context, "⏸") {
            if (paused) RunnerService.sendCmd(context, RunnerService.CMD_RESUME)
            else RunnerService.sendCmd(context, RunnerService.CMD_PAUSE)
        }
        controls.addView(pauseBtn)
        controls.addView(panelButton(context, "⏭") { RunnerService.sendCmd(context, RunnerService.CMD_NEXT) })
        controls.addView(panelButton(context, "⏹") { RunnerService.sendCmd(context, RunnerService.CMD_STOP) })
        controls.addView(panelButton(context, "−") { RunnerService.sendCmd(context, RunnerService.CMD_SPEED_DOWN) })
        controls.addView(panelButton(context, "+") { RunnerService.sendCmd(context, RunnerService.CMD_SPEED_UP) })
        controls.addView(panelButton(context, "⌄") { collapseToBubble() })
        panel.addView(controls)

        val screenW = context.resources.displayMetrics.widthPixels
        val panelWidth = minOf(Ui.dp(context, Store.panelBoxWidthDp(context)), screenW - Ui.dp(context, 16))
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Ui.dp(context, 12)
            y = Ui.dp(context, 120)
        }
        panelParams = params
        // Resize handle: drag the corner to make the panel narrower/wider.
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val handle = TextView(context).apply {
            text = "◢"
            setTextColor(Ui.MUTED)
            textSize = 13f
            setPadding(Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 2), 0)
        }
        var rsX = 0f
        var rsW = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rsX = ev.rawX; rsW = params.width; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val maxW = context.resources.displayMetrics.widthPixels - Ui.dp(context, 16)
                    params.width = (rsW + (ev.rawX - rsX).toInt())
                        .coerceIn(Ui.dp(context, 220), maxW)
                    try { wm?.updateViewLayout(panel, params) } catch (e: Exception) {}
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dpW = (params.width / context.resources.displayMetrics.density).toInt()
                    Store.setPanelBoxWidthDp(context, dpW)
                    true
                }
                else -> false
            }
        }
        footer.addView(handle)
        panel.addView(footer)
        attachDrag(panel, params, context, snapToEdge = false)
        try {
            wm?.addView(panel, params)
            panelView = panel
        } catch (e: Exception) {
            panelView = null
        }
    }

    private fun buildBubble(context: Context) {
        val size = Ui.dp(context, Store.bubbleSizeDp(context))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val bubble = BubbleView(context).apply {
            alpha = Store.panelOpacity(context) / 100f
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        row.addView(bubble)
        // The current step rides next to the bubble (ধাপ 1/2).
        val pill = TextView(context).apply {
            setTextColor(Ui.SECONDARY)
            textSize = 11.5f
            background = Ui.rounded(context, Ui.CARD, 999, stroke = true)
            setPadding(Ui.dp(context, 9), Ui.dp(context, 4), Ui.dp(context, 9), Ui.dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(context, 6) }
            visibility = View.GONE
            setOnClickListener { expandPanel() }
        }
        row.addView(pill)
        stepPill = pill
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size - Ui.dp(context, 92)).coerceAtLeast(0)
            y = Ui.dp(context, 220)
        }
        bubbleParams = params
        attachBubbleGestures(row, params, context)
        try {
            wm?.addView(row, params)
            bubbleRow = row
            bubbleView = bubble
        } catch (e: Exception) {
            bubbleRow = null
            bubbleView = null
        }
    }

    private fun attachBubbleGestures(view: View, params: WindowManager.LayoutParams, context: Context) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var lastTapAt = 0L
        var longFired = false
        val longRunnable = Runnable {
            longFired = true
            expandPanel()
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    downAt = System.currentTimeMillis()
                    longFired = false
                    handler.postDelayed(longRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        handler.removeCallbacks(longRunnable)
                        if (!Store.panelLocked(context)) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            try { wm?.updateViewLayout(view, params) } catch (e: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                    val quick = System.currentTimeMillis() - downAt < 300
                    when {
                        longFired -> Unit
                        dist > 70 -> {
                            // Swipe on the bubble toggles pause.
                            if (paused) RunnerService.sendCmd(context, RunnerService.CMD_RESUME)
                            else RunnerService.sendCmd(context, RunnerService.CMD_PAUSE)
                        }
                        quick && dist < 20 -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTapAt < 350) {
                                lastTapAt = 0L
                                RunnerService.sendCmd(context, RunnerService.CMD_STOP)
                            } else {
                                lastTapAt = now
                                handler.postDelayed({
                                    if (lastTapAt != 0L && bubbleView?.visibility == View.VISIBLE) {
                                        lastTapAt = 0L
                                        expandPanel()
                                    }
                                }, 360)
                            }
                        }
                        else -> snapBubbleToEdge(params, view, context)
                    }
                    if (dist >= 20 && dist <= 70) snapBubbleToEdge(params, view, context)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubbleToEdge(params: WindowManager.LayoutParams, view: View, context: Context) {
        val metrics = context.resources.displayMetrics
        val w = view.width.coerceAtLeast(Ui.dp(context, Store.bubbleSizeDp(context)))
        params.x = if (params.x + w / 2 < metrics.widthPixels / 2) 0
        else metrics.widthPixels - w
        try { wm?.updateViewLayout(view, params) } catch (e: Exception) {}
    }

    private fun expandPanel() {
        bubbleRow?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
        appCtx?.let { scheduleAutoHide(it) }
    }

    private fun collapseToBubble() {
        panelView?.visibility = View.GONE
        bubbleRow?.visibility = View.VISIBLE
    }

    private fun scheduleAutoHide(context: Context) {
        val sec = Store.panelAutoHideSec(context)
        if (sec <= 0) return
        handler.postDelayed({
            if (panelView != null && RunnerService.running) collapseToBubble()
        }, sec * 1000L)
    }

    /** Screen bounds [l, t, r, b] of whichever control is visible, for tap-dodging. */
    fun panelBounds(): IntArray? {
        val v = when {
            bubbleRow?.visibility == View.VISIBLE -> bubbleRow
            panelView?.visibility == View.VISIBLE -> panelView
            else -> null
        } ?: return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (v.width == 0) return null
        return intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Jumps the visible control to the opposite horizontal side of the screen. */
    fun dodge() {
        handler.post {
            val ctx = appCtx ?: return@post
            val metrics = ctx.resources.displayMetrics
            val b = bubbleView
            val p = panelView
            when {
                b != null && bubbleRow?.visibility == View.VISIBLE && bubbleParams != null -> {
                    val bp = bubbleParams!!
                    val rowW = bubbleRow?.width ?: b.width
                    bp.x = if (bp.x + rowW / 2 < metrics.widthPixels / 2)
                        metrics.widthPixels - rowW else 0
                    try { wm?.updateViewLayout(bubbleRow, bp) } catch (e: Exception) {}
                }
                p != null && p.visibility == View.VISIBLE && panelParams != null -> {
                    val pp = panelParams!!
                    pp.x = if (pp.x + p.width / 2 < metrics.widthPixels / 2)
                        metrics.widthPixels - p.width - Ui.dp(ctx, 6) else Ui.dp(ctx, 6)
                    try { wm?.updateViewLayout(p, pp) } catch (e: Exception) {}
                }
            }
        }
    }

    private fun panelButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(Ui.TEXT)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
            background = Ui.rounded(ctx, Ui.RAISED, 10, stroke = true)
            val lp = LinearLayout.LayoutParams(
                0,
                Ui.dp(ctx, 46),
                1f
            )
            lp.setMargins(Ui.dp(ctx, 3), Ui.dp(ctx, 6), Ui.dp(ctx, 3), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams, context: Context, snapToEdge: Boolean) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            if (Store.panelLocked(context)) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    try { wm?.updateViewLayout(view, params) } catch (e: Exception) {}
                    false
                }
                else -> false
            }
        }
    }

    fun setStats(tapCount: Int, started: Long, loopCount: Int) {
        taps = tapCount
        loops = loopCount
        startedAt = started
        handler.post { refreshStats() }
    }

    fun setProgress(text: String, fraction: Float) {
        progressFraction = fraction
        handler.post {
            progressView?.text = text
            val f = fraction.coerceIn(0f, 1f)
            barFill?.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, f * 100f)
            barRest?.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, (1f - f) * 100f)
            bubbleView?.let {
                it.fraction = fraction
                it.invalidate()
            }
        }
    }

    /** Step counter pill shown next to the collapsed bubble. */
    fun setStepPill(index: Int, total: Int) {
        handler.post {
            if (total > 0) {
                stepPill?.text = "ধাপ $index/$total"
                stepPill?.visibility = View.VISIBLE
            } else {
                stepPill?.visibility = View.GONE
            }
        }
    }

    fun setPaused(v: Boolean) {
        handler.post {
            paused = v
            statusView?.text = if (v) "বিরতি দেওয়া আছে" else ""
            pauseBtn?.text = if (v) "▶" else "⏸"
            bubbleView?.let {
                it.pausedGlyph = v
                it.invalidate()
            }
        }
    }

    fun setStatus(text: String) {
        handler.post { statusView?.text = text }
    }

    private fun refreshStats() {
        val elapsedSec = if (startedAt > 0) (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000 else 0
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        statsView?.text = "Tap: $taps  •  লুপ: $loops  •  সময়: ${mm}:${ss.toString().padStart(2, '0')}  •  গতি ${"%.2f".format(RunnerService.speedFactor)}x"
    }

    fun hide() {
        handler.removeCallbacks(tick)
        try { panelView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        try { bubbleRow?.let { wm?.removeView(it) } } catch (e: Exception) {}
        panelView = null
        bubbleView = null
        bubbleRow = null
        stepPill = null
        barFill = null
        barRest = null
    }
}
