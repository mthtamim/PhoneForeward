package com.autotap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands and eyes of the app.
 * Hands: taps, swipes, pinches, dual taps, freehand paths, global buttons.
 * Eyes: reads the foreground app's UI node tree on demand (only while a
 * user-started script is running search steps, or the element picker is
 * used) so scripts can find a word / number / element and tap it.
 * Also hosts two triggers: a chosen app opening (per-script trigger) and a
 * Volume-Down double-press to start/stop the selected script.
 */
class TapAccessibilityService : AccessibilityService() {

    private var lastVolDownMs = 0L
    private val lastAppTrigger = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (RunnerService.running) return
            // App-open trigger: a script bound to this package starts itself.
            try {
                val now = System.currentTimeMillis()
                for (script in Store.getScripts(this)) {
                    if (script.triggerPackage == pkg && script.steps.isNotEmpty()) {
                        val last = lastAppTrigger[script.id] ?: 0L
                        if (now - last > 15000) {
                            lastAppTrigger[script.id] = now
                            RunnerService.start(this, script.id)
                        }
                    }
                }
            } catch (e: Exception) {
                // Triggers must never crash the service.
            }
        }
    }

    override fun onInterrupt() {}

    /** Volume-Down double-press (within 800ms) toggles the selected script. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            val now = System.currentTimeMillis()
            if (now - lastVolDownMs < 800) {
                lastVolDownMs = 0L
                if (RunnerService.running) {
                    RunnerService.sendCmd(this, RunnerService.CMD_STOP)
                } else {
                    Store.selectedScript(this)?.let { s ->
                        if (s.steps.isNotEmpty()) RunnerService.start(this, s.id)
                    }
                }
                return true
            }
            lastVolDownMs = now
        }
        return super.onKeyEvent(event)
    }

    /** Calibration offset measured by the calibration wizard. */
    private fun cx(x: Int): Int = x + Store.calibDx(this)
    private fun cy(y: Int): Int = y + Store.calibDy(this)

    fun tap(x: Int, y: Int, holdMs: Long): Boolean {
        val ax = cx(x); val ay = cy(y)
        if (ShizukuTap.tryTap(this, ax, ay, holdMs)) return true
        val path = Path().apply { moveTo(ax.toFloat(), ay.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs.coerceIn(1, 10000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        if (ShizukuTap.trySwipe(this, cx(x1), cy(y1), cx(x2), cy(y2), durationMs)) return true
        val path = Path().apply {
            moveTo(cx(x1).toFloat(), cy(y1).toFloat())
            lineTo(cx(x2).toFloat(), cy(y2).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Human-like curved swipe: a quadratic bezier bowed to one side. */
    fun swipeCurved(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val ax = cx(x1); val ay = cy(y1); val bx = cx(x2); val by = cy(y2)
        val mx = (ax + bx) / 2f; val my = (ay + by) / 2f
        val dx = (bx - ax).toFloat(); val dy = (by - ay).toFloat()
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val bow = len * 0.12f
        val qx = mx - dy / len * bow
        val qy = my + dx / len * bow
        val path = Path().apply {
            moveTo(ax.toFloat(), ay.toFloat())
            quadTo(qx, qy, bx.toFloat(), by.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Press at (x1, y1), hold, drag to (x2, y2), release - one drag & drop. */
    fun dragDrop(x1: Int, y1: Int, x2: Int, y2: Int, holdMs: Long, moveMs: Long): Boolean {
        val ax = cx(x1); val ay = cy(y1); val bx = cx(x2); val by = cy(y2)
        val holdPath = Path().apply { moveTo(ax.toFloat(), ay.toFloat()) }
        val movePath = Path().apply {
            moveTo(ax.toFloat(), ay.toFloat())
            lineTo(bx.toFloat(), by.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(holdPath, 0, holdMs.coerceIn(100, 3000), true))
            .addStroke(GestureDescription.StrokeDescription(movePath, 0, moveMs.coerceIn(100, 5000), false))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Three parallel fingers swiping vertically together. */
    fun threeFingerSwipe(centerX: Int, y1: Int, y2: Int, durationMs: Long): Boolean {
        val dur = durationMs.coerceIn(50, 5000)
        val builder = GestureDescription.Builder()
        for (off in intArrayOf(-90, 0, 90)) {
            val path = Path().apply {
                moveTo(cx(centerX + off).toFloat(), cy(y1).toFloat())
                lineTo(cx(centerX + off).toFloat(), cy(y2).toFloat())
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, dur))
        }
        return dispatchGesture(builder.build(), null, null)
    }

    /** Two fingers tapping two points at the same moment. */
    fun dualTap(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val p1 = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
        val p2 = Path().apply { moveTo(x2.toFloat(), y2.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 60))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pinch(cx: Int, cy: Int, startDistPx: Int, endDistPx: Int, durationMs: Long): Boolean {
        val s = (startDistPx.coerceAtLeast(20)) / 2
        val e = (endDistPx.coerceAtLeast(10)) / 2
        val p1 = Path().apply {
            moveTo((cx - s).toFloat(), cy.toFloat())
            lineTo((cx - e).toFloat(), cy.toFloat())
        }
        val p2 = Path().apply {
            moveTo((cx + s).toFloat(), cy.toFloat())
            lineTo((cx + e).toFloat(), cy.toFloat())
        }
        val dur = durationMs.coerceIn(50, 5000)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, dur))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, dur))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Replays a recorded freehand path: points are [x, y] pairs in order. */
    fun tracePath(points: List<IntArray>, durationMs: Long): Boolean {
        if (points.size < 2) return false
        val path = Path().apply {
            moveTo(points[0][0].toFloat(), points[0][1].toFloat())
            for (i in 1 until points.size) lineTo(points[i][0].toFloat(), points[i][1].toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 10000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** System buttons via accessibility global actions. */
    fun globalAction(code: Int): Boolean = try {
        when (code) {
            SystemAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SystemAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    } catch (e: Exception) { false }

    /** Types text into the currently focused editable field, if any. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) { false }
    }

    fun currentPackage(): String = try {
        rootInActiveWindow?.packageName?.toString() ?: ""
    } catch (e: Exception) { "" }

    private fun eachNode(visit: (AccessibilityNodeInfo) -> Unit) {
        val root = rootInActiveWindow ?: return
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            visit(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
    }

    fun findText(query: String, regionPreset: Int): IntArray? {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var best: Rect? = null
        eachNode { node ->
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            if (label.isNotEmpty() && label.lowercase().contains(q)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0 &&
                    Region.contains(regionPreset, w, h, r.centerX(), r.centerY())
                ) {
                    val cur = best
                    if (cur == null || (r.width() * r.height()) < (cur.width() * cur.height())) best = r
                }
            }
        }
        val b = best ?: return null
        return intArrayOf(b.centerX(), b.centerY())
    }

    /**
     * Finds a number on screen. If [label] is non-empty, only nodes whose
     * text contains the label count; the first integer in the text wins.
     */
    fun findNumber(label: String, regionPreset: Int): Int? {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val q = label.trim().lowercase()
        var found: Int? = null
        val numRegex = Regex("\\d[\\d,]*")
        eachNode { node ->
            if (found != null) return@eachNode
            val text = node.text?.toString() ?: return@eachNode
            if (text.isEmpty()) return@eachNode
            if (q.isNotEmpty() && !text.lowercase().contains(q)) return@eachNode
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!Region.contains(regionPreset, w, h, r.centerX(), r.centerY())) return@eachNode
            val m = numRegex.find(text) ?: return@eachNode
            found = m.value.replace(",", "").toIntOrNull()
        }
        return found
    }

    /**
     * Element picker + snap support: the label and bounds of the smallest
     * node containing point (x, y), or null when nothing meaningful is there.
     * Returns array: [label, left, top, right, bottom] (numbers as strings).
     */
    fun nodeAt(x: Int, y: Int): Array<String>? {
        var bestRect: Rect? = null
        var bestLabel = ""
        var bestId = ""
        eachNode { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && x in r.left..r.right && y in r.top..r.bottom) {
                val cur = bestRect
                if (cur == null || (r.width() * r.height()) < (cur.width() * cur.height())) {
                    bestRect = r
                    bestLabel = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    bestId = node.viewIdResourceName ?: ""
                }
            }
        }
        val r = bestRect ?: return null
        val relX = if (r.width() > 0) ((x - r.left) * 1000 / r.width()).coerceIn(0, 1000) else -1
        val relY = if (r.height() > 0) ((y - r.top) * 1000 / r.height()).coerceIn(0, 1000) else -1
        return arrayOf(
            bestLabel, r.left.toString(), r.top.toString(), r.right.toString(), r.bottom.toString(),
            bestId, relX.toString(), relY.toString()
        )
    }

    /** Finds an element by its resource id; returns a point at the stored
     *  relative position inside it (per-mille), or its center. */
    fun findByViewId(viewId: String, relX: Int, relY: Int): IntArray? {
        if (viewId.isEmpty()) return null
        var best: Rect? = null
        eachNode { node ->
            val id = node.viewIdResourceName ?: return@eachNode
            if (id == viewId || id.endsWith(":id/" + viewId.substringAfterLast('/'))) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val cur = best
                    if (cur == null || (r.width() * r.height()) < (cur.width() * cur.height())) best = r
                }
            }
        }
        val b = best ?: return null
        val px = if (relX in 0..1000) b.left + b.width() * relX / 1000 else b.centerX()
        val py = if (relY in 0..1000) b.top + b.height() * relY / 1000 else b.centerY()
        return intArrayOf(px, py)
    }

    /** Image-based centering for canvas/game buttons with no accessibility
     *  node: grows a box around (x, y) while pixels stay similar in color. */
    fun centerOfColorBlob(frame: android.graphics.Bitmap, x: Int, y: Int): IntArray? {
        val px = x.coerceIn(0, frame.width - 1)
        val py = y.coerceIn(0, frame.height - 1)
        val base = frame.getPixel(px, py)
        fun similar(c: Int): Boolean {
            val dr = kotlin.math.abs(android.graphics.Color.red(c) - android.graphics.Color.red(base))
            val dg = kotlin.math.abs(android.graphics.Color.green(c) - android.graphics.Color.green(base))
            val db = kotlin.math.abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(base))
            return dr + dg + db < 90
        }
        var left = px; var right = px; var top = py; var bottom = py
        val cap = 500
        var grew = true
        var guard = 0
        while (grew && guard < cap) {
            grew = false; guard++
            if (left > 0 && similar(frame.getPixel(left - 1, py))) { left--; grew = true }
            if (right < frame.width - 1 && similar(frame.getPixel(right + 1, py))) { right++; grew = true }
            if (top > 0 && similar(frame.getPixel(px, top - 1))) { top--; grew = true }
            if (bottom < frame.height - 1 && similar(frame.getPixel(px, bottom + 1))) { bottom++; grew = true }
        }
        if (right - left < 8 || bottom - top < 8) return null
        return intArrayOf((left + right) / 2, (top + bottom) / 2)
    }

    /** Snap support for the point editor: center of the nearest node within maxDist px of (x, y). */
    fun snapCenterNear(x: Int, y: Int, maxDist: Int): IntArray? {
        var bestDist = maxDist.toDouble()
        var best: IntArray? = null
        eachNode { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return@eachNode
            val cx = r.centerX(); val cy = r.centerY()
            val d = kotlin.math.hypot((cx - x).toDouble(), (cy - y).toDouble())
            if (d < bestDist) {
                bestDist = d
                best = intArrayOf(cx, cy)
            }
        }
        return best
    }

    companion object {
        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
