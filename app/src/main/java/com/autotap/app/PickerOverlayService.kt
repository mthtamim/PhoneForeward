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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Element picker: a full-screen transparent layer. The user taps the real
 * element they want; the layer hides, the accessibility node at that point
 * is read, and a Text tap step (or plain Tap when the element has no label)
 * is appended to the script being edited. No typing needed.
 */
class PickerOverlayService : Service() {

    private var wm: WindowManager? = null
    private var layer: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var targetScriptId: String = ""
    private var mode: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetScriptId = intent?.getStringExtra(EXTRA_SCRIPT_ID) ?: ""
        mode = intent?.getStringExtra(EXTRA_MODE) ?: ""
        showLayer()
        return START_NOT_STICKY
    }

    private fun showLayer() {
        hideLayer()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("আগে Overlay permission দাও")
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(this@PickerOverlayService, 16), Ui.dp(this@PickerOverlayService, 48), Ui.dp(this@PickerOverlayService, 16), 0)
        }
        val banner = TextView(this).apply {
            text = if (mode == "color") "যে জায়গার রঙ বদল দেখবে, সেখানে আঙুল ছোঁয়াও" else if (mode == "hold") "যেখানে চেপে ধরবে, সেখানে আঙুল ছোঁয়াও" else "যে বাটন/লেখায় tap করতে চাও, সেটায় আঙুল ছোঁয়াও"
            setTextColor(Ui.TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@PickerOverlayService, 14), Ui.dp(this@PickerOverlayService, 10), Ui.dp(this@PickerOverlayService, 14), Ui.dp(this@PickerOverlayService, 10))
            background = Ui.rounded(this@PickerOverlayService, Ui.CARD, 12, stroke = true)
        }
        root.addView(banner)
        // Magnifier-style preview: while the finger moves, show which element
        // would be picked, so the user can confirm before lifting the finger.
        val preview = TextView(this).apply {
            setTextColor(Ui.BRIGHT)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@PickerOverlayService, 12), Ui.dp(this@PickerOverlayService, 6), Ui.dp(this@PickerOverlayService, 12), Ui.dp(this@PickerOverlayService, 6))
            background = Ui.rounded(this@PickerOverlayService, Ui.RAISED, 999, stroke = true)
            visibility = android.view.View.GONE
        }
        root.addView(preview)
        var lastPreviewAt = 0L
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastPreviewAt > 160) {
                        lastPreviewAt = now
                        val info = TapAccessibilityService.instance
                            ?.nodeAt(event.rawX.toInt(), event.rawY.toInt())
                        val label = info?.getOrNull(0)
                        preview.text = when {
                            mode == "color" -> "রঙের জায়গা: (${event.rawX.toInt()}, ${event.rawY.toInt()})"
                            info == null -> "এখানে element নেই - খালি tap হবে"
                            !label.isNullOrEmpty() -> "লুকানো নাম: \"$label\" (Text tap-এ এই নাম চলবে)"
                            else -> "নামহীন বাটন - coordinate tap হবে; চাইলে ছবি-tap ধাপ নাও"
                        }
                        preview.visibility = android.view.View.VISIBLE
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    hideLayer()
                    handler.postDelayed({ pick(x, y) }, 500)
                }
            }
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm?.addView(root, params)
            layer = root
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun pick(x: Int, y: Int) {
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            toast("আগে Accessibility চালু করো")
            stopSelf()
            return
        }
        val script = Store.getScripts(this).find { it.id == targetScriptId }
        if (script == null) {
            stopSelf()
            return
        }
        // Color-condition mode: sample the pixel color into a COLOR_IF step.
        if (mode == "colorif") {
            val frame = ScreenCaptureService.snapshot()
            if (frame == null) {
                toast("আগে capture চালু করে একবার script চালাতে হবে")
                stopSelf()
                return
            }
            val px = x.coerceIn(0, frame.width - 1)
            val py = y.coerceIn(0, frame.height - 1)
            val c = frame.getPixel(px, py)
            val hex = "#" + String.format("%06X", 0xFFFFFF and c)
            script.steps.add(Step(StepType.COLOR_IF, x = x, y = y, text = hex, timeoutSec = 5))
            Store.upsert(this, script)
            toast("রঙ-শর্ত step যোগ হয়েছে ($hex) - scriptে ফিরে সত্য/মিথ্যা ধাপ বসাও")
            stopSelf()
            return
        }
        // Point modes do not need a node: the raw tap point is the step.
        if (mode == "color") {
            script.steps.add(Step(StepType.COLOR_WAIT, x = x, y = y, timeoutSec = 30))
            Store.upsert(this, script)
            toast("রঙ বদল অপেক্ষা step যোগ হয়েছে ($x, $y)")
            stopSelf()
            return
        }
        if (mode == "hold") {
            script.steps.add(Step(StepType.HOLD_UNTIL, x = x, y = y, timeoutSec = 30))
            Store.upsert(this, script)
            toast("চেপে ধরা step যোগ হয়েছে - scriptে ফিরে শব্দটা বসাও")
            stopSelf()
            return
        }
        val info = svc.nodeAt(x, y)
        if (info == null) {
            toast("ওই জায়গায় কোনো element পাওয়া যায়নি")
            stopSelf()
            return
        }
        val label = info[0]
        val l = info[1].toIntOrNull() ?: x
        val t = info[2].toIntOrNull() ?: y
        val r = info[3].toIntOrNull() ?: x
        val b = info[4].toIntOrNull() ?: y
        val step = if (label.isNotEmpty()) {
            Step(StepType.TEXT_TAP, text = label, delayAfterMs = 1000)
        } else {
            Step(
                StepType.TAP, x = (l + r) / 2, y = (t + b) / 2, durationMs = 60, delayAfterMs = 1000,
                anchorId = info.getOrNull(5) ?: "",
                relX = info.getOrNull(6)?.toIntOrNull() ?: -1,
                relY = info.getOrNull(7)?.toIntOrNull() ?: -1
            )
        }
        script.steps.add(step)
        Store.upsert(this, script)
        toast("Step যোগ হয়েছে: ${step.describe()}")
        stopSelf()
    }

    private fun hideLayer() {
        try { layer?.let { wm?.removeView(it) } } catch (e: Exception) {}
        layer = null
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        hideLayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCRIPT_ID = "script_id"
        const val EXTRA_MODE = "mode"

        fun start(ctx: Context, scriptId: String, mode: String = "") {
            val intent = Intent(ctx, PickerOverlayService::class.java).apply {
                putExtra(EXTRA_SCRIPT_ID, scriptId)
                putExtra(EXTRA_MODE, mode)
            }
            ctx.startService(intent)
        }
    }
}
