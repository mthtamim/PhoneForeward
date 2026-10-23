package com.mthtamim.phonelink

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v2.11: expense view over the inbox's mobile-money SMS (bKash, Nagad,
 * Rocket, Upay, banks) — monthly in/out totals, per-service sums and
 * the most recent transactions. Parsing lives in Expenses.kt.
 */
class ExpensesActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expenses)
        findViewById<ImageButton>(R.id.btnExpBack).setOnClickListener { finish() }
        val box = findViewById<LinearLayout>(R.id.expBox)
        val txns = Expenses.parse(this)

        fun line(text: String, size: Float = 15f, bold: Boolean = false) {
            val tv = TextView(this)
            tv.text = text
            tv.textSize = size
            if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setPadding(4, 6, 4, 6)
            box.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        if (txns.isEmpty()) {
            line(getString(R.string.exp_none), 16f)
            return
        }
        val cal = Calendar.getInstance()
        val thisMonth = txns.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.time }
            c.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                c.get(Calendar.MONTH) == cal.get(Calendar.MONTH)
        }
        val inSum = thisMonth.filter { it.incoming }.sumOf { it.amount }
        val outSum = thisMonth.filter { !it.incoming }.sumOf { it.amount }
        line(getString(R.string.exp_month_title), 17f, true)
        line(getString(R.string.exp_in, fmt(inSum)))
        line(getString(R.string.exp_out, fmt(outSum)))
        line(getString(R.string.exp_net, fmt(inSum - outSum)))
        line(getString(R.string.exp_by_service), 17f, true)
        for ((svc, list) in txns.groupBy { it.service }) {
            val i = list.filter { it.incoming }.sumOf { it.amount }
            val o = list.filter { !it.incoming }.sumOf { it.amount }
            line("$svc — ${getString(R.string.exp_in, fmt(i))} • " +
                getString(R.string.exp_out, fmt(o)))
        }
        line(getString(R.string.exp_recent), 17f, true)
        val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        for (t in txns.take(40)) {
            line((if (t.incoming) "↓ " else "↑ ") + t.service + " — ৳" +
                fmt(t.amount) + "\n" + df.format(Date(t.time)) + " • " +
                t.title.take(48), 13f)
        }
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) "%,d".format(v.toLong())
        else "%,.2f".format(v)
}
