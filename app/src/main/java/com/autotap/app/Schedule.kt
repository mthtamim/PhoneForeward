package com.autotap.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Daily auto-start for a script at a fixed time (v2). Best effort: uses an
 * exact alarm when the system allows it, otherwise an allow-while-idle
 * alarm, and re-arms itself after every run for the next day.
 */
object ScheduleHelper {

    fun arm(ctx: Context, script: Script) {
        if (!script.scheduleEnabled) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val trigger = nextTrigger(script.scheduleHour, script.scheduleMinute, script.scheduleDays)
        val pi = pendingIntent(ctx, script)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) } catch (e2: Exception) {}
        }
    }

    fun disarm(ctx: Context, script: Script) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try { am.cancel(pendingIntent(ctx, script)) } catch (e: Exception) {}
    }

    private fun pendingIntent(ctx: Context, script: Script): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java)
            .setAction("com.autotap.app.SCHEDULE")
            .putExtra(ScheduleReceiver.EXTRA_SCRIPT_ID, script.id)
        return PendingIntent.getBroadcast(
            ctx, script.id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTrigger(hour: Int, minute: Int, daysMask: Int = 127): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        // Weekday mask: bit 0 = Sunday ... bit 6 = Saturday (Calendar DAY_OF_WEEK is 1..7).
        val mask = if (daysMask in 1..127) daysMask else 127
        var guard = 0
        while (guard < 8) {
            val bit = target.get(Calendar.DAY_OF_WEEK) - 1
            if (mask and (1 shl bit) != 0) break
            target.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return target.timeInMillis
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val scriptId = intent?.getStringExtra(EXTRA_SCRIPT_ID) ?: return
        val script = Store.getScript(context, scriptId) ?: return
        if (!script.scheduleEnabled || script.steps.isEmpty()) return
        // Respect the weekday selection; a non-matching day just re-arms.
        val todayBit = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        if (script.scheduleDays in 1..127 && (script.scheduleDays and (1 shl todayBit)) == 0) {
            ScheduleHelper.arm(context, script)
            return
        }
        if (TapAccessibilityService.isEnabled() && !RunnerService.running) {
            RunnerService.start(context, script.id)
        }
        // Re-arm for tomorrow regardless, so one missed day does not kill the schedule.
        ScheduleHelper.arm(context, script)
    }

    companion object {
        const val EXTRA_SCRIPT_ID = "script_id"
    }
}

/**
 * Watcher receiver (v3): every watchIntervalMin minutes the watched script
 * runs once; if its search step finds the text, RunnerService raises the
 * alert notification. The alarm re-arms itself each time it fires.
 */
class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        val script = Store.getScript(context, id) ?: return
        if (script.watchIntervalMin <= 0) return
        if (TapAccessibilityService.isEnabled() && !RunnerService.running && script.steps.isNotEmpty()) {
            RunnerService.start(context, script.id)
        }
        WatchHelper.arm(context, script)
    }

    companion object {
        const val EXTRA_ID = "watch_id"
    }
}

object WatchHelper {
    private const val REQ_BASE = 777000

    private fun pendingIntent(ctx: Context, scriptId: String): PendingIntent {
        val intent = Intent(ctx, WatchReceiver::class.java).apply {
            putExtra(WatchReceiver.EXTRA_ID, scriptId)
        }
        val req = REQ_BASE + (scriptId.hashCode() and 0xFFFF)
        return PendingIntent.getBroadcast(
            ctx, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun arm(ctx: Context, script: Script) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (script.watchIntervalMin <= 0) {
            cancel(ctx, script.id)
            return
        }
        val at = System.currentTimeMillis() + script.watchIntervalMin * 60000L
        val pi = pendingIntent(ctx, script.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (e2: Exception) {}
        }
    }

    fun cancel(ctx: Context, scriptId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try { am.cancel(pendingIntent(ctx, scriptId)) } catch (e: Exception) {}
    }
}

/**
 * Charging trigger (v3): when power connects, the script whose trigger is
 * set to "CHARGING" starts (chosen in the script's trigger picker).
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (RunnerService.running) return
        val script = Store.getScripts(context).firstOrNull {
            it.triggerPackage == "CHARGING" && it.steps.isNotEmpty()
        } ?: return
        if (TapAccessibilityService.isEnabled()) {
            RunnerService.start(context, script.id)
        }
    }
}
