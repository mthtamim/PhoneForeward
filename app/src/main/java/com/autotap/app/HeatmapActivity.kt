package com.autotap.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tap heat map: every point a script tapped, drawn over a dark screen-sized
 * canvas so hot spots glow through density (points are drawn translucent,
 * overlaps stack up brighter). Tap anywhere to close.
 */
class HeatmapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scriptId = intent.getStringExtra(EXTRA_ID) ?: ""
        val script = Store.getScript(this, scriptId)
        val points = Store.heatPoints(this, scriptId)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@HeatmapActivity, 16), Ui.dp(this@HeatmapActivity, 16),
                Ui.dp(this@HeatmapActivity, 16), Ui.dp(this@HeatmapActivity, 16))
        }
        root.addView(TextView(this).apply {
            text = "Tap heatmap: ${script?.name ?: ""}"
            setTextColor(Ui.TEXT)
            textSize = 16f
        })
        root.addView(TextView(this).apply {
            text = "মোট ${points.size}টি tap বিন্দু — যেখানে বেশি tap, সেখানে বেশি উজ্জ্বল। বন্ধ করতে যেকোনো জায়গায় চাপো।"
            setTextColor(Ui.MUTED)
            textSize = 12f
        })
        val canvas = HeatView(this, points)
        canvas.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(canvas)
        root.setOnClickListener { finish() }
        setContentView(root)
    }

    inner class HeatView(ctx: Context, private val points: List<IntArray>) : View(ctx) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.BRIGHT
            alpha = 46
            style = Paint.Style.FILL
        }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.STROKE
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(ctx, 1).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            if (sw <= 0 || sh <= 0) return
            val scale = minOf(width / sw, height / sh)
            val ox = (width - sw * scale) / 2
            val oy = (height - sh * scale) / 2
            canvas.drawRect(ox, oy, ox + sw * scale, oy + sh * scale, frame)
            val r = Ui.dp(context, 13) * scale + 3f
            for (p in points) {
                canvas.drawCircle(ox + p[0] * scale, oy + p[1] * scale, r, dot)
            }
        }
    }

    companion object {
        const val EXTRA_ID = "script_id"
    }
}
