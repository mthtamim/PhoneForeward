package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject

/**
 * v2.4 whole-configuration transfer: exports the app's settings and
 * automation rules as one JSON document (for a file or a pasted code),
 * and imports it back on another phone. Secrets that must stay local
 * (app-lock hash, command PIN digest) are never exported.
 */
object SettingsTransfer {

    private val skipKeys = setOf("cmd_pin_digest", "app_pin_hash")

    fun export(ctx: Context): String {
        val cfg = ctx.getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE)
        val out = JSONObject()
        val cfgObj = JSONObject()
        for ((k, v) in cfg.all) {
            if (k in skipKeys || v == null) continue
            cfgObj.put(k, v)
        }
        out.put("cfg", cfgObj)
        val auto = ctx.getSharedPreferences("phonelink_auto", Context.MODE_PRIVATE)
        out.put("rules", auto.getString("rules", "[]"))
        out.put("v", 24)
        return out.toString()
    }

    fun import(ctx: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val cfgObj = root.optJSONObject("cfg") ?: return false
            val cfg = ctx.getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE)
            val edit = cfg.edit()
            val keys = cfgObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in skipKeys) continue
                when (val v = cfgObj.get(k)) {
                    is Boolean -> edit.putBoolean(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is Float -> edit.putFloat(k, v)
                    is String -> edit.putString(k, v)
                    is org.json.JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until v.length()) set.add(v.optString(i))
                        edit.putStringSet(k, set)
                    }
                }
            }
            edit.apply()
            val rules = root.optString("rules", "")
            if (rules.isNotEmpty()) {
                ctx.getSharedPreferences("phonelink_auto", Context.MODE_PRIVATE)
                    .edit().putString("rules", rules).apply()
                Automation.refreshWatchers(ctx)
            }
            true
        } catch (e: Exception) { false }
    }
}
