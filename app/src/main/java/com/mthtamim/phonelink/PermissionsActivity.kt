package com.mthtamim.phonelink

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Permission wizard — v2.17.4: the ONE place every access is granted
 * and checked. The gauge counts the full set (12 runtime + 9 special
 * = 21, from PermChecks; the Overlay row joined in v2.18 for the
 * App Locker); contextual permissions (Bluetooth, mic,
 * calendar, install) show as status lines below, with calendar
 * requested right here (its only grant home) and Bluetooth/mic
 * deep-linking to the screens that request them.
 * Opened automatically once (first launch, or first service start
 * while something is missing) and any time from Settings.
 */
class PermissionsActivity : LocalizedActivity() {

    private lateinit var pbGauge: android.widget.ProgressBar
    private lateinit var tvGauge: TextView
    private lateinit var tvGaugeSub: TextView
    private lateinit var tvGaugeMark: TextView
    private lateinit var tvRuntimeLines: TextView
    private lateinit var tvNotifState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var tvUsageState: TextView
    private lateinit var tvAdminState: TextView
    private lateinit var tvA11yState: TextView
    private lateinit var tvShizukuState: TextView
    private lateinit var tvExactState: TextView
    private lateinit var tvWriteState: TextView
    private lateinit var tvOverlayState: TextView
    private lateinit var tvBtState: TextView
    private lateinit var tvMicState: TextView
    private lateinit var tvCalState: TextView
    private lateinit var tvInstallState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        pbGauge = findViewById(R.id.pbPermGauge)
        tvGauge = findViewById(R.id.tvPermGauge)
        tvGaugeSub = findViewById(R.id.tvPermGaugeSub)
        tvGaugeMark = findViewById(R.id.tvPermGaugeMark)
        tvRuntimeLines = findViewById(R.id.tvPermRuntimeLines)
        tvNotifState = findViewById(R.id.tvPermNotifState)
        tvBatteryState = findViewById(R.id.tvPermBatteryState)
        tvUsageState = findViewById(R.id.tvPermUsageState)
        tvAdminState = findViewById(R.id.tvPermAdminState)
        tvA11yState = findViewById(R.id.tvPermA11yState)
        tvShizukuState = findViewById(R.id.tvPermShizukuState)
        tvExactState = findViewById(R.id.tvPermExactState)
        tvWriteState = findViewById(R.id.tvPermWriteState)
        tvOverlayState = findViewById(R.id.tvPermOverlayState)
        tvBtState = findViewById(R.id.tvPermBtState)
        tvMicState = findViewById(R.id.tvPermMicState)
        tvCalState = findViewById(R.id.tvPermCalState)
        tvInstallState = findViewById(R.id.tvPermInstallState)

        findViewById<View>(R.id.btnPermBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPermDone).setOnClickListener { finish() }

        // v2.17.4: the old "diagnostics" button only counted numbers.
        // It now really re-reads every check and names what is missing.
        findViewById<View>(R.id.btnPermDiag).setOnClickListener {
            refresh()
            val missing = (PermChecks.runtimeList(this) + PermChecks.specialList(this))
                .filter { !it.second }.map { getString(it.first) }
            if (missing.isEmpty()) {
                toast(getString(R.string.perm_summary_ok))
            } else {
                toast(getString(R.string.perm_summary_missing, missing.size) +
                    ": " + missing.joinToString(", "))
            }
        }

