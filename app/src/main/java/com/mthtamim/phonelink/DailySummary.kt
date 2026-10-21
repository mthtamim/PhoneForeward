package com.mthtamim.phonelink

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.CallLog
import java.util.Calendar

/**
 * End-of-day summary built on the SENDER phone: today's calls, screen
 * time with top apps, forwarded count and current battery. Sections the
 * phone has no permission for are simply skipped.
 */
object DailySummary {

    fun build(ctx: Context): String {
        val lines = mutableListOf<String>()
        val start = startOfToday()

        // ---- calls today (needs READ_CALL_LOG) ----
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                var incoming = 0; var outgoing = 0; var missed = 0; var talkSecs = 0L
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                    "${CallLog.Calls.DATE} >= ?", arrayOf(start.toString()), null
                )?.use { c ->
                    val tIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                    val dIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                    while (c.moveToNext()) {
                        when (c.getInt(tIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> incoming++
                            CallLog.Calls.OUTGOING_TYPE -> outgoing++
                            CallLog.Calls.MISSED_TYPE -> missed++
                        }
                        talkSecs += c.getLong(dIdx)
                    }
                }
                lines.add("📞 কল: ইনকামিং $incoming, আউটগোয়িং $outgoing, মিসড $missed" +
                    " (মোট কথা ${talkSecs / 60} মিনিট)")
            } catch (_: Exception) { }
        }

        // ---- screen time today (needs usage access) ----
        if (usageAccess(ctx)) {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
                var total = 0L
                val perApp = mutableListOf<Pair<String, Long>>()
                for (s in stats) {
                    if (s.totalTimeInForeground > 0) {
                        total += s.totalTimeInForeground
                        perApp.add(s.packageName to s.totalTimeInForeground)
                    }
                }
                if (total > 0) {
                    lines.add("📱 স্ক্রিন টাইম: ${total / 3600000} ঘণ্টা ${(total % 3600000) / 60000} মিনিট")
                    val top = perApp.sortedByDescending { it.second }.take(3)
                    for ((pkg, ms) in top) {
                        lines.add("• ${Contacts.appLabel(ctx, pkg)}: ${ms / 60000} মিনিট")
                    }
                }
            } catch (_: Exception) { }
        }

        // ---- steps today (needs ACTIVITY_RECOGNITION on Android 10+) ----
        stepsToday(ctx)?.let { lines.add("🚶 আজকের পদক্ষেপ: $it") }

        lines.add("✉️ মোট ফরোয়ার্ড (সব সময়): ${Stats.sent(ctx)} টা")
        lines.add("🔋 ${SystemReceiver.batteryStatusText(ctx)}")
        return lines.joinToString("\n")
    }

    /**
     * Today's step count from the hardware step counter (which counts
     * since boot). A per-day baseline is kept in state prefs.
     */
    private fun stepsToday(ctx: Context): Int? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
                ?: return null
            val latch = java.util.concurrent.CountDownLatch(1)
            var current = -1f
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent?) {
                    if (e != null && current < 0) {
                        current = e.values[0]
                        latch.countDown()
                    }
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) { }
            }
            sm.registerListener(listener, sensor,
                android.hardware.SensorManager.SENSOR_DELAY_FASTEST)
            latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            sm.unregisterListener(listener)
            if (current < 0) return null
            val prefs = ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            if (prefs.getString("step_day", "") != today) {
                prefs.edit().putString("step_day", today)
                    .putFloat("step_base", current).apply()
                0
            } else {
                (current - prefs.getFloat("step_base", current)).toInt().coerceAtLeast(0)
            }
        } catch (_: Exception) { null }
    }

    fun usageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
