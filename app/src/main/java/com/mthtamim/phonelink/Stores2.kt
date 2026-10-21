package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small state-prefs stores added in v2.3: security log, scheduled
 *  messages and the peer battery history. */
object SecLog {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    fun add(ctx: Context, text: String) {
        try {
            val arr = try { JSONArray(prefs(ctx).getString("sec_log", "[]")) }
                catch (e: Exception) { JSONArray() }
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("s", text))
            while (arr.length() > 60) arr.remove(0)
            prefs(ctx).edit().putString("sec_log", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun all(ctx: Context): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        try {
            val arr = JSONArray(prefs(ctx).getString("sec_log", "[]"))
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.getJSONObject(i)
                out.add(o.optLong("t") to o.optString("s"))
            }
        } catch (_: Exception) { }
        return out
    }
}

object SchedStore {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    data class Entry(val id: Long, val time: Long, val text: String)

    fun list(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(prefs(ctx).getString("sched", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.optLong("id"), o.optLong("time"), o.optString("text")))
            }
        } catch (_: Exception) { }
        return out.sortedBy { it.time }
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("id", e.id).put("time", e.time).put("text", e.text))
        prefs(ctx).edit().putString("sched", arr.toString()).apply()
    }

    fun add(ctx: Context, time: Long, text: String) {
        save(ctx, list(ctx) + Entry(time, time, text))
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, list(ctx).filterNot { it.id == id })
    }

    /** Returns due entries and removes them from the store. */
    fun takeDue(ctx: Context, now: Long): List<Entry> {
        val all = list(ctx)
        val due = all.filter { it.time <= now }
        if (due.isNotEmpty()) save(ctx, all.filter { it.time > now })
        return due
    }
}

object BatteryHist {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    fun add(ctx: Context, pct: Int) {
        if (pct < 0) return
        try {
            val arr = try { JSONArray(prefs(ctx).getString("batt_hist", "[]")) }
                catch (e: Exception) { JSONArray() }
            // Skip a sample identical to the previous one.
            if (arr.length() > 0 && arr.getJSONArray(arr.length() - 1).optInt(1) == pct) return
            arr.put(JSONArray().put(System.currentTimeMillis()).put(pct))
            while (arr.length() > 120) arr.remove(0)
            prefs(ctx).edit().putString("batt_hist", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun summary(ctx: Context): String? {
        return try {
            val arr = JSONArray(prefs(ctx).getString("batt_hist", "[]"))
            if (arr.length() == 0) return null
            var min = 100; var max = 0; var last = -1
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i).optInt(1)
                if (p < min) min = p
                if (p > max) max = p
                last = p
            }
            "🔋 $min% – $max% (এখন $last%)"
        } catch (e: Exception) { null }
    }
}
