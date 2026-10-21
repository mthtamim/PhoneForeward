package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full backup & restore (v2.12; replaces the v2.4 settings-only
 * transfer). One JSON document holds the whole app: settings
 * (phonelink_cfg), automation (phonelink_auto — rules, variables,
 * run log), shared state (phonelink_state — paired devices and
 * history marks) and the inbox itself.
 *
 * PIN secrets never leave the phone: the app-lock hash, command PIN
 * digest, panic PIN hash, web PIN and per-device command PINs are
 * excluded from the backup and must be set again after a restore.
 * The older v2.4 format (cfg + rules) still imports.
 */
object SettingsTransfer {

    private val skipExact = setOf("cmd_pin_digest", "app_pin_hash",
        "panic_pin_hash", "web_pin")

    private fun secret(k: String): Boolean =
        k in skipExact || k.startsWith("peer_pin_")

    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "PhoneLink")
        root.put("kind", "full-backup")
        root.put("v", 3)
        root.put("cfg", dump(ctx, "phonelink_cfg", true))
        root.put("auto", dump(ctx, "phonelink_auto", false))
        root.put("state", dump(ctx, "phonelink_state", false))
        root.put("inbox", History.exportJson(ctx))
        return root.toString()
    }

    private fun dump(ctx: Context, name: String,
                     skipSecrets: Boolean): JSONObject {
        val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in prefs.all) {
            if (v == null) continue
            if (skipSecrets && secret(k)) continue
            when (v) {
                is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }))
                else -> out.put(k, v)
            }
        }
        return out
    }

    fun import(ctx: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            if (root.optString("kind") == "full-backup") importFull(ctx, root)
            else importLegacy(ctx, root)
        } catch (e: Exception) { false }
    }

    private fun importFull(ctx: Context, root: JSONObject): Boolean {
        val cfgObj = root.optJSONObject("cfg") ?: return false
        restore(ctx, "phonelink_cfg", cfgObj, true)
        root.optJSONObject("auto")?.let {
            restore(ctx, "phonelink_auto", it, false) }
        root.optJSONObject("state")?.let {
            restore(ctx, "phonelink_state", it, false) }
        val inbox = root.optString("inbox", "")
        if (inbox.isNotEmpty()) History.importJson(ctx, inbox)
        // Re-hook rule watchers, exact alarms and sync from the
        // restored rule set.
        try { Automation.saveAll(ctx, Automation.rules(ctx)) }
        catch (_: Exception) { }
        return true
    }

    /** Clears the store, then writes every value with its JSON type. */
    private fun restore(ctx: Context, name: String, obj: JSONObject,
                        skipSecrets: Boolean) {
        val edit = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (skipSecrets && secret(k)) continue
            when (val v = obj.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Double -> edit.putFloat(k, v.toFloat())
                is String -> edit.putString(k, v)
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until v.length()) set.add(v.optString(i))
                    edit.putStringSet(k, set)
                }
            }
        }
        edit.apply()
    }

    /** The v2.4 format: a "cfg" object plus a "rules" string. */
    private fun importLegacy(ctx: Context, root: JSONObject): Boolean {
        val cfgObj = root.optJSONObject("cfg") ?: return false
        val cfg = ctx.getSharedPreferences("phonelink_cfg",
            Context.MODE_PRIVATE)
        val edit = cfg.edit()
        val keys = cfgObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (secret(k)) continue
            when (val v = cfgObj.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Double -> edit.putFloat(k, v.toFloat())
                is String -> edit.putString(k, v)
                is JSONArray -> {
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
        return true
    }
}
