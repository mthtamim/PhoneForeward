package com.autotap.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All local storage: scripts, selection, panel settings. No account, no cloud. */
object Store {
    private const val PREFS = "autotap"
    private const val KEY_SCRIPTS = "scripts_json"
    private const val KEY_SCRIPTS_BAK = "scripts_json_bak"
    private const val KEY_SELECTED = "selected_script_id"
    private const val KEY_EDIT = "edit_script_id"
    private const val KEY_PANEL_COLLAPSED = "panel_collapsed_default"
    private const val KEY_PANEL_SMALL = "panel_small"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getScripts(ctx: Context): MutableList<Script> {
        val raw = prefs(ctx).getString(KEY_SCRIPTS, null) ?: return mutableListOf()
        parseScripts(raw)?.let { return it }
        // Main value is corrupt: fall back to the previous good value.
        val bak = prefs(ctx).getString(KEY_SCRIPTS_BAK, null)
        if (bak != null) parseScripts(bak)?.let { return it }
        return mutableListOf()
    }

    /** Parses the scripts array; null when the JSON itself is broken. */
    private fun parseScripts(raw: String): MutableList<Script>? {
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Script>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Script.fromJson(it)) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun saveScripts(ctx: Context, scripts: List<Script>) {
        val arr = JSONArray()
        scripts.forEach { arr.put(it.toJson()) }
        val editor = prefs(ctx).edit()
        // Keep the previous value as a fallback so one corrupt write can
        // never take every script with it.
        val current = prefs(ctx).getString(KEY_SCRIPTS, null)
        if (current != null && parseScripts(current) != null) {
            editor.putString(KEY_SCRIPTS_BAK, current)
        }
        editor.putString(KEY_SCRIPTS, arr.toString()).apply()
        Backup.schedule(ctx)
    }

    fun getScript(ctx: Context, id: String): Script? = getScripts(ctx).firstOrNull { it.id == id }

    /** All scripts as one JSON array string (export / PC editor / QR share). */
    fun exportAll(ctx: Context): String {
        val arr = JSONArray()
        getScripts(ctx).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** Imports scripts from a JSON array string; returns how many were added. */
    fun importJson(ctx: Context, json: String): Int {
        return try {
            val arr = JSONArray(json)
            val scripts = getScripts(ctx)
            var count = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = Script.fromJson(o)
                // Keep ids unique so an import never overwrites a local script.
                if (scripts.any { it.id == s.id }) {
                    val copy = Script.fromJson(o).let {
                        Script(Script.newId(), it.name + " (আমদানি)").apply {
                            steps.addAll(it.steps)
                            loopCount = it.loopCount; globalMinutes = it.globalMinutes
                        }
                    }
                    scripts.add(copy)
                } else {
                    scripts.add(s)
                }
                count++
            }
            if (count > 0) saveScripts(ctx, scripts)
            count
        } catch (e: Exception) {
            0
        }
    }

    fun upsert(ctx: Context, script: Script) {
        // Keep a short version history per script so a bad edit is undoable.
        try {
            val prev = getScript(ctx, script.id)
            if (prev != null && prev.toJson().toString() != script.toJson().toString()) {
                pushHistory(ctx, script.id, prev.toJson().toString())
            }
        } catch (e: Exception) { /* history is best-effort */ }
        upsertRaw(ctx, script)
    }

    private fun upsertRaw(ctx: Context, script: Script) {
        val all = getScripts(ctx)
        val idx = all.indexOfFirst { it.id == script.id }
        if (idx >= 0) all[idx] = script else all.add(script)
        saveScripts(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        saveScripts(ctx, getScripts(ctx).filter { it.id != id })
        if (selectedId(ctx) == id) setSelectedId(ctx, null)
    }

    /** Expanded-panel width in dp (user-resizable, v5.3). */
    fun panelBoxWidthDp(ctx: Context): Int = prefs(ctx).getInt("panel_box_w_dp", 300)

    fun setPanelBoxWidthDp(ctx: Context, dp: Int) {
        prefs(ctx).edit().putInt("panel_box_w_dp", dp.coerceIn(220, 400)).apply()
    }

    fun selectedId(ctx: Context): String? = prefs(ctx).getString(KEY_SELECTED, null)

    fun setSelectedId(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    fun selectedScript(ctx: Context): Script? {
        val id = selectedId(ctx) ?: return getScripts(ctx).firstOrNull()
        return getScript(ctx, id) ?: getScripts(ctx).firstOrNull()
    }

    /** Script currently open in the point editor overlay. */
    fun editScriptId(ctx: Context): String? = prefs(ctx).getString(KEY_EDIT, null)

    fun setEditScriptId(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_EDIT, id).apply()
    }

    fun panelCollapsedDefault(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PANEL_COLLAPSED, true)

    fun setPanelCollapsedDefault(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PANEL_COLLAPSED, v).apply()
    }

