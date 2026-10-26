package com.mthtamim.phonelink

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

/** Shared permission-state checks used by the home status card and the wizard.
 *  v2.17.4: the counted set is the FULL access set — 12 runtime
 *  permissions + 8 special (system-settings) accesses = 20 — so the
 *  gauge and missingCount finally mean "everything".
 *  v2.18: the App Locker's Overlay grant joins the special list —
 *  12 runtime + 9 special = 21.
 *  v2.21: CALL_PHONE joins the runtime list (the call_number action
 *  silently fell back to the dialer without it) — 13 runtime +
 *  9 special = 22. */
object PermChecks {

    fun has(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun notifAccess(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(ctx.packageName)
    }

    fun batteryIgnored(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Usage Access (PACKAGE_USAGE_STATS) via AppOps. */
    fun usageAccess(ctx: Context): Boolean = try {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION")
            ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    /** Our AccessibilityService is enabled. */
    fun a11yEnabled(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    } catch (_: Exception) { false }

    /** Exact alarms: always available before Android 12. */
    fun exactAlarmOk(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true

    /** WRITE_SETTINGS: this app may modify system settings. */
    fun writeSettingsOk(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** SYSTEM_ALERT_WINDOW: this app may draw over other apps
     *  (the App Locker's cover window). */
    fun overlayOk(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** (label string res, granted) for each runtime permission line — 13. */
    fun runtimeList(ctx: Context): List<Pair<Int, Boolean>> = listOf(
        R.string.perm_line_sms to has(ctx, Manifest.permission.RECEIVE_SMS),
        R.string.perm_line_send_sms to has(ctx, Manifest.permission.SEND_SMS),
        R.string.perm_line_read_sms to has(ctx, Manifest.permission.READ_SMS),
        R.string.perm_line_phone to has(ctx, Manifest.permission.READ_PHONE_STATE),
        R.string.perm_line_call_log to has(ctx, Manifest.permission.READ_CALL_LOG),
        R.string.perm_line_call_phone to has(ctx, Manifest.permission.CALL_PHONE),
        R.string.perm_line_contacts to has(ctx, Manifest.permission.READ_CONTACTS),
        R.string.perm_line_location to has(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
        R.string.perm_line_location_coarse to has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION),
        R.string.perm_line_camera to has(ctx, Manifest.permission.CAMERA),
        R.string.perm_line_steps to
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                has(ctx, Manifest.permission.ACTIVITY_RECOGNITION)),
        R.string.perm_line_media to
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                has(ctx, Manifest.permission.READ_MEDIA_IMAGES) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)),
        R.string.perm_line_notif_post to
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                has(ctx, Manifest.permission.POST_NOTIFICATIONS))
    )

    /** (label string res, granted) for the 9 special accesses. */
    fun specialList(ctx: Context): List<Pair<Int, Boolean>> = listOf(
        R.string.perm_notif_title to notifAccess(ctx),
        R.string.perm_battery_title to batteryIgnored(ctx),
        R.string.perm_usage_title to usageAccess(ctx),
        R.string.btn_admin to AdminReceiver.isActive(ctx),
        R.string.btn_a11y to a11yEnabled(ctx),
        R.string.perm_shizuku_title to ShizukuHelper.granted(),
        R.string.auto_health_alarm to exactAlarmOk(ctx),
        R.string.perm_write_title to writeSettingsOk(ctx),
        R.string.perm_overlay_title to overlayOk(ctx)
    )

    /** Runtime permissions that still need requesting (13 incl. CALL_PHONE). */
    fun runtimeToRequest(ctx: Context): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            @Suppress("DEPRECATION")
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        return perms.filter { !has(ctx, it) }.toTypedArray()
    }

    /** The full counted set: 13 runtime + 9 special = 22. */
    fun totalCount(ctx: Context): Int = runtimeList(ctx).size + specialList(ctx).size

    fun grantedCount(ctx: Context): Int =
        runtimeList(ctx).count { it.second } + specialList(ctx).count { it.second }

    /** Total number of permission items still missing (0 = everything granted). */
    fun missingCount(ctx: Context): Int = totalCount(ctx) - grantedCount(ctx)

    // ----- contextual permissions (shown as status lines, not in the gauge) -----

    fun btOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || has(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun micOk(ctx: Context): Boolean = has(ctx, Manifest.permission.RECORD_AUDIO)

    fun calendarOk(ctx: Context): Boolean =
        has(ctx, Manifest.permission.READ_CALENDAR) && has(ctx, Manifest.permission.WRITE_CALENDAR)

    fun installOk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
}
