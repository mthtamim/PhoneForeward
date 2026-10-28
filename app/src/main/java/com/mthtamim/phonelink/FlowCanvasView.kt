package com.mthtamim.phonelink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Flowchart canvas, v1.1.4 (port-based editor, Automate-style).
 *
 * Canvas zoom (0.5x-2.0x, pinch + buttons + fit) scales EVERYTHING,
 * including node boxes. On top of that each node has its OWN size:
 * the selected node shows a resize grip at its bottom-right corner;
 * dragging the grip scales that one box (0.6x-2.0x of the default
 * size) and the factor is stored as "s" in the node's JSON, so it
 * persists with the flow file. Old nodes without "s" read as 1.0.
 *
 * All geometry is in world coordinates; the canvas is scaled by
 * [zoom] at draw time and touch points are divided by [zoom].
 *
 * Ports (no Connect button/mode anymore): every node exposes
 * labeled ports — trigger GO (output), condition IN (input) +
 * YES/NO (outputs), action IN (input) + OK (output). Dragging
 * from an output port to another node's IN port creates the edge;
 * the port decides the branch (YES -> "yes", NO -> "no",
 * GO/OK -> "any"). Tapping a curve selects that edge
 * ([onEdgeSelected]); dragging a node moves it; the selected
 * node's corner grip resizes it. [onNodeDragStart] /
 * [onNodeResizeStart] fire once, before the first real change,
 * so the activity can snapshot for undo; [onNodeMoveEnd] fires
 * after a real move (for the "Moved block" snackbar).
 * Double-tapping a node (two clean taps on the SAME node within
 * 300ms, no drag, no port-drag, no resize) fires
 * [onNodeDoubleTap] — the activity opens the node dialog.
 * There is NO bottom panel anymore: single tap only selects.
 */
class FlowCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var nodes: JSONArray = JSONArray()
    var edges: JSONArray = JSONArray()
    var selectedId: String? = null
    var snapToGrid: Boolean = false
    var highlightId: String? = null
    var selectedEdge: JSONObject? = null
    var onNodeSelected: ((JSONObject?) -> Unit)? = null
    var onNodeDoubleTap: ((JSONObject) -> Unit)? = null
    var onPortConnect: ((String, String, String) -> Unit)? = null
    var onEdgeSelected: ((JSONObject) -> Unit)? = null
    var onNodeDragStart: (() -> Unit)? = null
    var onNodeResizeStart: (() -> Unit)? = null
    var onNodeMoveEnd: ((JSONObject) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    var zoom: Float = 1f
        private set

    private val density = context.resources.displayMetrics.density
    private val nodeW = 230f * density
    private val nodeH = 92f * density
    private val gripDp = 24f * density
    private val portHit = 24f * density
    private val edgeHit = 12f * density

    private val paintNode = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintEdge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGrip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGripBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPort = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPortText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintTemp = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dragId: String? = null
    private var dragDx = 0f
    private var dragDy = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragStarted = false
    private var resizeId: String? = null
    private var resizeStarted = false
    private var portDragFrom: String? = null
    private var portDragPort: String? = null
    private var portDragX = 0f
    private var portDragY = 0f
    private var lastTapUpTime = 0L
    private var lastTapNodeId: String? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        }
    )

    init {
        paintNode.style = Paint.Style.FILL
        paintBorder.style = Paint.Style.STROKE
        paintEdge.style = Paint.Style.STROKE
        paintEdge.strokeWidth = 3f * density
        // v2.25.1: canvas text/sub/grip/grid/temp colours resolve
        // from the active theme (view is created after the activity
        // applies its theme, and recreated on theme change). Emerald
        // night values are identical to the old literals: text
        // #EAF4EE, sub #8FA79B, grip #10B981, grid #2A3336.
        paintText.color = ThemeColors.onSurface(context)
        paintText.textSize = 15f * density
        paintSub.color = ThemeColors.onSurfaceVariant(context)
        paintSub.textSize = 12f * density
        paintGrip.style = Paint.Style.FILL
        paintGrip.color = ThemeColors.primary(context)
        paintGripBorder.style = Paint.Style.STROKE
        paintGripBorder.color = Color.WHITE
        paintGripBorder.strokeWidth = 2f * density
        paintGrid.style = Paint.Style.FILL
        paintGrid.color = ThemeColors.outline(context)
        paintPort.style = Paint.Style.FILL
        paintPortText.textSize = 11f * density
        paintTemp.style = Paint.Style.STROKE
        paintTemp.strokeWidth = 2f * density
        paintTemp.color = ThemeColors.onSurface(context)
    }

    fun setZoom(value: Float) {
        zoom = value.coerceIn(0.5f, 2.0f)
        invalidate()
        onZoomChanged?.invoke(zoom)
    }

    /** Zooms out just enough that every node is visible (never zooms in past 1x). */
    fun fitToScreen() {
        if (nodes.length() == 0) {
            return
        }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val r = nodeRect(node)
            left = min(left, r.left)
            top = min(top, r.top)
            right = max(right, r.right)
            bottom = max(bottom, r.bottom)
        }
        if (left == Float.MAX_VALUE) {
            return
        }
        val contentW = max(1f, right - left)
        val contentH = max(1f, bottom - top)
        val fit = min(width / contentW, height / contentH)
        setZoom(min(1.0f, fit))
    }

    /** Per-node scale factor from the node's JSON ("s", default 1.0, clamped). */
    private fun nodeScale(node: JSONObject): Float {
        return node.optDouble("s", 1.0).toFloat().coerceIn(0.6f, 2.0f)
    }

    private fun borderColor(kind: String): Int {
        return when (kind) {
            "t" -> Color.parseColor("#10B981")
            "c" -> Color.parseColor("#F5A623")
            else -> Color.parseColor("#2FD68F")
        }
    }

    /** Category border color by node type; falls back to borderColor(kind). */
    private fun borderColorFor(node: JSONObject): Int {
        val type = node.optString("type")
        return when {
            type == "time_at" || type == "time_interval" || type == "time_at_days" ||
                type == "date_at" || type == "sunrise" || type == "sunset" ||
                type == "stopwatch" -> Color.parseColor("#2196F3")
            type.startsWith("battery_") -> Color.parseColor("#10B981")
            type.startsWith("wifi_") || type.startsWith("bt_") ||
                type.startsWith("airplane_") || type.startsWith("mobile_data_") ||
                type.startsWith("hotspot_") || type.startsWith("vpn_") ||
                type.startsWith("roaming_") -> Color.parseColor("#00BCD4")
            type.startsWith("torch_") || type == "vibrate" ||
                type == "flash_pattern" || type.startsWith("brightness_") ||
                type == "screen_timeout_set" || type == "auto_rotate_set" ||
                type == "dnd_set" || type == "volume_set" || type == "mode_set" ||
                type == "ringer" || type == "keep_awake" ||
                type == "lock_screen" || type == "wake_screen" ->
                Color.parseColor("#795548")
            type.startsWith("screen_") || type.startsWith("charging_") ||
                type.startsWith("volume_") || type.startsWith("storage_") ||
                type.startsWith("orientation_") || type == "pick_up" ||
                type == "split_screen" -> Color.parseColor("#8FA79B")
            type.startsWith("app_") -> Color.parseColor("#9C27B0")
            type.startsWith("sms_") || type.startsWith("notif_") ||
                type.startsWith("whatsapp_") || type.startsWith("telegram_") ->
                Color.parseColor("#FF9800")
            type.startsWith("geofence_") || type.startsWith("gps_") ->
                Color.parseColor("#4CAF50")
            type.startsWith("music_") || type.startsWith("media_") ||
                type == "play_sound" || type.startsWith("speak_") ||
                type.startsWith("record_") || type == "take_photo" ->
                Color.parseColor("#E91E63")
            type == "local_notify" || type == "toast_local" ||
                type == "rich_notify" -> Color.parseColor("#FFC107")
            type == "write_file" || type == "read_file" ||
                type.startsWith("file_") || type == "delete_file" ->
                Color.parseColor("#607D8B")
            type.startsWith("loop_") || type == "for_each" ||
                type == "call_flow" || type == "flow_stop" || type == "fork" ||
                type == "stop_flow" || type == "set_local" ||
                type.startsWith("list_") || type.startsWith("dict_") ||
                type.startsWith("text_") || type == "random_int" ->
                Color.parseColor("#3F51B5")
            type.startsWith("peer_") || type.startsWith("command_") ||
                type.startsWith("mqtt_") -> Color.parseColor("#009688")
            type.startsWith("timezone_") || type.startsWith("locale_") ||
                type.startsWith("calendar_") || type.startsWith("weather") ||
                type == "webhook" || type.startsWith("qr_") ->
                Color.parseColor("#9E9E9E")
            else -> borderColor(node.optString("kind"))
        }
    }

    private fun portColor(port: String): Int {
        return when (port) {
            "GO" -> Color.parseColor("#2196F3")
            "YES" -> Color.parseColor("#10B981")
            "NO" -> Color.parseColor("#F87171")
            "OK" -> Color.parseColor("#2FD68F")
            else -> Color.parseColor("#8FA79B")
        }
    }

    private fun nodeRect(node: JSONObject): RectF {
        val s = nodeScale(node)
        val x = node.optDouble("x", 0.0).toFloat()
        val y = node.optDouble("y", 0.0).toFloat()
        return RectF(x, y, x + nodeW * s, y + nodeH * s)
    }

    private fun nodeById(id: String): JSONObject? {
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("id") == id) {
                return n
            }
        }
        return null
    }

    /** Output ports of a node: (port name, position). */
    private fun outputPorts(node: JSONObject): List<Pair<String, PointF>> {
        val r = nodeRect(node)
        // v1.2.0: loop_count / for_each are YES/NO branch nodes even
        // though they live in the action kind (their walker branch is
        // yes while runs/items remain, no when done).
        val type = node.optString("type")
        if (type == "loop_count" || type == "for_each") {
            return listOf(
                "YES" to PointF(r.right, r.top + (r.height() * 0.30f)),
                "NO" to PointF(r.right, r.top + (r.height() * 0.70f))
            )
        }
        return when (node.optString("kind")) {
            "t" -> listOf("GO" to PointF(r.right, r.centerY()))
            "c" -> listOf(
                "YES" to PointF(r.right, r.top + (r.height() * 0.30f)),
                "NO" to PointF(r.right, r.top + (r.height() * 0.70f))
            )
            else -> listOf("OK" to PointF(r.right, r.centerY()))
        }
    }

    /** Input port of a node, or null for triggers (entry blocks have no IN). */
    private fun inputPort(node: JSONObject): PointF? {
        if (node.optString("kind") == "t") {
            return null
        }
        val r = nodeRect(node)
        return PointF(r.left, r.centerY())
    }

    /** The port an edge starts from (by branch) and the port it lands on. */
    private fun edgePorts(edge: JSONObject): Pair<PointF, PointF>? {
        val from = nodeById(edge.optString("from")) ?: return null
        val to = nodeById(edge.optString("to")) ?: return null
        val branch = edge.optString("branch", "any")
        val start = when (branch) {
            "yes" -> outputPorts(from).firstOrNull { it.first == "YES" }?.second
            "no" -> outputPorts(from).firstOrNull { it.first == "NO" }?.second
            else -> outputPorts(from).firstOrNull()?.second
        } ?: PointF(nodeRect(from).right, nodeRect(from).centerY())
        val end = inputPort(to)
            ?: PointF(nodeRect(to).left, nodeRect(to).centerY())
        return start to end
    }

    private fun edgeColor(edge: JSONObject): Int {
        return when (edge.optString("branch", "any")) {
            "yes" -> Color.parseColor("#10B981")
            "no" -> Color.parseColor("#F87171")
            "any" -> {
                val from = nodeById(edge.optString("from"))
                if (from != null && from.optString("kind") == "t") {
                    Color.parseColor("#2196F3")
                } else {
                    Color.parseColor("#8FA79B")
                }
            }
            else -> Color.parseColor("#8FA79B")
        }
    }

    /** Cubic bezier from p0 to p3 with horizontal control offsets. */
    private fun bezierPath(p0: PointF, p3: PointF): Path {
        val dx = max(40f * density, kotlin.math.abs(p3.x - p0.x) * 0.5f)
        val path = Path()
        path.moveTo(p0.x, p0.y)
        path.cubicTo(p0.x + dx, p0.y, p3.x - dx, p3.y, p3.x, p3.y)
        return path
    }

    /** Sampled points along the same bezier (for hit testing). */
    private fun bezierPoints(p0: PointF, p3: PointF): List<PointF> {
        val dx = max(40f * density, kotlin.math.abs(p3.x - p0.x) * 0.5f)
        val p1 = PointF(p0.x + dx, p0.y)
        val p2 = PointF(p3.x - dx, p3.y)
        val pts = ArrayList<PointF>()
        for (step in 0..24) {
            val t = step / 24f
            val mt = 1f - t
            val x = mt * mt * mt * p0.x + 3f * mt * mt * t * p1.x +
                3f * mt * t * t * p2.x + t * t * t * p3.x
            val y = mt * mt * mt * p0.y + 3f * mt * mt * t * p1.y +
                3f * mt * t * t * p2.y + t * t * t * p3.y
            pts.add(PointF(x, y))
        }
        return pts
    }

    /** True when the world point is on the selected node's resize grip (generous hit area). */
    private fun hitGrip(node: JSONObject, wx: Float, wy: Float): Boolean {
        val s = nodeScale(node)
        val r = nodeRect(node)
        val g = gripDp * s
        val pad = 8f * density
        return wx >= r.right - g - pad && wx <= r.right + pad &&
            wy >= r.bottom - g - pad && wy <= r.bottom + pad
    }

    private fun hitNode(wx: Float, wy: Float): JSONObject? {
        for (i in nodes.length() - 1 downTo 0) {
            val node = nodes.optJSONObject(i) ?: continue
            if (nodeRect(node).contains(wx, wy)) {
                return node
            }
        }
        return null
    }

    /** Output port whose generous hit area contains the world point, or null. */
    private fun hitOutputPort(wx: Float, wy: Float): Pair<JSONObject, String>? {
        for (i in nodes.length() - 1 downTo 0) {
            val node = nodes.optJSONObject(i) ?: continue
            for ((name, pt) in outputPorts(node)) {
                if (kotlin.math.abs(wx - pt.x) <= portHit &&
                    kotlin.math.abs(wy - pt.y) <= portHit
                ) {
                    return node to name
                }
            }
        }
        return null
    }

    /** Input port whose generous hit area contains the world point, or null. */
    private fun hitInputPort(wx: Float, wy: Float): JSONObject? {
        for (i in nodes.length() - 1 downTo 0) {
            val node = nodes.optJSONObject(i) ?: continue
            val pt = inputPort(node) ?: continue
            if (kotlin.math.abs(wx - pt.x) <= portHit &&
                kotlin.math.abs(wy - pt.y) <= portHit
            ) {
                return node
            }
        }
        return null
    }

    private fun hitEdge(wx: Float, wy: Float): JSONObject? {
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            val (p0, p3) = edgePorts(e) ?: continue
            val pts = bezierPoints(p0, p3)
            for (j in 0 until pts.size - 1) {
                val a = pts[j]
                val b = pts[j + 1]
                if (distToSegment(wx, wy, a.x, a.y, b.x, b.y) <= edgeHit) {
                    return e
                }
            }
        }
        return null
    }

    private fun distToSegment(
        px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0f) {
            0f
        } else {
            (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0f, 1f)
        }
        val cx = x1 + t * dx
        val cy = y1 + t * dy
        val ddx = px - cx
        val ddy = py - cy
        return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(zoom, zoom)

        // Dotted grid under everything (24dp world spacing).
        val worldW = width / zoom
        val worldH = height / zoom
        val step = 24f * density
        var gx = 0f
        while (gx <= worldW) {
            var gy = 0f
            while (gy <= worldH) {
                canvas.drawCircle(gx, gy, 1.5f * density, paintGrid)
                gy += step
            }
            gx += step
        }

        val rects = HashMap<String, RectF>()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            rects[n.optString("id")] = nodeRect(n)
        }

        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            val (p0, p3) = edgePorts(e) ?: continue
            val color = edgeColor(e)
            paintEdge.color = color
            val isSel = selectedEdge != null &&
                selectedEdge!!.optString("from") == e.optString("from") &&
                selectedEdge!!.optString("to") == e.optString("to") &&
                selectedEdge!!.optString("branch") == e.optString("branch")
            paintEdge.strokeWidth = if (isSel) 5f * density else 3f * density
            canvas.drawPath(bezierPath(p0, p3), paintEdge)
            drawArrow(canvas, p3)
        }

        // Temp curve while dragging from an output port.
        val pf = portDragFrom
        if (pf != null) {
            val from = nodeById(pf)
            val port = portDragPort
            if (from != null && port != null) {
                val start = outputPorts(from).firstOrNull { it.first == port }?.second
                if (start != null) {
                    val finger = PointF(portDragX, portDragY)
                    canvas.drawPath(bezierPath(start, finger), paintTemp)
                }
            }
        }

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val r = rects[node.optString("id")] ?: continue
            val s = nodeScale(node)
            // v2.25.1: node fill follows the theme secondaryContainer
            // (Emerald night #1C2326 = the old literal, identical).
            paintNode.color = ThemeColors.secondaryContainer(context)
            canvas.drawRoundRect(r, 18f * density * s, 18f * density * s, paintNode)
            val isHighlighted = node.optString("id") == highlightId
            paintBorder.color = if (isHighlighted) {
                Color.parseColor("#FFC107")
            } else {
                borderColorFor(node)
            }
            paintBorder.strokeWidth = if (node.optString("id") == selectedId || isHighlighted) {
                6f * density * s
            } else {
                3f * density * s
            }
            canvas.drawRoundRect(r, 18f * density * s, 18f * density * s, paintBorder)
            paintText.textSize = 15f * density * s
            paintSub.textSize = 12f * density * s
            val pad = 12f * density * s
            val maxText = nodeW * s - 24f * density * s
            val title = FlowFlow.title(context, node)
            val param = node.optString("param")
            canvas.drawText(
                ellipsize(title, paintText, maxText),
                r.left + pad, r.top + 30f * density * s, paintText
            )
            canvas.drawText(
                ellipsize(param, paintSub, maxText),
                r.left + pad, r.top + 56f * density * s, paintSub
            )
            val kindLabel = when (node.optString("kind")) {
                "t" -> context.getString(R.string.flow_kind_trigger)
                "c" -> context.getString(R.string.flow_kind_condition)
                else -> context.getString(R.string.flow_kind_action)
            }
            canvas.drawText(
                kindLabel, r.left + pad, r.top + 80f * density * s, paintSub
            )
            val note = node.optString("note")
            if (note.isNotEmpty()) {
                canvas.drawText(
                    ellipsize(note, paintSub, maxText),
                    r.left + pad, r.bottom + 16f * density * s, paintSub
                )
            }
            // Ports: circles on the edges + tiny labels outside the box.
            paintPortText.textSize = 11f * density * s
            val pr = 8f * density * s
            for ((name, pt) in outputPorts(node)) {
                paintPort.color = portColor(name)
                canvas.drawCircle(pt.x, pt.y, pr, paintPort)
                paintPortText.color = portColor(name)
                paintPortText.textAlign = Paint.Align.LEFT
                canvas.drawText(name, pt.x + pr + 3f * density * s, pt.y + 4f * density * s, paintPortText)
            }
            val inPt = inputPort(node)
            if (inPt != null) {
                paintPort.color = portColor("IN")
                canvas.drawCircle(inPt.x, inPt.y, pr, paintPort)
                paintPortText.color = portColor("IN")
                paintPortText.textAlign = Paint.Align.RIGHT
                canvas.drawText("IN", inPt.x - pr - 3f * density * s, inPt.y + 4f * density * s, paintPortText)
            }
            if (node.optString("id") == selectedId) {
                val g = gripDp * s
                val grip = RectF(r.right - g, r.bottom - g, r.right, r.bottom)
                canvas.drawRect(grip, paintGrip)
                canvas.drawRect(grip, paintGripBorder)
            }
        }

        canvas.restore()
    }

    /**
     * Arrowhead at the target port. The bezier's end control point
     * sits left of the target at the same height, so the curve
     * always enters the port horizontally from the left — the
     * arrow points +x. Uses the caller's paintEdge color/width.
     */
    private fun drawArrow(canvas: Canvas, p3: PointF) {
        val angle = 0.0
        val len = 18f * density
        val spread = 0.45
        val ax = p3.x - len * cos(angle).toFloat()
        val ay = p3.y - len * sin(angle).toFloat()
        val p1x = (ax + len * 0.5f * cos(angle + spread).toFloat())
        val p1y = (ay + len * 0.5f * sin(angle + spread).toFloat())
        val p2x = (ax + len * 0.5f * cos(angle - spread).toFloat())
        val p2y = (ay + len * 0.5f * sin(angle - spread).toFloat())
        canvas.drawLine(p3.x, p3.y, p1x, p1y, paintEdge)
        canvas.drawLine(p3.x, p3.y, p2x, p2y, paintEdge)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty()) {
            return ""
        }
        if (paint.measureText(text) <= maxWidth) {
            return text
        }
        var cut = text.length
        while (cut > 1 && paint.measureText(text.substring(0, cut) + "…") > maxWidth) {
            cut--
        }
        return text.substring(0, cut) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragId = null
            resizeId = null
            portDragFrom = null
            return true
        }
        val wx = event.x / zoom
        val wy = event.y / zoom
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                dragStarted = false
                resizeStarted = false
                selectedEdge = null
                val sel = selectedId?.let { nodeById(it) }
                if (sel != null && hitGrip(sel, wx, wy)) {
                    resizeId = sel.optString("id")
                    lastTapNodeId = null
                    return true
                }
                val portHitRes = hitOutputPort(wx, wy)
                if (portHitRes != null) {
                    portDragFrom = portHitRes.first.optString("id")
                    portDragPort = portHitRes.second
                    portDragX = wx
                    portDragY = wy
                    lastTapNodeId = null
                    return true
                }
                val node = hitNode(wx, wy)
                if (node == null) {
                    selectedId = null
                    lastTapNodeId = null
                    onNodeSelected?.invoke(null)
                    invalidate()
                    return true
                }
                val id = node.optString("id")
                selectedId = id
                dragId = id
                val r = nodeRect(node)
                dragDx = wx - r.left
                dragDy = wy - r.top
                onNodeSelected?.invoke(node)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pf = portDragFrom
                if (pf != null) {
                    portDragX = wx
                    portDragY = wy
                    invalidate()
                    return true
                }
                val rid = resizeId
                if (rid != null) {
                    if (!resizeStarted) {
                        resizeStarted = true
                        onNodeResizeStart?.invoke()
                    }
                    val node = nodeById(rid)
                    if (node != null) {
                        val nx = node.optDouble("x", 0.0).toFloat()
                        val s = ((wx - nx) / nodeW).coerceIn(0.6f, 2.0f)
                        node.put("s", s.toDouble())
                        invalidate()
                    }
                    return true
                }
                val did = dragId ?: return true
                if (kotlin.math.abs(event.x - downX) > 8f * density ||
                    kotlin.math.abs(event.y - downY) > 8f * density
                ) {
                    moved = true
                    lastTapNodeId = null
                }
                if (!moved) {
                    return true
                }
                if (!dragStarted) {
                    dragStarted = true
                    onNodeDragStart?.invoke()
                }
                val node = FlowFlow.nodeByIdFromArray(nodes, did) ?: return true
                val s = nodeScale(node)
                val worldW = width / zoom
                val worldH = height / zoom
                var nx = wx - dragDx
                var ny = wy - dragDy
                if (snapToGrid) {
                    val step = 24f * density
                    nx = (nx / step).roundToInt() * step
                    ny = (ny / step).roundToInt() * step
                }
                if (nx < 0f) {
                    nx = 0f
                }
                if (ny < 0f) {
                    ny = 0f
                }
                if (nx > worldW - nodeW * s) {
                    nx = (worldW - nodeW * s).coerceAtLeast(0f)
                }
                if (ny > worldH - nodeH * s) {
                    ny = (worldH - nodeH * s).coerceAtLeast(0f)
                }
                node.put("x", nx.toDouble())
                node.put("y", ny.toDouble())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pf = portDragFrom
                val pp = portDragPort
                if (pf != null && pp != null) {
                    portDragFrom = null
                    portDragPort = null
                    val target = hitInputPort(wx, wy)
                    if (target != null && target.optString("id") != pf) {
                        onPortConnect?.invoke(pf, pp, target.optString("id"))
                    }
                    invalidate()
                    return true
                }
                val rid = resizeId
                resizeId = null
                resizeStarted = false
                if (rid != null) {
                    return true
                }
                val did = dragId
                dragId = null
                if (did != null && moved) {
                    val node = FlowFlow.nodeByIdFromArray(nodes, did)
                    if (node != null) {
                        onNodeMoveEnd?.invoke(node)
                    }
                    return true
                }
                if (did != null && !moved) {
                    val node = FlowFlow.nodeByIdFromArray(nodes, did)
                    val now = SystemClock.uptimeMillis()
                    if (node != null && lastTapNodeId == did &&
                        now - lastTapUpTime < 300L
                    ) {
                        lastTapNodeId = null
                        lastTapUpTime = 0L
                        onNodeDoubleTap?.invoke(node)
                    } else {
                        lastTapNodeId = did
                        lastTapUpTime = now
                    }
                    return true
                }
                if (did == null) {
                    val edge = hitEdge(wx, wy)
                    if (edge != null) {
                        selectedEdge = edge
                        invalidate()
                        onEdgeSelected?.invoke(edge)
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragId = null
                resizeId = null
                portDragFrom = null
                portDragPort = null
                return true
            }
            else -> {
                return super.onTouchEvent(event)
            }
        }
    }
}
