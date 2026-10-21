package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject

/**
 * Every phone that has talked on this pair code: name, last-seen time and
 * last known battery. Powers the device list, so several phones sharing
 * one code can all see who is around.
 */
object Peers {
    data class Peer(val name: String, val seen: Long, val battery: Int)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)

    @Synchronized
    fun touch(ctx: Context, name: String, battery: Int) {
        if (name.isBlank()) return
        try {
            val all = JSONObject(prefs(ctx).getString("peers", "{}"))
            val prev = all.optJSONObject(name)
            val batt = if (battery >= 0) battery else (prev?.optInt("battery", -1) ?: -1)
            all.put(name, JSONObject().apply {
                put("seen", System.currentTimeMillis())
                put("battery", batt)
            })
            prefs(ctx).edit().putString("peers", all.toString()).apply()
        } catch (_: Exception) { }
    }

    fun list(ctx: Context): List<Peer> {
        val out = mutableListOf<Peer>()
        try {
            val all = JSONObject(prefs(ctx).getString("peers", "{}"))
            val keys = all.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val o = all.getJSONObject(name)
                out.add(Peer(name, o.optLong("seen"), o.optInt("battery", -1)))
            }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.seen }
    }
}
