package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject

class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        // Automation events fire regardless of forwarding roles.
        // v2.20.1: the service restart and the local security effects
        // below are also role-neutral. They used to sit behind the
        // sender gate further down, so a Receiver phone never restarted
        // LinkService after a reboot — and with no service there was
        // no poll loop, no tick, and no local automation at all.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Automation.onEvent(ctx, "boot", JSONObject())
                // v2.14.1: reboot wipes AlarmManager alarms — re-arm the
                // time rules for BOTH roles.
                Automation.scheduleTimeRules(ctx)
                SecLog.add(ctx, "ফোন বুট/রিস্টার্ট হয়েছে")
                LinkService.start(ctx)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                Automation.scheduleTimeRules(ctx)
            Intent.ACTION_POWER_CONNECTED -> {
                Automation.onEvent(ctx, "charging_on", JSONObject())
                LinkService.start(ctx) // good moment to flush the offline queue
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Automation.onEvent(ctx, "charging_off", JSONObject())
                if (Config.guardOn(ctx)) {
                    // Guard mode: an unplugged charger is treated as
                    // theft. Ringing + the local log entry happen on
                    // every role; only the peer alert below stays gated.
                    RingHelper.start(ctx, maxAll = true)
                    SecLog.add(ctx, "পাহারা মোড: চার্জার খুলে ফেলা হয়েছে")
                }
            }
            "android.intent.action.HEADSET_PLUG" -> {
                val state = intent.getIntExtra("state", 0)
                Automation.onEvent(ctx, if (state == 1) "headphone_in" else "headphone_out",
                    JSONObject())
            }
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" ->
                Automation.onEvent(ctx, "usb_connected", JSONObject())
        }
        if (!Config.isSender(ctx)) return

        // Everything below is peer forwarding and stays sender-only.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
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
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                if (Config.guardOn(ctx)) {
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

/** v2.16: EVERY battery value Android exposes without root —
 *  the sticky ACTION_BATTERY_CHANGED intent plus BatteryManager
 *  properties (charge/energy counters, current). Tamim asked for
 *  the plain way first; Shizuku's dumpsys only supplements it. */
object BatteryInfo {
    fun fullText(ctx: android.content.Context): String {
        val sb = StringBuilder()
        try {
            val i = ctx.registerReceiver(null, android.content.IntentFilter(
                android.content.Intent.ACTION_BATTERY_CHANGED))
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
                as android.os.BatteryManager
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
            sb.append("ব্যাটারি: ").append(if (pct >= 0) "$pct%" else "?")
            val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            sb.append(when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> " (চার্জ হচ্ছে)"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> " (ফুল)"
                android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> " (চার্জ কমছে)"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> " (চার্জ হচ্ছে না)"
                else -> "" })
            val plugged = i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val src = when (plugged) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC অ্যাডাপ্টার"
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "ওয়্যারলেস"
                else -> ""
            }
            if (src.isNotEmpty()) sb.append("\nউৎস: ").append(src)
            val temp = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) sb.append("\nতাপমাত্রা: ")
                .append(String.format(java.util.Locale.US, "%.1f°C", temp / 10.0))
            val volt = i?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (volt > 0) sb.append("\nভোল্টেজ: ")
                .append(String.format(java.util.Locale.US, "%.2fV", volt / 1000.0))
            val health = i?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
            sb.append("\nস্বাস্থ্য: ").append(when (health) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "ভালো"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "অতিরিক্ত গরম"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "মৃত"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "ওভার-ভোল্টেজ"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "ঠান্ডা"
                else -> "অজানা" })
            val tech = i?.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: ""
            if (tech.isNotEmpty()) sb.append("\nপ্রযুক্তি: ").append(tech)
            fun prop(id: Int): Long = try { bm.getLongProperty(id) }
                catch (e: Exception) { Long.MIN_VALUE }
            val charge = prop(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (charge != Long.MIN_VALUE && charge > 0)
                sb.append("\nঅবশিষ্ট চার্জ: ").append(charge / 1000).append(" mAh")
            val energy = prop(android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            if (energy != Long.MIN_VALUE && energy > 0)
                sb.append("\nশক্তি: ").append(
                    String.format(java.util.Locale.US, "%.2f Wh", energy / 1e9))
            val current = prop(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (current != Long.MIN_VALUE && current != 0L)
                sb.append("\nকারেন্ট: ").append(current / 1000).append(" mA")
            val remain = try { bm.computeChargeTimeRemaining() }
                catch (e: Exception) { -1L }
            if (remain > 0) sb.append("\nফুল হতে বাকি: ")
                .append(remain / 60000).append(" মিনিট")
        } catch (e: Exception) {
            if (sb.isEmpty()) sb.append("ব্যাটারির তথ্য পড়া যায়নি")
        }
        return sb.toString()
    }
}
