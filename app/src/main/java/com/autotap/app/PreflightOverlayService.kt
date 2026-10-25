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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pre-flight check: while the user is on the target app's screen, one tap
 * checks every search step of the selected script against what is on screen
 * right now and lists which steps would find their target and which would
 * not - before a real run wastes time on a broken script.
 */
class PreflightOverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var resultView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showPanel()
        return START_NOT_STICKY
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showPanel() {
        hidePanel()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "আগে Overlay permission দাও", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@PreflightOverlayService, Ui.CARD, 18, stroke = true)
            setPadding(Ui.dp(this@PreflightOverlayService, 14), Ui.dp(this@PreflightOverlayService, 10),
                Ui.dp(this@PreflightOverlayService, 14), Ui.dp(this@PreflightOverlayService, 10))
        }
        layout.addView(TextView(this).apply {
            text = "Pre-flight check"
            setTextColor(Ui.TEXT); textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        })
        resultView = TextView(this).apply {
            text = "টার্গেট অ্যাপের স্ক্রিনে গিয়ে 'এখন চেক করো' চাপো"
            setTextColor(Ui.MUTED); textSize = 13f
        }
        layout.addView(resultView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(text: String, action: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@PreflightOverlayService, Ui.RAISED, 999, stroke = true)
            setPadding(Ui.dp(this@PreflightOverlayService, 14), Ui.dp(this@PreflightOverlayService, 7),
                Ui.dp(this@PreflightOverlayService, 14), Ui.dp(this@PreflightOverlayService, 7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@PreflightOverlayService, 4); rightMargin = Ui.dp(this@PreflightOverlayService, 4); topMargin = Ui.dp(this@PreflightOverlayService, 8) }
            setOnClickListener { action() }
        }
        row.addView(btn("এখন চেক করো") { runCheck() })
        row.addView(btn("বন্ধ") { stopSelf() })
        layout.addView(row)
        val metrics = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.88).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = Ui.dp(this@PreflightOverlayService, 28) }
        try {
            wm?.addView(layout, p)
            root = layout
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun runCheck() {
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            resultView?.text = "আগে Accessibility চালু করো"
            return
        }
        val script = Store.selectedScript(this)
        if (script == null) {
            resultView?.text = "কোনো script সিলেক্ট করা নেই"
            return
        }
        // Hide our own panel for a moment so it cannot shadow the target app.
        root?.visibility = android.view.View.GONE
        handler.postDelayed({
            val sb = StringBuilder()
            sb.append("Script: ${script.name}\n")
            var checked = 0
            script.steps.forEachIndexed { i, step ->
                val line: String? = when (step.type) {
                    StepType.TEXT_TAP, StepType.WAIT_TEXT, StepType.CONDITION, StepType.HOLD_UNTIL -> {
                        checked++
                        val found = svc.findText(step.text, step.region) != null
                        "${i + 1}. \"${step.text}\" - ${if (found) "পাওয়া যাচ্ছে" else "পাওয়া যাচ্ছে না"}"
                    }
                    StepType.NUMBER_TARGET -> {
                        checked++
                        val n = svc.findNumber(step.text, step.region)
                        "${i + 1}. সংখ্যা টার্গেট ${step.x} - এখন: ${n?.toString() ?: "নেই"}"
                    }
                    else -> null
                }
                if (line != null) sb.append(line).append("\n")
            }
            if (checked == 0) sb.append("এই script-এ যাচাই করার মতো search step নেই।\n")
            resultView?.text = sb.toString().trim()
            root?.visibility = android.view.View.VISIBLE
        }, 450)
    }

    private fun hidePanel() {
        try { root?.let { wm?.removeView(it) } } catch (e: Exception) {}
        root = null
    }

    override fun onDestroy() {
        hidePanel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, PreflightOverlayService::class.java))
        }
    }
}
