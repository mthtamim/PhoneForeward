package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject

/**
 * Every phone that has talked on this pair code: name, last-seen time, last
 * known battery, last known feature-config string and shared status text.
 * Powers the device list and the remote-control panel, so several phones
 * sharing one code can all see who is around.
 */
object Peers {
    data class Peer(val name: String, val seen: Long, val battery: Int,
                    val cfg: String = "", val status: String = "",
                    val dev: String = "")

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    @Synchronized
    fun touch(ctx: Context, name: String, battery: Int, cfg: String = "",
              status: String = "", dev: String = "") {
        if (name.isBlank()) return
        try {
            val all = JSONObject(prefs(ctx).getString("peers", "{}"))
            val prev = all.optJSONObject(name)
            val batt = if (battery >= 0) battery else (prev?.optInt("battery", -1) ?: -1)
            all.put(name, JSONObject().apply {
                put("seen", System.currentTimeMillis())
                put("battery", batt)
                put("cfg", cfg.ifEmpty { prev?.optString("cfg") ?: "" })
                put("status", status.ifEmpty { prev?.optString("status") ?: "" })
                put("dev", dev.ifEmpty { prev?.optString("dev") ?: "" })
            })
            prefs(ctx).edit().putString("peers", all.toString()).apply()
            if (batt >= 0) recordBattery(ctx, batt)
        } catch (_: Exception) { }
    }

    /** v2.7: peer battery history points (throttled) for the chart. */
    @Synchronized
    fun recordBattery(ctx: Context, pct: Int) {
        try {
            val arr = try { org.json.JSONArray(
                prefs(ctx).getString("peerbat_hist", "[]") ?: "[]")
            } catch (e: Exception) { org.json.JSONArray() }
            val now = System.currentTimeMillis()
            val last = if (arr.length() > 0) arr.optJSONObject(arr.length() - 1) else null
            if (last != null && now - last.optLong("t") < 15 * 60_000 &&
                kotlin.math.abs(last.optInt("p") - pct) < 3) return
            arr.put(JSONObject().put("t", now).put("p", pct))
            while (arr.length() > 120) arr.remove(0)
            prefs(ctx).edit().putString("peerbat_hist", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun batteryHistory(ctx: Context): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        try {
            val arr = org.json.JSONArray(prefs(ctx).getString("peerbat_hist", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optLong("t") to o.optInt("p"))
            }
        } catch (_: Exception) { }
        return out
    }

    fun list(ctx: Context): List<Peer> {
        val out = mutableListOf<Peer>()
        try {
            val all = JSONObject(prefs(ctx).getString("peers", "{}"))
            val keys = all.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val o = all.getJSONObject(name)
                out.add(Peer(name, o.optLong("seen"), o.optInt("battery", -1),
                    o.optString("cfg"), o.optString("status"), o.optString("dev")))
            }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.seen }
    }
}
