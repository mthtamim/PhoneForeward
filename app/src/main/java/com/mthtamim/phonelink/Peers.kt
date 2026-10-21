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
                out.add(Peer(name, o.optLong("seen"), o.optInt("battery", -1),
                    o.optString("cfg"), o.optString("status"), o.optString("dev")))
            }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.seen }
    }
}
