package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (!Config.isSender(ctx)) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Restart the realtime link after a reboot, and tell the other phone.
                SecLog.add(ctx, "ফোন বুট/রিস্টার্ট হয়েছে")
                LinkService.start(ctx)
                if (Config.fwdSystem(ctx)) {
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "ফোন চালু হয়েছে",
                        "${Config.deviceName(ctx)} রিবুটের পর আবার অনলাইন। ব্যাটারি: ${batteryPercent(ctx)}%")
                }
            }
            Intent.ACTION_BATTERY_LOW -> {
                if (Config.fwdBattery(ctx))
                    Relay.send(ctx, Relay.TYPE_BATTERY, "ব্যাটারি কম",
                        "ব্যাটারি ${batteryPercent(ctx)}% — চার্জ দেওয়া দরকার।")
            }
            Intent.ACTION_BATTERY_OKAY -> {
                if (Config.fwdBattery(ctx))
                    Relay.send(ctx, Relay.TYPE_BATTERY, "ব্যাটারি আবার ঠিক আছে",
                        "ব্যাটারি ${batteryPercent(ctx)}%")
            }
            Intent.ACTION_POWER_CONNECTED -> {
                if (Config.fwdBattery(ctx))
                    Relay.send(ctx, Relay.TYPE_BATTERY, "চার্জে দেওয়া হয়েছে",
                        "ব্যাটারি ${batteryPercent(ctx)}%")
                LinkService.start(ctx) // good moment to flush the offline queue
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                if (Config.guardOn(ctx)) {
                    // Guard mode: an unplugged charger is treated as theft.
                    RingHelper.start(ctx, maxAll = true)
                    SecLog.add(ctx, "পাহারা মোড: চার্জার খুলে ফেলা হয়েছে")
                    Relay.send(ctx, Relay.TYPE_SYSTEM, "🚨 পাহারা মোড",
                        "চার্জার খুলে ফেলা হয়েছে — ফোন সরানো হয়ে থাকতে পারে।")
                } else if (Config.fwdBattery(ctx)) {
                    Relay.send(ctx, Relay.TYPE_BATTERY, "চার্জার খোলা হয়েছে",
                        "ব্যাটারি ${batteryPercent(ctx)}%")
                }
            }
            // Package add/remove lives in PackageReceiver — its intent
            // filter needs a package data scheme, this one must not have it.
        }
    }

    companion object {
        fun batteryPercent(ctx: Context): Int {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        fun batteryStatusText(ctx: Context): String {
            val pct = batteryPercent(ctx)
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val full = status == BatteryManager.BATTERY_STATUS_FULL
            return when {
                full -> "ব্যাটারি $pct% (ফুল, চার্জে আছে)"
                charging -> "ব্যাটারি $pct% (চার্জ হচ্ছে)"
                else -> "ব্যাটারি $pct%"
            }
        }
    }
}