    fun panelSmall(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PANEL_SMALL, true)

    fun setPanelSmall(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PANEL_SMALL, v).apply()
    }

    // ---- v3 settings ----

    /** Floating panel opacity percent: 100 / 80 / 60. */
    fun panelOpacity(ctx: Context): Int = prefs(ctx).getInt("panel_opacity", 100)
    fun setPanelOpacity(ctx: Context, v: Int) { prefs(ctx).edit().putInt("panel_opacity", v).apply() }

    /** When true the floating panel cannot be dragged. */
    fun panelLocked(ctx: Context): Boolean = prefs(ctx).getBoolean("panel_locked", false)
    fun setPanelLocked(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("panel_locked", v).apply() }

    /** Auto-hide the panel to a tiny dot after this many seconds of a run (0 = never). */
    fun panelAutoHideSec(ctx: Context): Int = prefs(ctx).getInt("panel_autohide", 0)
    fun setPanelAutoHideSec(ctx: Context, v: Int) { prefs(ctx).edit().putInt("panel_autohide", v).apply() }

    /** Hard cap for ANY run, in minutes (0 = no cap). Safety setting. */
    fun safetyCapMinutes(ctx: Context): Int = prefs(ctx).getInt("safety_cap", 0)
    fun setSafetyCapMinutes(ctx: Context, v: Int) { prefs(ctx).edit().putInt("safety_cap", v).apply() }

    /** Stop a run when the battery is low and not charging. */
    fun batteryGuard(ctx: Context): Boolean = prefs(ctx).getBoolean("battery_guard", false)
    fun setBatteryGuard(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("battery_guard", v).apply() }

    /** Packages where scripts must never tap (e.g. banking apps). */
    fun blockedPackages(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("blocked_packages", emptySet()) ?: emptySet()

    fun setBlockedPackages(ctx: Context, v: Set<String>) {
        prefs(ctx).edit().putStringSet("blocked_packages", v).apply()
    }

    /** Summary of the most recent run, shown on the Home tab. */
    fun lastReport(ctx: Context): String? = prefs(ctx).getString("last_report", null)
    fun setLastReport(ctx: Context, v: String?) { prefs(ctx).edit().putString("last_report", v).apply() }

    /** 4-digit PIN guarding the PC editor; generated once and kept. */
    fun pcPin(ctx: Context): String {
        val p = prefs(ctx)
        var pin = p.getString("pc_pin", null)
        if (pin.isNullOrEmpty()) {
            pin = (1000..9999).random().toString()
            p.edit().putString("pc_pin", pin).apply()
        }
        return pin
    }

    /** Path of the newest failure screenshot (search step not found), if any. */
    fun lastFailureShot(ctx: Context): String? = prefs(ctx).getString("last_failure_shot", null)
    fun setLastFailureShot(ctx: Context, v: String?) {
        prefs(ctx).edit().putString("last_failure_shot", v).apply()
    }

    // ---- v4: version history per script ----

    private fun historyKey(id: String) = "history_$id"

    fun pushHistory(ctx: Context, id: String, json: String) {
        val key = historyKey(id)
        val arr = try { JSONArray(prefs(ctx).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("json", json))
        while (arr.length() > 8) arr.remove(0)
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    /** Newest-first list of (timestamp, scriptJson) snapshots for undo / restore. */
    fun history(ctx: Context, id: String): List<Pair<Long, String>> {
        val arr = try { JSONArray(prefs(ctx).getString(historyKey(id), "[]")) } catch (e: Exception) { JSONArray() }
        val out = mutableListOf<Pair<Long, String>>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optLong("at") to o.optString("json"))
        }
        return out
    }

    /** Restores the newest snapshot (the Undo action). Returns false when empty. */
    fun undoOnce(ctx: Context, id: String): Boolean {
        val key = historyKey(id)
        val arr = try { JSONArray(prefs(ctx).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) return false
        val last = arr.optJSONObject(arr.length() - 1) ?: return false
        arr.remove(arr.length() - 1)
        prefs(ctx).edit().putString(key, arr.toString()).apply()
        val script = try { Script.fromJson(JSONObject(last.optString("json"))) } catch (e: Exception) { return false }
        upsertRaw(ctx, script)
        return true
    }

    // ---- v4: lifetime stats ----

    fun lifetimeRuns(ctx: Context): Int = prefs(ctx).getInt("life_runs", 0)
    fun lifetimeTaps(ctx: Context): Long = prefs(ctx).getLong("life_taps", 0)
    fun lifetimeSeconds(ctx: Context): Long = prefs(ctx).getLong("life_seconds", 0)

    fun addLifetime(ctx: Context, taps: Int, seconds: Long) {
        val p = prefs(ctx)
        p.edit()
            .putInt("life_runs", p.getInt("life_runs", 0) + 1)
            .putLong("life_taps", p.getLong("life_taps", 0) + taps)
            .putLong("life_seconds", p.getLong("life_seconds", 0) + seconds)
            .apply()
    }

    // ---- v4: misc settings ----

    fun soundOnFound(ctx: Context): Boolean = prefs(ctx).getBoolean("sound_found", false)
    fun setSoundOnFound(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("sound_found", v).apply() }
    fun soundOnFinish(ctx: Context): Boolean = prefs(ctx).getBoolean("sound_finish", true)
    fun setSoundOnFinish(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("sound_finish", v).apply() }
    fun vibrateOnFinish(ctx: Context): Boolean = prefs(ctx).getBoolean("vibrate_finish", false)
    fun setVibrateOnFinish(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("vibrate_finish", v).apply() }

    /** Edit-overlay marker size: 0 small, 1 medium, 2 large. */
    fun markerSize(ctx: Context): Int = prefs(ctx).getInt("marker_size", 1)
    fun setMarkerSize(ctx: Context, v: Int) { prefs(ctx).edit().putInt("marker_size", v).apply() }
    /** Edit-overlay marker style: 0 numbered circle, 1 plain dot. */
    fun markerStyle(ctx: Context): Int = prefs(ctx).getInt("marker_style", 0)
    fun setMarkerStyle(ctx: Context, v: Int) { prefs(ctx).edit().putInt("marker_style", v).apply() }
    /** Edit-overlay toolbar side: 0 center, 1 left, 2 right. */
    fun toolbarSide(ctx: Context): Int = prefs(ctx).getInt("toolbar_side", 0)
    fun setToolbarSide(ctx: Context, v: Int) { prefs(ctx).edit().putInt("toolbar_side", v).apply() }

    /** Floating panel form: 0 box, 1 bubble. */
    fun panelForm(ctx: Context): Int = prefs(ctx).getInt("panel_form", 1)
    fun setPanelForm(ctx: Context, v: Int) { prefs(ctx).edit().putInt("panel_form", v).apply() }

    fun dockEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("dock_enabled", false)
    fun setDockEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("dock_enabled", v).apply() }

    fun callPauseEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("call_pause", false)
    fun setCallPauseEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("call_pause", v).apply() }

    /** App-lock PIN (empty = off). Stored locally only. */
    fun appPin(ctx: Context): String = prefs(ctx).getString("app_pin", "") ?: ""
    fun setAppPin(ctx: Context, v: String) { prefs(ctx).edit().putString("app_pin", v).apply() }

    /** Setup toolbar scale (user-resizable overlay). */
    fun overlayScale(ctx: Context): Float =
        prefs(ctx).getFloat("overlay_scale", 1f).coerceIn(0.7f, 1.6f)
    fun setOverlayScale(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat("overlay_scale", v.coerceIn(0.7f, 1.6f)).apply()
    }

    // ---- v5 run/engine settings ----
    fun tapVerify(ctx: Context): Boolean = prefs(ctx).getBoolean("tap_verify", false)
    fun setTapVerify(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("tap_verify", v).apply() }
    fun tapRetry(ctx: Context): Int = prefs(ctx).getInt("tap_retry", 2).coerceIn(0, 5)
    fun setTapRetry(ctx: Context, v: Int) { prefs(ctx).edit().putInt("tap_retry", v.coerceIn(0, 5)).apply() }
    fun stuckGuard(ctx: Context): Boolean = prefs(ctx).getBoolean("stuck_guard", true)
    fun setStuckGuard(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("stuck_guard", v).apply() }
    fun stuckLimit(ctx: Context): Int = prefs(ctx).getInt("stuck_limit", 6).coerceIn(3, 15)
    fun setStuckLimit(ctx: Context, v: Int) { prefs(ctx).edit().putInt("stuck_limit", v.coerceIn(3, 15)).apply() }
    fun precisionTiming(ctx: Context): Boolean = prefs(ctx).getBoolean("precision_timing", false)
    fun setPrecisionTiming(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("precision_timing", v).apply() }
    fun shakeStop(ctx: Context): Boolean = prefs(ctx).getBoolean("shake_stop", false)
    fun setShakeStop(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("shake_stop", v).apply() }
    fun apiEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("api_enabled", false)
    fun setApiEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("api_enabled", v).apply() }
    fun shizukuEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("shizuku_enabled", false)
    fun setShizukuEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("shizuku_enabled", v).apply() }
    fun runVideo(ctx: Context): Boolean = prefs(ctx).getBoolean("run_video", false)
    fun setRunVideo(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("run_video", v).apply() }
    fun gridSnap(ctx: Context): Boolean = prefs(ctx).getBoolean("grid_snap", false)
    fun setGridSnap(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("grid_snap", v).apply() }
    fun templateTolerance(ctx: Context): Float = prefs(ctx).getFloat("tpl_tolerance", 0.85f).coerceIn(0.6f, 0.99f)
    fun setTemplateTolerance(ctx: Context, v: Float) { prefs(ctx).edit().putFloat("tpl_tolerance", v.coerceIn(0.6f, 0.99f)).apply() }
    fun calibDx(ctx: Context): Int = prefs(ctx).getInt("calib_dx", 0)
    fun calibDy(ctx: Context): Int = prefs(ctx).getInt("calib_dy", 0)
    fun setCalib(ctx: Context, dx: Int, dy: Int) {
        prefs(ctx).edit().putInt("calib_dx", dx).putInt("calib_dy", dy).apply()
    }
    /** Floating bubble size (panel + dock), dp. */
    fun bubbleSizeDp(ctx: Context): Int = prefs(ctx).getInt("bubble_size", 58).coerceIn(40, 96)
    fun setBubbleSizeDp(ctx: Context, v: Int) { prefs(ctx).edit().putInt("bubble_size", v.coerceIn(40, 96)).apply() }

    // ---- v5 per-script stats + daily counters + heat points ----
    private fun dayKey(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun dayCount(ctx: Context, id: String): Int = prefs(ctx).getInt("daycount_${id}_${dayKey()}", 0)
    fun incDayCount(ctx: Context, id: String) {
        prefs(ctx).edit().putInt("daycount_${id}_${dayKey()}", dayCount(ctx, id) + 1).apply()
    }
    /** stats string: runs|completes|fails|lastFailStepIndex1Based|lastRunEpoch */
    fun scriptStats(ctx: Context, id: String): IntArray {
        val raw = prefs(ctx).getString("stat_$id", null) ?: return intArrayOf(0, 0, 0, 0)
        val parts = raw.split("|")
        return intArrayOf(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
            parts.getOrNull(3)?.toIntOrNull() ?: 0
        )
    }
    fun bumpScriptStat(ctx: Context, id: String, completed: Boolean, failStep: Int) {
        val s = scriptStats(ctx, id)
        val runs = s[0] + 1
        val completes = s[1] + if (completed) 1 else 0
        val fails = s[2] + if (completed) 0 else 1
        val lastFail = if (completed) s[3] else failStep
        prefs(ctx).edit().putString("stat_$id", "$runs|$completes|$fails|$lastFail").apply()
    }
    fun resetScriptStat(ctx: Context, id: String) {
        prefs(ctx).edit().remove("stat_$id").remove("heat_$id").apply()
    }
    fun addHeatPoint(ctx: Context, id: String, x: Int, y: Int) {
        try {
            val arr = JSONArray(prefs(ctx).getString("heat_$id", "[]"))
            arr.put(JSONArray().put(x).put(y))
            while (arr.length() > 400) arr.remove(0)
            prefs(ctx).edit().putString("heat_$id", arr.toString()).apply()
        } catch (e: Exception) { /* heat map is best-effort */ }
    }
    fun heatPoints(ctx: Context, id: String): List<IntArray> {
        val out = mutableListOf<IntArray>()
        try {
            val arr = JSONArray(prefs(ctx).getString("heat_$id", "[]"))
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                out.add(intArrayOf(pair.optInt(0), pair.optInt(1)))
            }
        } catch (e: Exception) { /* ignore */ }
        return out
    }

    /** Edit-overlay draft (placed markers as JSON) per script id. */
    fun editDraft(ctx: Context, id: String): String? = prefs(ctx).getString("draft_$id", null)
    fun setEditDraft(ctx: Context, id: String, json: String?) {
        prefs(ctx).edit().putString("draft_$id", json).apply()
    }
}
