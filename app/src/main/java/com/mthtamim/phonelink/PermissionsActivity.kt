package com.mthtamim.phonelink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Permission wizard. Opened automatically once (first launch, or first
 * service start while something is missing) and any time from Home/Settings.
 * Granted items show green, missing ones red.
 */
class PermissionsActivity : LocalizedActivity() {

    private lateinit var tvNotifState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var tvRuntimeLines: TextView
    // v2.17.2 Permissions Manager: gauge + Shizuku state row
    private lateinit var pbGauge: android.widget.ProgressBar
    private lateinit var tvGauge: TextView
    private lateinit var tvGaugeSub: TextView
    private lateinit var tvGaugeMark: TextView
    private lateinit var tvShizukuState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        tvNotifState = findViewById(R.id.tvPermNotifState)
        tvBatteryState = findViewById(R.id.tvPermBatteryState)
        tvRuntimeLines = findViewById(R.id.tvPermRuntimeLines)
        pbGauge = findViewById(R.id.pbPermGauge)
        tvGauge = findViewById(R.id.tvPermGauge)
        tvGaugeSub = findViewById(R.id.tvPermGaugeSub)
        tvGaugeMark = findViewById(R.id.tvPermGaugeMark)
        tvShizukuState = findViewById(R.id.tvPermShizukuState)

        findViewById<View>(R.id.btnPermBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPermDiag).setOnClickListener {
            refresh()
            val total = 2 + PermChecks.runtimeList(this).size
            val granted = total - PermChecks.missingCount(this)
            toast(getString(R.string.perm_diag_fmt, granted, total))
        }

        findViewById<View>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast(getString(R.string.toast_enable_in_list))
        }
        findViewById<View>(R.id.btnPermissions).setOnClickListener {
            val missing = PermChecks.runtimeToRequest(this)
            if (missing.isEmpty()) {
                toast(getString(R.string.perm_summary_ok))
            } else {
                requestPermissions(missing, 42)
            }
        }
        findViewById<View>(R.id.btnBatteryOpt).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<View>(R.id.btnPermDone).setOnClickListener { finish() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        refresh()
        toast(getString(R.string.toast_perm_updated))
    }

    private fun refresh() {
        val okColor = ContextCompat.getColor(this, R.color.status_ok)
        val badColor = ContextCompat.getColor(this, R.color.status_bad)

        val notifOk = PermChecks.notifAccess(this)
        tvNotifState.text = getString(if (notifOk) R.string.status_granted else R.string.status_missing)
        tvNotifState.setTextColor(if (notifOk) okColor else badColor)

        val batteryOk = PermChecks.batteryIgnored(this)
        tvBatteryState.text = getString(if (batteryOk) R.string.status_granted else R.string.status_missing)
        tvBatteryState.setTextColor(if (batteryOk) okColor else badColor)

        // v2.17.2: the gauge is fed by the real PermChecks counts —
        // notification access + battery exemption + the runtime list.
        val runtimeNow = PermChecks.runtimeList(this)
        val total = 2 + runtimeNow.size
        val granted = (if (notifOk) 1 else 0) + (if (batteryOk) 1 else 0) +
            runtimeNow.count { it.second }
        pbGauge.progress = if (total > 0) granted * 100 / total else 0
        tvGauge.text = getString(R.string.perm_gauge_fmt, granted, total)
        tvGaugeSub.text = getString(R.string.perm_diag_fmt, granted, total)
        tvGaugeMark.text = if (granted == total) "✓" else "!"
        tvGaugeMark.setTextColor(if (granted == total) okColor else badColor)
        val shizukuOk = ShizukuHelper.granted()
        tvShizukuState.text = getString(
            if (shizukuOk) R.string.status_granted else R.string.status_missing)
        tvShizukuState.setTextColor(if (shizukuOk) okColor else badColor)

        val lines = PermChecks.runtimeList(this)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, (labelRes, granted) ->
            val start = sb.length
            sb.append(if (granted) "✓ " else "✗ ").append(getString(labelRes))
            sb.setSpan(
                ForegroundColorSpan(if (granted) okColor else badColor),
                start, start + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (i < lines.size - 1) sb.append("\n")
        }
        tvRuntimeLines.text = sb
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
