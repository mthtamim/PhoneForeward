package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject

/** Lifetime counters for the statistics card: sent / received / commands. */
object Stats {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    private fun bump(ctx: Context, key: String, by: Int = 1) {
        prefs(ctx).edit().putLong(key, prefs(ctx).getLong(key, 0L) + by).apply()
    }

    private fun bumpType(ctx: Context, key: String, type: String) {
        try {
            val o = JSONObject(prefs(ctx).getString(key, "{}"))
            o.put(type, o.optInt(type, 0) + 1)
            prefs(ctx).edit().putString(key, o.toString()).apply()
        } catch (_: Exception) { }
    }

    fun incSent(ctx: Context, type: String) { bump(ctx, "st_sent"); bumpType(ctx, "st_sent_types", type); bumpDaily(ctx, "s") }
    fun incRecv(ctx: Context, type: String) { bump(ctx, "st_recv"); bumpType(ctx, "st_recv_types", type); bumpDaily(ctx, "r") }
    fun incCmd(ctx: Context) { bump(ctx, "st_cmd") }

    // v2.4 daily history for the charts screen: hist_<yyyy-MM-dd> = {s, r}.
    private fun bumpDaily(ctx: Context, field: String) {
        try {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val key = "hist_$day"
            val o = JSONObject(prefs(ctx).getString(key, "{}"))
            o.put(field, o.optInt(field, 0) + 1)
            prefs(ctx).edit().putString(key, o.toString()).apply()
        } catch (_: Exception) { }
    }

    fun daily(ctx: Context, day: String): Pair<Int, Int> {
        return try {
            val o = JSONObject(prefs(ctx).getString("hist_$day", "{}"))
            o.optInt("s", 0) to o.optInt("r", 0)
        } catch (_: Exception) { 0 to 0 }
    }

    fun sent(ctx: Context) = prefs(ctx).getLong("st_sent", 0L)
    fun recv(ctx: Context) = prefs(ctx).getLong("st_recv", 0L)
    fun cmds(ctx: Context) = prefs(ctx).getLong("st_cmd", 0L)

    fun typeCounts(ctx: Context, sentSide: Boolean): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        try {
            val o = JSONObject(prefs(ctx).getString(if (sentSide) "st_sent_types" else "st_recv_types", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); out[k] = o.optInt(k, 0) }
        } catch (_: Exception) { }
        return out
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .remove("st_sent").remove("st_recv").remove("st_cmd")
            .remove("st_sent_types").remove("st_recv_types")
            .apply()
    }
}
