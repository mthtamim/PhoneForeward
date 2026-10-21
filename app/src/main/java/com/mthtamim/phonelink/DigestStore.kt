package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holding pen for notifications whose app rule is DIGEST. Items collect
 * here on the sender phone and are forwarded as one bundled message by
 * LinkService's heartbeat loop every Config.digestMinutes.
 */
object DigestStore {
    data class Entry(val app: String, val title: String, val body: String, val time: Long)

    private const val PREFS = "phonelink_digest"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, app: String, title: String, body: String) {
        try {
            val arr = JSONArray(prefs(ctx).getString("items", "[]"))
            arr.put(JSONObject().apply {
                put("app", app); put("title", title); put("body", body)
                put("time", System.currentTimeMillis())
            })
            while (arr.length() > 200) arr.remove(0)
            prefs(ctx).edit().putString("items", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun size(ctx: Context): Int =
        try { JSONArray(prefs(ctx).getString("items", "[]")).length() } catch (e: Exception) { 0 }

    @Synchronized
    fun drain(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(prefs(ctx).getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.optString("app"), o.optString("title"),
                    o.optString("body"), o.optLong("time")))
            }
            prefs(ctx).edit().remove("items").apply()
        } catch (_: Exception) { }
        return out
    }
}
