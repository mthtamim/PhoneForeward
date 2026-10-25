package com.autotap.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-screen setup (v4 redesign). Draggable markers over the target app,
 * now with: tap-to-add + guided banner, path lines between markers, grid +
 * coordinate readout, zoom lens while dragging, crosshair, frozen-screenshot
 * setup, a bottom setup panel (select a marker to edit its delay with a
 * slider, duplicate / delete), preview run, undo / redo, single-marker
 * delete on long-press, and an auto-saved draft so half-finished setups are
 * never lost. Done writes steps back; element anchors are recorded for taps.
 */
class EditOverlayService : Service() {

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())

    private data class Spec(
        val kind: Int, // 0 tap, 1 swipe, 2 dual
        var x: Int, var y: Int, var x2: Int = 0, var y2: Int = 0,
        var delayMs: Long = 1000, var holdMs: Long = 50
    )

    private val specs = mutableListOf<Spec>()
    private val markerViews = mutableListOf<MutableList<View>>()
    private val allWindows = mutableListOf<View>()

    private var toolbar: View? = null
    private var banner: TextView? = null
    private var panel: LinearLayout? = null
    private var panelInfo: TextView? = null
    private var pathView: PathView? = null
    private var gridView: GridView? = null
    private var frozenView: ImageView? = null
    private var tapLayer: View? = null
    private var crossH: View? = null
    private var crossV: View? = null
    private var zoomView: ImageView? = null
    private var ringView: View? = null

    private var toolbarParams: WindowManager.LayoutParams? = null
    private var toolbarX: Int? = null
    private var toolbarY: Int? = null
    private var panelChips: LinearLayout? = null
    private var selected = -1
    private var tapAddMode = false
    private var gridOn = false
    private var frozenOn = false

    private val undoStack = mutableListOf<List<Spec>>()
    private val redoStack = mutableListOf<List<Spec>>()

    inner class PathView(ctx: Context) : View(ctx) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.BRIGHT; strokeWidth = Ui.dp(ctx, 2).toFloat(); style = Paint.Style.STROKE
            alpha = 150
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BRIGHT; alpha = 200 }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (i in 0 until specs.size - 1) {
                val a = specs[i]
                val b = specs[i + 1]
                canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), linePaint)
            }
            for (s in specs) {
                canvas.drawCircle(s.x.toFloat(), s.y.toFloat(), Ui.dp(context, 3).toFloat(), dotPaint)
                if (s.kind != 0) {
                    canvas.drawLine(s.x.toFloat(), s.y.toFloat(), s.x2.toFloat(), s.y2.toFloat(), linePaint)
                }
            }
        }
    }

    inner class GridView(ctx: Context) : View(ctx) {
        private val paint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val step = Ui.dp(context, 60)
            var x = step
            while (x < width) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
                x += step
            }
            var y = step
            while (y < height) {
                canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
                y += step
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun notTouchableParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun markerSizeDp(): Int = when (Store.markerSize(this)) {
        0 -> 40; 2 -> 68; else -> 52
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (toolbar != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "আগে Overlay permission দাও", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        buildLayers()
        // Load: an unfinished draft wins over the saved steps.
        val scriptId = Store.editScriptId(this)
        val draft = scriptId?.let { Store.editDraft(this, it) }
        var loadedFromDraft = false
        if (!draft.isNullOrEmpty()) {
            try {
                val arr = JSONArray(draft)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    specs.add(
                        Spec(
                            o.optInt("kind"), o.optInt("x"), o.optInt("y"),
                            o.optInt("x2"), o.optInt("y2"),
                            o.optLong("delayMs", 1000), o.optLong("holdMs", 50)
                        )
                    )
                }
                loadedFromDraft = specs.isNotEmpty()
            } catch (e: Exception) { specs.clear() }
        }
        if (!loadedFromDraft) {
            val existing = scriptId?.let { Store.getScript(this, it) }
            existing?.steps?.forEach { step ->
                when (step.type) {
                    StepType.TAP -> specs.add(Spec(0, step.x, step.y, delayMs = step.delayAfterMs, holdMs = step.durationMs))
                    StepType.SWIPE -> specs.add(Spec(1, step.x, step.y, step.x2, step.y2, delayMs = step.delayAfterMs, holdMs = step.durationMs))
                    StepType.DUAL_TAP -> specs.add(Spec(2, step.x, step.y, step.x2, step.y2, delayMs = step.delayAfterMs))
                    StepType.DRAG_DROP -> specs.add(Spec(3, step.x, step.y, step.x2, step.y2, delayMs = step.delayAfterMs, holdMs = step.durationMs))
                    else -> Unit
                }
            }
        }
        specs.forEach { addMarkerViews(it) }
        refreshPaths()
        showToolbar()
        if (loadedFromDraft) toast("আগের অর্ধেক setup (draft) থেকে ফিরিয়ে আনা হলো")
        else toast("মার্কার টেনে জায়গামতো বসাও, তারপর Done")
        return START_NOT_STICKY
    }

    private fun buildLayers() {
        val metrics = resources.displayMetrics
        frozenView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        addWindow(frozenView!!, notTouchableParams(metrics.widthPixels, metrics.heightPixels))
        gridView = GridView(this).apply { visibility = View.GONE }
        addWindow(gridView!!, notTouchableParams(metrics.widthPixels, metrics.heightPixels))
        pathView = PathView(this)
        addWindow(pathView!!, notTouchableParams(metrics.widthPixels, metrics.heightPixels))
        crossH = View(this).apply {
            setBackgroundColor(Ui.BRIGHT); visibility = View.GONE
        }
        addWindow(crossH!!, notTouchableParams(metrics.widthPixels, Ui.dp(this, 2)))
        crossV = View(this).apply {
            setBackgroundColor(Ui.BRIGHT); visibility = View.GONE
        }
        addWindow(crossV!!, notTouchableParams(Ui.dp(this, 2), metrics.heightPixels))
        val layer = View(this)
        val lp = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layer.setOnTouchListener { _, ev -> handleLayerTouch(ev) }
        layer.visibility = View.GONE
        addWindow(layer, lp)
        tapLayer = layer
        zoomView = ImageView(this).apply {
            visibility = View.GONE
            background = Ui.rounded(this@EditOverlayService, Ui.CARD, 16, stroke = true)
        }
        val zp = notTouchableParams(Ui.dp(this, 150), Ui.dp(this, 150))
        zp.x = (metrics.widthPixels - Ui.dp(this, 150)) / 2
        zp.y = Ui.dp(this, 130)
        addWindow(zoomView!!, zp)
        ringView = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(Ui.dp(this@EditOverlayService, 3), Ui.BRIGHT)
                setColor(0x22000000)
            }
            visibility = View.GONE
        }
        addWindow(ringView!!, notTouchableParams(Ui.dp(this, 56), Ui.dp(this, 56)))
    }

    private fun addWindow(v: View, p: WindowManager.LayoutParams) {
        try {
            wm.addView(v, p)
            allWindows.add(v)
        } catch (e: Exception) { /* overlay may be revoked */ }
    }

    // ---------- markers ----------

    private fun markerLabel(index: Int, spec: Spec, part: Int): String {
        val n = index + 1
        return when (spec.kind) {
            1 -> if (part == 0) "${n}S" else "${n}E"
            2 -> if (part == 0) "${n}D1" else "${n}D2"
            3 -> if (part == 0) "${n}a" else "${n}b"
            else -> n.toString()
        }
    }

    private fun makeMarker(spec: Spec, index: Int, part: Int): View {
        val size = Ui.dp(this, markerSizeDp())
        val half = size / 2
        val cx = if (part == 0) spec.x else spec.x2
        val cy = if (part == 0) spec.y else spec.y2
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - half; y = cy - half
        }
        val isSel = index == selected
        val v = TextView(this).apply {
            text = markerLabel(index, spec, part)
            textSize = 16f
            setTextColor(Ui.BG)
            gravity = Gravity.CENTER
            background = Ui.circle(this@EditOverlayService, if (isSel) Ui.BRIGHT else Ui.ACCENT)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        attachMarkerTouch(v, p, spec, part)
        addWindow(v, p)
        return v
    }

    private fun addMarkerViews(spec: Spec) {
        val idx = specs.indexOf(spec)
        val views = mutableListOf<View>()
        views.add(makeMarker(spec, idx, 0))
        if (spec.kind != 0) views.add(makeMarker(spec, idx, 1))
        markerViews.add(views)
    }

    private fun rebuildMarkers() {
        markerViews.flatten().forEach { v ->
            try { wm.removeView(v) } catch (e: Exception) {}
            allWindows.remove(v)
        }
        markerViews.clear()
        specs.forEach { spec ->
            val idx = specs.indexOf(spec)
            val views = mutableListOf<View>()
            views.add(makeMarker(spec, idx, 0))
            if (spec.kind != 0) views.add(makeMarker(spec, idx, 1))
            markerViews.add(views)
        }
        refreshPaths()
        refreshPanel()
    }

    private fun attachMarkerTouch(v: View, p: WindowManager.LayoutParams, spec: Spec, part: Int) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var downAt = 0L; var moved = false; var longFired = false
        val longRunnable = Runnable {
            if (!moved) {
                longFired = true
                pushHistory()
                val idx = specs.indexOf(spec)
                if (idx >= 0) {
                    specs.removeAt(idx)
                    if (selected == idx) selected = -1
                    saveDraft()
                    rebuildMarkers()
                    toast("Marker মোছা হলো (Undo করা যাবে)")
                }
            }
        }
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y; touchX = ev.rawX; touchY = ev.rawY
                    downAt = System.currentTimeMillis(); moved = false; longFired = false
                    handler.postDelayed(longRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 14) {
                        moved = true
                        handler.removeCallbacks(longRunnable)
                    }
                    p.x = startX + dx; p.y = startY + dy
                    try { wm.updateViewLayout(v, p) } catch (e: Exception) {}
                    val half = Ui.dp(this, markerSizeDp()) / 2
                    if (part == 0) { spec.x = snapGrid(p.x + half); spec.y = snapGrid(p.y + half) }
                    else { spec.x2 = snapGrid(p.x + half); spec.y2 = snapGrid(p.y + half) }
                    refreshPaths()
                    updateZoom(spec, part)
                    updateBanner("${specs.indexOf(spec) + 1} · ${spec.x},${spec.y}")
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    zoomView?.visibility = View.GONE
                    if (longFired) return@setOnTouchListener true
                    if (!moved && System.currentTimeMillis() - downAt < 400) {
                        selected = specs.indexOf(spec)
                        rebuildMarkers()
                        showPanel()
                    } else if (moved) {
                        saveDraft()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateZoom(spec: Spec, part: Int) {
        val frame = ScreenCaptureService.snapshot() ?: return
        val cx = if (part == 0) spec.x else spec.x2
        val cy = if (part == 0) spec.y else spec.y2
        val r = 70
        val left = (cx - r).coerceIn(0, (frame.width - 2 * r).coerceAtLeast(0))
        val top = (cy - r).coerceIn(0, (frame.height - 2 * r).coerceAtLeast(0))
        try {
            val crop = Bitmap.createBitmap(frame, left, top, (2 * r).coerceAtMost(frame.width - left), (2 * r).coerceAtMost(frame.height - top))
            val scaled = Bitmap.createScaledBitmap(crop, Ui.dp(this, 150), Ui.dp(this, 150), true)
            zoomView?.setImageBitmap(scaled)
            zoomView?.visibility = View.VISIBLE
        } catch (e: Exception) { /* zoom is best-effort */ }
    }

    private fun snapGrid(v: Int): Int =
        if (Store.gridSnap(this)) ((v + 10) / 20) * 20 else v

    // ---------- tap-to-add layer ----------

    private fun handleLayerTouch(ev: MotionEvent): Boolean {
        if (!tapAddMode) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                moveCrosshair(ev.rawX.toInt(), ev.rawY.toInt())
                return true
            }
            MotionEvent.ACTION_UP -> {
                crossH?.visibility = View.GONE
                crossV?.visibility = View.GONE
                var x = snapGrid(ev.rawX.toInt())
                var y = snapGrid(ev.rawY.toInt())
                // One-touch centering: land exactly on the element's center.
                val svc = TapAccessibilityService.instance
                val info = svc?.nodeAt(x, y)
                if (info == null && svc != null) {
                    // No accessibility node (games/canvas): center on the
                    // same-color blob under the finger instead.
                    val frame = ScreenCaptureService.snapshot()
                    if (frame != null) {
                        val blob = svc.centerOfColorBlob(frame, x, y)
                        if (blob != null) { x = blob[0]; y = blob[1] }
                    }
                }
                if (info != null) {
                    val l = info[1].toIntOrNull(); val tp = info[2].toIntOrNull()
                    val r = info[3].toIntOrNull(); val b = info[4].toIntOrNull()
                    if (l != null && tp != null && r != null && b != null) {
                        x = (l + r) / 2; y = (tp + b) / 2
                    }
                }
                pushHistory()
                val spec = Spec(0, x, y)
                specs.add(spec)
                addMarkerViews(spec)
                refreshPaths()
                saveDraft()
                val placedLabel = info?.getOrNull(0) ?: ""
                if (placedLabel.isNotEmpty()) {
                    updateBanner("✓ " + placedLabel)
                    handler.postDelayed({ updateBanner(null) }, 1500)
                } else {
                    updateBanner(null)
                }
                return true
            }
        }
        return true
    }

    private fun moveCrosshair(x: Int, y: Int) {
        val h = crossH ?: return
        val v = crossV ?: return
        val hp = h.layoutParams as WindowManager.LayoutParams
        hp.y = y
        val vp = v.layoutParams as WindowManager.LayoutParams
        vp.x = x
        h.visibility = View.VISIBLE
        v.visibility = View.VISIBLE
        try { wm.updateViewLayout(h, hp) } catch (e: Exception) {}
        try { wm.updateViewLayout(v, vp) } catch (e: Exception) {}
    }

    // ---------- history / draft ----------

    private fun pushHistory() {
        undoStack.add(specs.map { it.copy() })
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun applySpecs(list: List<Spec>) {
        specs.clear()
        list.forEach { specs.add(it.copy()) }
        selected = -1
        rebuildMarkers()
        saveDraft()
    }

    private fun saveDraft() {
        val scriptId = Store.editScriptId(this) ?: return
        val arr = JSONArray()
        specs.forEach { s ->
            arr.put(JSONObject().apply {
                put("kind", s.kind); put("x", s.x); put("y", s.y)
                put("x2", s.x2); put("y2", s.y2)
                put("delayMs", s.delayMs); put("holdMs", s.holdMs)
            })
        }
        Store.setEditDraft(this, scriptId, arr.toString())
    }

    // ---------- toolbar (minimal: drawn preview icons, movable, resizable) ----------

    inner class IconBtn(
        ctx: Context,
        private val kind: Int,
        private val glyph: String?,
        private val label: String,
        private val action: () -> Unit
    ) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.TEXT; strokeWidth = Ui.dp(ctx, 2).toFloat(); style = Paint.Style.STROKE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.TEXT; style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.TEXT; textAlign = Paint.Align.CENTER
        }

        init {
            background = Ui.rounded(ctx, Ui.RAISED, 10, stroke = true)
            setOnClickListener { action() }
            setOnLongClickListener {
                Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 4f
            if (glyph != null) {
                textPaint.textSize = height * 0.5f
                canvas.drawText(glyph, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
                return
            }
            when (kind) {
                0 -> { // tap point: ring + dot
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.drawCircle(cx, cy, r * 0.3f, fillPaint)
                }
                1 -> { // swipe: diagonal arrow
                    canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
                    canvas.drawLine(cx + r, cy - r, cx + r * 0.15f, cy - r, paint)
                    canvas.drawLine(cx + r, cy - r, cx + r, cy - r * 0.15f, paint)
                }
                2 -> { // dual: two rings
                    canvas.drawCircle(cx - r * 0.7f, cy, r * 0.75f, paint)
                    canvas.drawCircle(cx + r * 0.7f, cy, r * 0.75f, paint)
                }
                3 -> { // tap-to-add: crosshair target
                    canvas.drawCircle(cx, cy, r * 0.9f, paint)
                    canvas.drawLine(cx - r * 1.5f, cy, cx - r * 0.45f, cy, paint)
                    canvas.drawLine(cx + r * 0.45f, cy, cx + r * 1.5f, cy, paint)
                    canvas.drawLine(cx, cy - r * 1.5f, cx, cy - r * 0.45f, paint)
                    canvas.drawLine(cx, cy + r * 0.45f, cx, cy + r * 1.5f, paint)
                }
                4 -> { // grid
                    val s = r * 0.85f
                    canvas.drawRect(cx - s, cy - s, cx + s, cy + s, paint)
                    canvas.drawLine(cx, cy - s, cx, cy + s, paint)
                    canvas.drawLine(cx - s, cy, cx + s, cy, paint)
                }
                6 -> { // drag & drop: circle, arrow, square
                    canvas.drawCircle(cx - r * 0.8f, cy, r * 0.5f, paint)
                    canvas.drawLine(cx - r * 0.2f, cy, cx + r * 0.5f, cy, paint)
                    canvas.drawRect(cx + r * 0.5f, cy - r * 0.5f, cx + r, cy + r * 0.5f, paint)
                }
                5 -> { // frozen image: frame + dot
                    canvas.drawRect(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f, paint)
                    canvas.drawCircle(cx - r * 0.4f, cy - r * 0.3f, r * 0.18f, fillPaint)
                }
            }
        }
    }

    private fun showToolbar() {
        toolbar?.let { old ->
            try { wm.removeView(old) } catch (e: Exception) {}
            allWindows.remove(old)
        }
        val scale = Store.overlayScale(this)
        val btnDp = (38 * scale).toInt().coerceAtLeast(26)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@EditOverlayService, Ui.CARD, 16, stroke = true)
            setPadding(Ui.dp(this@EditOverlayService, 5), Ui.dp(this@EditOverlayService, 5),
                Ui.dp(this@EditOverlayService, 5), Ui.dp(this@EditOverlayService, 5))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        toolbarParams = p
        // Drag handle: moves the whole bar.
        var dragSX = 0f; var dragSY = 0f; var winSX = 0; var winSY = 0
        val handle = TextView(this).apply {
            text = "⠿"
            setTextColor(Ui.MUTED)
            textSize = 17f
            gravity = Gravity.CENTER
            val s = Ui.dp(this@EditOverlayService, btnDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragSX = ev.rawX; dragSY = ev.rawY
                    val loc = IntArray(2)
                    toolbar?.getLocationOnScreen(loc)
                    winSX = loc[0]; winSY = loc[1]
                    p.gravity = Gravity.TOP or Gravity.START
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = winSX + (ev.rawX - dragSX).toInt()
                    p.y = winSY + (ev.rawY - dragSY).toInt()
                    toolbarX = p.x; toolbarY = p.y
                    try { wm.updateViewLayout(toolbar, p) } catch (e: Exception) {}
                    true
                }
                else -> false
            }
        }
        root.addView(handle)
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun icon(kind: Int, glyph: String?, label: String, action: () -> Unit) {
            val b = IconBtn(this, kind, glyph, label, action)
            val s = Ui.dp(this, btnDp)
            b.layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = Ui.dp(this@EditOverlayService, 3)
                rightMargin = Ui.dp(this@EditOverlayService, 3)
            }
            row.addView(b)
        }
        icon(0, null, "নতুন Tap পয়েন্ট") {
            pushHistory()
            val s = Spec(0, cx, cy); specs.add(s); addMarkerViews(s); refreshPaths(); saveDraft()
        }
        icon(1, null, "নতুন Swipe") {
            pushHistory()
            val s = Spec(1, cx, cy + Ui.dp(this, 120), cx, cy - Ui.dp(this, 120), holdMs = 600)
            specs.add(s); addMarkerViews(s); refreshPaths(); saveDraft()
        }
        icon(2, null, "নতুন Dual tap") {
            pushHistory()
            val s = Spec(2, cx - Ui.dp(this, 90), cy, cx + Ui.dp(this, 90), cy)
            specs.add(s); addMarkerViews(s); refreshPaths(); saveDraft()
        }
        icon(6, null, "নতুন Drag & drop (চেপে টেনে ছাড়া)") {
            pushHistory()
            val s = Spec(3, cx - Ui.dp(this, 70), cy, cx + Ui.dp(this, 70), cy, holdMs = 700)
            specs.add(s); addMarkerViews(s); refreshPaths(); saveDraft()
        }
        icon(3, null, "ছোঁয়াও মোড: যেখানে ছোঁয়াবে সেখানেই পয়েন্ট") {
            tapAddMode = !tapAddMode
            tapLayer?.visibility = if (tapAddMode) View.VISIBLE else View.GONE
            updateBanner(null)
        }
        icon(4, null, "গ্রিড") {
            gridOn = !gridOn
            gridView?.visibility = if (gridOn) View.VISIBLE else View.GONE
        }
        icon(5, null, "Screenshot জমিয়ে তার উপর setup") {
            if (frozenOn) {
                frozenOn = false
                frozenView?.visibility = View.GONE
            } else {
                val frame = ScreenCaptureService.snapshot()
                if (frame == null) {
                    toast("আগে অ্যাপ থেকে একবার capture চালু করে script চালাও")
                } else {
                    frozenView?.setImageBitmap(frame)
                    frozenView?.visibility = View.VISIBLE
                    frozenOn = true
                }
            }
        }
        icon(-1, "☰", "Setup প্যানেল") { showPanel() }
        icon(-1, "▶", "Preview: ধীরে চালিয়ে দেখো") { preview() }
        icon(-1, "◌", "Ghost preview: tap ছাড়া শুধু পয়েন্ট দেখো") { preview(ghost = true) }
        icon(-1, "↶", "Undo") {
            if (undoStack.isNotEmpty()) {
                redoStack.add(specs.map { it.copy() })
                applySpecs(undoStack.removeAt(undoStack.size - 1))
            } else toast("Undo করার কিছু নেই")
        }
        icon(-1, "↷", "Redo") {
            if (redoStack.isNotEmpty()) {
                undoStack.add(specs.map { it.copy() })
                applySpecs(redoStack.removeAt(redoStack.size - 1))
            } else toast("Redo করার কিছু নেই")
        }
        icon(-1, "✓", "Done: সেভ করো") { done() }
        icon(-1, "✕", "বাতিল (draft থেকে যাবে)") {
            toast("Draft রাখা হলো")
            cleanup(); stopSelf()
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(row)
        }
        root.addView(scroll)
        // Resize handle: drag to scale the bar, release to apply.
        var rsSX = 0f; var rsSY = 0f; var rsScale = 1f; var pendingScale = 1f
        val resize = TextView(this).apply {
            text = "⤢"
            setTextColor(Ui.MUTED)
            textSize = 15f
            gravity = Gravity.CENTER
            val s = Ui.dp(this@EditOverlayService, btnDp)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        resize.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    rsSX = ev.rawX; rsSY = ev.rawY
                    rsScale = Store.overlayScale(this@EditOverlayService)
                    pendingScale = rsScale
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = ((ev.rawX - rsSX) + (ev.rawY - rsSY)) / 420f
                    pendingScale = (rsScale + d).coerceIn(0.7f, 1.6f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Store.setOverlayScale(this@EditOverlayService, pendingScale)
                    showToolbar()
                    true
                }
                else -> false
            }
        }
        root.addView(resize)
        val side = Store.toolbarSide(this)
        if (toolbarX != null) {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = toolbarX!!
            p.y = toolbarY ?: Ui.dp(this, 48)
        } else {
            p.gravity = Gravity.TOP or when (side) {
                1 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            p.y = Ui.dp(this, 48)
        }
        addWindow(root, p)
        toolbar = root
        if (banner == null) {
            val b = TextView(this).apply {
                setTextColor(Ui.BRIGHT)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 6),
                    Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 6))
                background = Ui.rounded(this@EditOverlayService, Ui.CARD, 999, stroke = true)
                visibility = View.GONE
            }
            val bp = notTouchableParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
            )
            bp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bp.y = Ui.dp(this, 118)
            addWindow(b, bp)
            banner = b
        }
    }

    private fun updateBanner(custom: String?) {
        val b = banner ?: return
        if (custom != null) {
            b.text = custom
            b.visibility = View.VISIBLE
            return
        }
        if (tapAddMode) {
            b.text = "＋ " + (specs.size + 1)
            b.visibility = View.VISIBLE
        } else {
            b.visibility = View.GONE
        }
    }

    // ---------- bottom setup panel (minimal: number chips + one slider) ----------

    private fun showPanel() {
        if (panel != null) {
            refreshPanel()
            panel?.visibility = View.VISIBLE
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@EditOverlayService, Ui.CARD, 20, stroke = true)
            setPadding(Ui.dp(this@EditOverlayService, 10), Ui.dp(this@EditOverlayService, 8),
                Ui.dp(this@EditOverlayService, 10), Ui.dp(this@EditOverlayService, 8))
        }
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }
        panelChips = chipsRow
        root.addView(chipsScroll)
        panelInfo = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 13f }
        root.addView(panelInfo)
        val seek = SeekBar(this).apply { max = 5000 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && selected in specs.indices) {
                    specs[selected].delayMs = progress.toLong()
                    refreshPanel()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saveDraft() }
        })
        root.tag = seek
        root.addView(seek)
        // Nudge row: pixel-precise moves for the selected marker.
        val nudgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun nBtn(glyph: String, dx: Int, dy: Int) = TextView(this).apply {
            text = glyph
            textSize = 14f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@EditOverlayService, Ui.RAISED, 999, stroke = true)
            setPadding(Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 5),
                Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@EditOverlayService, 4); rightMargin = Ui.dp(this@EditOverlayService, 4) }
            fun move(step: Int) {
                if (selected in specs.indices) {
                    val s = specs[selected]
                    s.x += dx * step; s.y += dy * step
                    if (s.kind != 0) { /* only the start point nudges */ }
                    saveDraft(); rebuildMarkers(); panel?.visibility = View.VISIBLE
                }
            }
            setOnClickListener { move(1) }
            setOnLongClickListener { move(10); true }
        }
        nudgeRow.addView(nBtn("◀", -1, 0))
        nudgeRow.addView(nBtn("▲", 0, -1))
        nudgeRow.addView(nBtn("▼", 0, 1))
        nudgeRow.addView(nBtn("▶", 1, 0))
        root.addView(nudgeRow)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pBtn(text: String, label: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@EditOverlayService, Ui.RAISED, 999, stroke = true)
            setPadding(Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 5),
                Ui.dp(this@EditOverlayService, 12), Ui.dp(this@EditOverlayService, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@EditOverlayService, 4); rightMargin = Ui.dp(this@EditOverlayService, 4) }
            setOnClickListener { action() }
            setOnLongClickListener {
                Toast.makeText(this@EditOverlayService, label, Toast.LENGTH_SHORT).show()
                true
            }
        }
        row.addView(pBtn("⧉", "কপি করো") {
            if (selected in specs.indices) {
                pushHistory()
                val s = specs[selected].copy(x = specs[selected].x + 36, y = specs[selected].y + 36,
                    x2 = specs[selected].x2 + 36, y2 = specs[selected].y2 + 36)
                specs.add(s); addMarkerViews(s); refreshPaths(); saveDraft(); refreshPanel()
            }
        })
        row.addView(pBtn("✕", "মুছে ফেলো") {
            if (selected in specs.indices) {
                pushHistory()
                specs.removeAt(selected)
                selected = -1
                saveDraft(); rebuildMarkers()
            }
        })
        row.addView(pBtn("▽", "বন্ধ করো") { panel?.visibility = View.GONE })
        root.addView(row)
        val metrics = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            metrics.widthPixels, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        addWindow(root, p)
        panel = root
        refreshPanel()
    }

    private fun refreshPanel() {
        val chips = panelChips ?: return
        chips.removeAllViews()
        specs.forEachIndexed { i, _ ->
            val chip = TextView(this).apply {
                text = (i + 1).toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (i == selected) Ui.BG else Ui.TEXT)
                background = Ui.circle(this@EditOverlayService, if (i == selected) Ui.BRIGHT else Ui.RAISED)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                val sz = Ui.dp(this@EditOverlayService, 30)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    rightMargin = Ui.dp(this@EditOverlayService, 5)
                }
                setOnClickListener {
                    selected = i
                    rebuildMarkers()
                    panel?.visibility = View.VISIBLE
                }
            }
            chips.addView(chip)
        }
        panelInfo?.text = when {
            specs.isEmpty() -> "কোনো পয়েন্ট নেই"
            selected in specs.indices -> {
                val s = specs[selected]
                val kind = when (s.kind) { 0 -> "Tap"; 1 -> "Swipe"; else -> "Dual" }
                "ধাপ " + (selected + 1) + " · " + kind + " · ⏱ " + s.delayMs + "ms"
            }
            else -> "মোট " + specs.size + "টি পয়েন্ট"
        }
        val seek = panel?.tag as? SeekBar
        if (selected in specs.indices) {
            seek?.progress = specs[selected].delayMs.toInt().coerceIn(0, 5000)
        }
    }

    // ---------- preview ----------

    private fun preview(ghost: Boolean = false) {
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            toast("আগে Accessibility চালু করো")
            return
        }
        if (specs.isEmpty()) {
            toast("কোনো marker নেই")
            return
        }
        val toHide = mutableListOf<View>()
        toolbar?.let { toHide.add(it) }
        banner?.let { toHide.add(it) }
        panel?.let { toHide.add(it) }
        markerViews.flatten().forEach { toHide.add(it) }
        toHide.forEach { it.visibility = View.GONE }
        pathView?.visibility = View.GONE
        Thread {
            Thread.sleep(500)
            for (s in specs.toList()) {
                if (toolbar == null) break
                showRing(s.x, s.y)
                Thread.sleep(650)
                if (!ghost) {
                    when (s.kind) {
                        0 -> svc.tap(s.x, s.y, s.holdMs.coerceIn(30, 2000))
                        1 -> svc.swipe(s.x, s.y, s.x2, s.y2, 600)
                        2 -> svc.dualTap(s.x, s.y, s.x2, s.y2)
                        3 -> svc.dragDrop(s.x, s.y, s.x2, s.y2, 500, 600)
                    }
                }
                Thread.sleep(if (ghost) 450 else 900)
            }
            handler.post {
                ringView?.visibility = View.GONE
                toHide.forEach { if (it != banner) it.visibility = View.VISIBLE }
                if (tapAddMode) banner?.visibility = View.VISIBLE
                pathView?.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun showRing(x: Int, y: Int) {
        handler.post {
            val ring = ringView ?: return@post
            val p = ring.layoutParams as WindowManager.LayoutParams
            p.x = x - Ui.dp(this, 28)
            p.y = y - Ui.dp(this, 28)
            ring.visibility = View.VISIBLE
            try { wm.updateViewLayout(ring, p) } catch (e: Exception) {}
        }
    }

    private fun refreshPaths() {
        pathView?.invalidate()
    }

    // ---------- done ----------

    private fun done() {
        val scriptId = Store.editScriptId(this)
        val script = scriptId?.let { Store.getScript(this, it) }
        if (script == null) {
            toast("Script পাওয়া যায়নি")
            cleanup(); stopSelf(); return
        }
        val old = script.steps.toList()
        val preserved = old.filter {
            it.type != StepType.TAP && it.type != StepType.SWIPE && it.type != StepType.DUAL_TAP &&
                it.type != StepType.DRAG_DROP
        }
        val svc = TapAccessibilityService.instance
        fun snap(x: Int, y: Int): IntArray {
            if (svc == null) return intArrayOf(x, y)
            return svc.snapCenterNear(x, y, 60) ?: intArrayOf(x, y)
        }
        script.steps.clear()
        specs.forEach { s ->
            when (s.kind) {
                0 -> {
                    var snapped = snap(s.x, s.y)
                    val info = svc?.nodeAt(snapped[0], snapped[1])
                    if (info == null && svc != null) {
                        val frame = ScreenCaptureService.snapshot()
                        if (frame != null) {
                            val blob = svc.centerOfColorBlob(frame, snapped[0], snapped[1])
                            if (blob != null) snapped = blob
                        }
                    }
                    script.steps.add(
                        Step(
                            StepType.TAP, snapped[0], snapped[1],
                            durationMs = s.holdMs, delayAfterMs = s.delayMs,
                            anchor = info?.getOrNull(0) ?: "",
                            anchorId = info?.getOrNull(5) ?: "",
                            relX = info?.getOrNull(6)?.toIntOrNull() ?: -1,
                            relY = info?.getOrNull(7)?.toIntOrNull() ?: -1
                        )
                    )
                }
                1 -> script.steps.add(
                    Step(StepType.SWIPE, s.x, s.y, s.x2, s.y2, durationMs = s.holdMs, delayAfterMs = s.delayMs)
                )
                3 -> script.steps.add(
                    Step(StepType.DRAG_DROP, s.x, s.y, s.x2, s.y2, durationMs = s.holdMs, delayAfterMs = s.delayMs)
                )
                2 -> script.steps.add(
                    Step(StepType.DUAL_TAP, s.x, s.y, s.x2, s.y2, delayAfterMs = s.delayMs)
                )
            }
        }
        script.steps.addAll(preserved)
        // Step thumbnails: a small crop around each tap point so the step
        // list in the app can show where every point is.
        try {
            val frame = ScreenCaptureService.snapshot()
            if (frame != null) {
                val dir = java.io.File(filesDir, "thumbs").apply { mkdirs() }
                specs.forEachIndexed { idx, s ->
                    if (s.kind != 0) return@forEachIndexed
                    val half = 60
                    val left = (s.x - half).coerceIn(0, (frame.width - 2 * half).coerceAtLeast(0))
                    val top = (s.y - half).coerceIn(0, (frame.height - 2 * half).coerceAtLeast(0))
                    val crop = Bitmap.createBitmap(frame, left, top, (2 * half).coerceAtMost(frame.width - left), (2 * half).coerceAtMost(frame.height - top))
                    val small = Bitmap.createScaledBitmap(crop, 72, 72, true)
                    java.io.FileOutputStream(java.io.File(dir, script.id + "_" + idx + ".png")).use {
                        small.compress(Bitmap.CompressFormat.PNG, 85, it)
                    }
                }
            }
        } catch (e: Exception) { /* thumbnails are best-effort */ }
        Store.upsert(this, script)
        Store.setSelectedId(this, script.id)
        Store.setEditDraft(this, script.id, null)
        toast("${script.steps.size}টি step সেভ হলো")
        cleanup()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        stopSelf()
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun cleanup() {
        allWindows.forEach { v -> try { wm.removeView(v) } catch (e: Exception) {} }
        allWindows.clear()
        markerViews.clear()
        toolbar = null
        panel = null
        panelChips = null
        banner = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
