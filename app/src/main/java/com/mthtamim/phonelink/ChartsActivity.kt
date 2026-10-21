package com.mthtamim.phonelink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** v2.4 history screen: last 7 days of sent/received counts as bars. */
class ChartsActivity : LocalizedActivity() {

    class BarsView(ctx: Context, private val values: List<Int>) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(103, 80, 164)
        }
        private val paint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(11, 128, 113)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val n = values.size / 2
            if (n == 0) return
            val slot = width.toFloat() / n
            val barW = slot * 0.3f
            for (i in 0 until n) {
                val s = values[i * 2]
                val r = values[i * 2 + 1]
                val hs = (s.toFloat() / max) * (height - 8)
                val hr = (r.toFloat() / max) * (height - 8)
                val x = i * slot + slot * 0.15f
                canvas.drawRect(x, height - hs, x + barW, height.toFloat(), paint)
                canvas.drawRect(x + barW + 4, height - hr, x + barW * 2 + 4,
                    height.toFloat(), paint2)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charts)
        findViewById<android.widget.ImageButton>(R.id.btnChartsBack).setOnClickListener { finish() }
        val container = findViewById<LinearLayout>(R.id.chartsContainer)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFmt = SimpleDateFormat("dd MMM", Locale.US)
        val cal = Calendar.getInstance()
        val days = mutableListOf<Pair<String, String>>()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            days.add(fmt.format(cal.time) to labelFmt.format(cal.time))
        }
        val values = mutableListOf<Int>()
        for ((day, _) in days) {
            val (s, r) = Stats.daily(this, day)
            values.add(s); values.add(r)
        }
        val chart = BarsView(this, values).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 360)
        }
        container.addView(chart)
        container.addView(TextView(this).apply {
            text = getString(R.string.charts_legend)
            setPadding(4, 8, 4, 12)
        })
        for ((day, label) in days) {
            val (s, r) = Stats.daily(this, day)
            container.addView(TextView(this).apply {
                text = "$label — ${getString(R.string.charts_sent)} $s • ${getString(R.string.charts_recv)} $r"
                textSize = 15f
                setPadding(4, 6, 4, 6)
            })
        }

        // v2.7: peer battery history (points recorded from peer status).
        container.addView(TextView(this).apply {
            text = getString(R.string.charts_peer_batt)
            textSize = 17f
            setPadding(4, 20, 4, 8)
        })
        val hist = Peers.batteryHistory(this)
        if (hist.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.charts_peer_batt_empty)
                setPadding(4, 4, 4, 12)
            })
        } else {
            val vals = hist.flatMap { listOf(0, it.second) }
            val chart = BarsView(this, vals)
            chart.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120)
            container.addView(chart)
            val last = hist.last()
            container.addView(TextView(this).apply {
                text = getString(R.string.charts_peer_batt_last, last.second)
                setPadding(4, 8, 4, 12)
            })
        }
    }
}
