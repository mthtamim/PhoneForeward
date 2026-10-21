package com.mthtamim.phonelink

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device-admin hooks: enables the real LOCK command, makes uninstalling
 * harder (the admin must be deactivated first), and reports failed unlock
 * attempts — optionally with a front-camera photo (intruder selfie).
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        SecLog.add(context.applicationContext, "Device admin চালু হয়েছে")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        SecLog.add(context.applicationContext, "Device admin বন্ধ করা হয়েছে")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        SecLog.add(ctx, "ভুল পাসওয়ার্ড/PIN দিয়ে আনলকের চেষ্টা")
        if (!Config.intruderOn(ctx) || !Config.isSender(ctx)) return
        Relay.send(ctx, Relay.TYPE_SYSTEM, "🚨 ভুল আনলক চেষ্টা",
            "কেউ ভুল পাসওয়ার্ড/PIN দিয়ে ফোন খোলার চেষ্টা করেছে।")
        try {
            SessionState.snapFront = true
            val svc = Intent(ctx, SnapService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc)
            } else {
                ctx.startService(svc)
            }
        } catch (_: Exception) { }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        // Nothing to report for a normal successful unlock.
    }

    companion object {
        fun isActive(ctx: Context): Boolean {
            return try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
                dpm.isAdminActive(android.content.ComponentName(ctx, AdminReceiver::class.java))
            } catch (_: Exception) { false }
        }

        fun lockNow(ctx: Context): Boolean {
            return try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
                if (dpm.isAdminActive(android.content.ComponentName(ctx, AdminReceiver::class.java))) {
                    dpm.lockNow()
                    true
                } else false
            } catch (_: Exception) { false }
        }
    }
}
