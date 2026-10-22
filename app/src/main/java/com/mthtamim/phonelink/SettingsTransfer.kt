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

    // v2.15.1: keys the app reads with getLong / getInt. A JSON round
    // trip cannot tell a small Long from an Int (both are just "42"),
    // and a restore that guesses wrong makes the typed getter throw
    // ClassCastException — on a startup path that bricks the app
    // ("restore an old backup, the app never opens again" — Tamim).
    // These lists, plus the type of the value already in the store,
    // are the authority when a restore writes numbers back.
    private val knownLong = setOf("auto_backup_last", "lan_peer_at",
        "last_ack_time", "last_digest", "last_latency_ms", "last_lowloc",
        "last_media_id", "last_photos_at", "last_relay_ok",
        "last_seen_time", "last_sender_seen", "last_video_id",
        "media_img_id", "media_vid_id", "mms_seen_id", "net_down_since",
        "pause_until", "pin_lock_until", "quiet_until",
        "screen_off_since", "sms_sent_last_id")
    private val knownInt = setOf("checkin_expect", "clip_hash",
        "data_limit_mb", "debounce_sec", "digest_minutes", "focus_end",
        "focus_start", "geo_inside", "geo_radius", "inbox_keep_days",
        "last_seen_count", "last_sender_battery", "pin_fails",
        "quiet_end", "quiet_start", "routine_minutes", "smtp_port",
        "steps_today", "tab", "wx_code", "wxf_code")

    /** Writes one backup value, coerced to the type the app actually
     *  reads: the type already in the store wins, then the known-key
     *  tables, then the JSON type. Values that cannot be coerced are
     *  skipped instead of poisoning the store. */
    private fun putCoerced(edit: android.content.SharedPreferences.Editor,
                           current: Map<String, *>, k: String, v: Any) {
        val cur = current[k]
        val target = when {
            cur is Boolean -> "b"
            cur is Int -> "i"
            cur is Long -> "l"
            cur is Float -> "f"
            cur is String -> "s"
            cur is Set<*> -> "set"
            k in knownLong || k.startsWith("fired_") -> "l"
            k in knownInt -> "i"
            v is Boolean -> "b"
            v is Int -> "i"
            v is Long -> "l"
            v is Double -> "f"
            v is String -> "s"
            v is JSONArray -> "set"
            else -> "skip"
        }
        val num = v as? Number
        when (target) {
            "b" -> edit.putBoolean(k, (v as? Boolean)
                ?: v.toString().equals("true", ignoreCase = true))
            "i" -> num?.let { edit.putInt(k, it.toInt()) }
                ?: (v as? String)?.toIntOrNull()?.let { edit.putInt(k, it) }
            "l" -> num?.let { edit.putLong(k, it.toLong()) }
                ?: (v as? String)?.toLongOrNull()?.let { edit.putLong(k, it) }
            "f" -> num?.let { edit.putFloat(k, it.toFloat()) }
                ?: (v as? String)?.toFloatOrNull()?.let { edit.putFloat(k, it) }
            "s" -> if (v !is JSONArray && v !is JSONObject)
                edit.putString(k, v.toString())
            "set" -> if (v is JSONArray) {
                val set = mutableSetOf<String>()
                for (i in 0 until v.length()) set.add(v.optString(i))
                edit.putStringSet(k, set)
            }
        }
    }

    /** v2.15.1: startup self-repair. If a restore (old or new) already
     *  poisoned a store with a wrongly-typed number, rewrite the known
     *  keys to the types the readers expect — installing this version
     *  un-bricks the app without wiping any data. */
    fun heal(ctx: Context) {
        for (name in listOf("phonelink_cfg", "phonelink_auto",
                            "phonelink_state")) {
            try {
                val prefs = ctx.getSharedPreferences(name,
                    Context.MODE_PRIVATE)
                val edit = prefs.edit()
                var changed = false
                for ((k, v) in prefs.all) {
                    when {
                        (k in knownLong || k.startsWith("fired_")) && v is Int -> {
                            edit.putLong(k, v.toLong()); changed = true }
                        (k in knownLong || k.startsWith("fired_")) && v is String ->
                            v.toLongOrNull()?.let {
                                edit.putLong(k, it); changed = true }
                        k in knownInt && v is Long &&
                            v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> {
                            edit.putInt(k, v.toInt()); changed = true }
                        k in knownInt && v is String ->
                            v.toIntOrNull()?.let {
                                edit.putInt(k, it); changed = true }
                    }
                }
                if (changed) edit.apply()
            } catch (_: Exception) { }
        }
    }

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
        val text = json.trim()
        // v2.14.2: accept a bare rules array too — users restore through
        // whichever box they find first, and a format mismatch used to
        // fail silently in both directions.
        if (text.startsWith("[")) {
            return try { Automation.importJson(ctx, text) >= 0 }
            catch (e: Exception) { false }
        }
        return try {
            val root = JSONObject(text)
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
        // A damaged inbox must not fail the whole restore.
        if (inbox.isNotEmpty()) try { History.importJson(ctx, inbox) }
        catch (_: Exception) { }
        // Re-hook rule watchers, exact alarms and sync from the
        // restored rule set.
        try { Automation.saveAll(ctx, Automation.rules(ctx)) }
        catch (_: Exception) { }
        return true
    }

    /** Clears the store, then writes every value coerced to the type
     *  the app reads (v2.15.1 — see putCoerced). */
    private fun restore(ctx: Context, name: String, obj: JSONObject,
                        skipSecrets: Boolean) {
        val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val current = HashMap(prefs.all)
        val edit = prefs.edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (skipSecrets && secret(k)) continue
            putCoerced(edit, current, k, obj.get(k))
        }
        edit.apply()
    }

    /** The v2.4 format: a "cfg" object plus a "rules" string. */
    private fun importLegacy(ctx: Context, root: JSONObject): Boolean {
        val cfgObj = root.optJSONObject("cfg")
        if (cfgObj == null && root.optString("rules", "").isEmpty())
            return false
        val cfg = ctx.getSharedPreferences("phonelink_cfg",
            Context.MODE_PRIVATE)
        val current = HashMap(cfg.all)
        val edit = cfg.edit()
        if (cfgObj == null) {
            // Rules-only legacy object: just restore the rules below.
            val rulesOnly = root.optString("rules", "")
            if (rulesOnly.isNotEmpty()) {
                ctx.getSharedPreferences("phonelink_auto", Context.MODE_PRIVATE)
                    .edit().putString("rules", rulesOnly).apply()
                Automation.refreshWatchers(ctx)
            }
            return rulesOnly.isNotEmpty()
        }
        val keys = cfgObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (secret(k)) continue
            putCoerced(edit, current, k, cfgObj.get(k))
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
