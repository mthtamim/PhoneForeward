package com.mthtamim.phonelink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Shared permission-state checks used by the home status card and the wizard. */
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

    /** (label string res, granted) for each runtime permission line. */
    fun runtimeList(ctx: Context): List<Pair<Int, Boolean>> = listOf(
        R.string.perm_line_sms to has(ctx, Manifest.permission.RECEIVE_SMS),
        R.string.perm_line_phone to has(ctx, Manifest.permission.READ_PHONE_STATE),
        R.string.perm_line_contacts to has(ctx, Manifest.permission.READ_CONTACTS),
        R.string.perm_line_location to has(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
        R.string.perm_line_notif_post to
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                has(ctx, Manifest.permission.POST_NOTIFICATIONS))
    )

    /** Runtime permissions that still need requesting. */
    fun runtimeToRequest(ctx: Context): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.filter { !has(ctx, it) }.toTypedArray()
    }

    /** Total number of permission items still missing (0 = everything granted). */
    fun missingCount(ctx: Context): Int {
        var n = 0
        if (!notifAccess(ctx)) n++
        if (!batteryIgnored(ctx)) n++
        n += runtimeList(ctx).count { !it.second }
        return n
    }
}