        findViewById<View>(R.id.btnPermissions).setOnClickListener {
            val missing = PermChecks.runtimeToRequest(this)
            if (missing.isEmpty()) {
                toast(getString(R.string.perm_summary_ok))
            } else {
                requestPermissions(missing, 42)
            }
        }
        findViewById<View>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast(getString(R.string.toast_enable_in_list))
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
        // Usage Access — moved here from the Smart panel (v2.17.4).
        findViewById<View>(R.id.btnPermUsage).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { toast(getString(R.string.toast_no_usage_settings)) }
        }
        // Device Admin — moved here from the Security panel (v2.17.4);
        // same grant/deactivate behaviour the Security button had.
        findViewById<View>(R.id.btnPermAdmin).setOnClickListener {
            if (AdminReceiver.isActive(this)) {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) { }
                toast(getString(R.string.admin_deactivate_hint))
            } else {
                val comp = android.content.ComponentName(this, AdminReceiver::class.java)
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_explanation))
                try { startActivity(intent) } catch (_: Exception) {
                    toast(getString(R.string.toast_admin_failed))
                }
            }
        }
        // Accessibility — moved here from the Security panel (v2.17.4).
        findViewById<View>(R.id.btnPermA11y).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) { }
        }
        // Shizuku connect/test — moved here from Relay diagnostics (v2.17.4).
        findViewById<View>(R.id.btnPermShizuku).setOnClickListener {
            when {
                !ShizukuHelper.available() ->
                    toast(getString(R.string.shizuku_absent))
                ShizukuHelper.granted() -> {
                    toast(getString(R.string.shizuku_testing))
                    thread {
                        val ok = ShizukuHelper.selfTest()
                        val err = ShizukuHelper.lastError ?: ""
                        runOnUiThread {
                            if (ok) toast(getString(R.string.shizuku_ready))
                            else toast(getString(R.string.shizuku_broken, err))
                            refresh()
                        }
                    }
                }
                else -> {
                    ShizukuHelper.request()
                    toast(getString(R.string.shizuku_ask))
                }
            }
        }
        // Exact alarm — was only an Automation health row (v2.17.4).
        findViewById<View>(R.id.btnPermExact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
            }
        }
        // WRITE_SETTINGS — brand-new grant row (v2.17.4): opens this
        // app's "modify system settings" page directly.
        findViewById<View>(R.id.btnPermWrite).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)) }
                catch (_: Exception) { }
            }
        }
        // Overlay — v2.18: the App Locker's cover window grant;
        // opens this app's "display over other apps" page directly.
        findViewById<View>(R.id.btnPermOverlay).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                catch (_: Exception) { }
            }
        }
        // Contextual rows: Bluetooth and mic are requested by their
        // own feature screens — these buttons are links, not grants.
        findViewById<View>(R.id.btnPermBtOpen).setOnClickListener {
            startActivity(Intent(this, BtActivity::class.java))
        }
        findViewById<View>(R.id.btnPermMicOpen).setOnClickListener {
            startActivity(Intent(this, VoiceActivity::class.java))
        }
        // Calendar: no feature screen requests it, so this screen is
        // its single grant home (v2.17.4).
        findViewById<View>(R.id.btnPermCal).setOnClickListener {
            if (PermChecks.calendarOk(this)) {
                toast(getString(R.string.perm_summary_ok))
            } else {
                requestPermissions(arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR), 43)
            }
        }
        findViewById<View>(R.id.btnPermInstall).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
            }
        }

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

    private fun setState(tv: TextView, ok: Boolean, okColor: Int, badColor: Int) {
        tv.text = getString(if (ok) R.string.status_granted else R.string.status_missing)
        tv.setTextColor(if (ok) okColor else badColor)
    }

    private fun refresh() {
        val okColor = ContextCompat.getColor(this, R.color.status_ok)
        val badColor = ContextCompat.getColor(this, R.color.status_bad)

        // Gauge: the full 22-item set from PermChecks.
        val total = PermChecks.totalCount(this)
        val granted = PermChecks.grantedCount(this)
        pbGauge.progress = if (total > 0) granted * 100 / total else 0
        tvGauge.text = getString(R.string.perm_gauge_fmt, granted, total)
        tvGaugeSub.text = getString(R.string.perm_diag_fmt, granted, total)
        tvGaugeMark.text = if (granted == total) "✓" else "!"
        tvGaugeMark.setTextColor(if (granted == total) okColor else badColor)

        val lines = PermChecks.runtimeList(this)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, (labelRes, ok) ->
            val start = sb.length
            sb.append(if (ok) "✓ " else "✗ ").append(getString(labelRes))
            sb.setSpan(
                ForegroundColorSpan(if (ok) okColor else badColor),
                start, start + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (i < lines.size - 1) sb.append("\n")
        }
        tvRuntimeLines.text = sb

        setState(tvNotifState, PermChecks.notifAccess(this), okColor, badColor)
        setState(tvBatteryState, PermChecks.batteryIgnored(this), okColor, badColor)
        setState(tvUsageState, PermChecks.usageAccess(this), okColor, badColor)
        setState(tvAdminState, AdminReceiver.isActive(this), okColor, badColor)
        setState(tvA11yState, PermChecks.a11yEnabled(this), okColor, badColor)
        setState(tvShizukuState, ShizukuHelper.granted(), okColor, badColor)
        setState(tvExactState, PermChecks.exactAlarmOk(this), okColor, badColor)
        setState(tvWriteState, PermChecks.writeSettingsOk(this), okColor, badColor)
        setState(tvOverlayState, PermChecks.overlayOk(this), okColor, badColor)

        setState(tvBtState, PermChecks.btOk(this), okColor, badColor)
        setState(tvMicState, PermChecks.micOk(this), okColor, badColor)
        setState(tvCalState, PermChecks.calendarOk(this), okColor, badColor)
        setState(tvInstallState, PermChecks.installOk(this), okColor, badColor)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

