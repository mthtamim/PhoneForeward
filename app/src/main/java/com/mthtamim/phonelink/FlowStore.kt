package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Flow persistence for PhoneLink v2.24.0 (the Lab merge).
 * DELIBERATELY SEPARATE from PhoneLink's rule store (Automation,
 * prefs "phonelink_auto"): flows live in prefs "phonelink_flows"
 * plus one JSON file per flow under filesDir/flows/, with their
 * own log, variables, trigger state, per-flow logs and stats.
 * This file NEVER reads or writes rules: rule_enable/disable/run
 * and the rule_enabled condition go through Automation instead,
 * so the Flow engine can never fire, edit or corrupt a rule.
 */
object FlowStore {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_flows", Context.MODE_PRIVATE)

    /**
     * v1.4.3 service heartbeat. FlowService writes the current time
     * on every 60s tick and clears it on destroy; Home reads it to
     * show whether the service is actually alive, instead of
     * trusting the "Automation on" toggle alone (XOS can kill the
     * service while the toggle stays on).
     */
    fun serviceLastTick(ctx: Context): Long =
        prefs(ctx).getLong("service_last_tick", 0L)

    /** Flows-tab service switch. Default OFF: unlike Lab's
     *  automation switch (default on), a fresh PhoneLink install
     *  must not run flows in background before the user opts in. */
    fun flowServiceOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("flow_service_on", false)

    fun setFlowServiceOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("flow_service_on", on).apply()
    }

    fun setServiceLastTick(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong("service_last_tick", at).apply()
    }

    // ---------------- v1.5.0: peer relay, MQTT, lost mode, app lock ----------------

    /** Peer pair code for notify_peer / speak_peer / command_peer.
     *  Same derivation as PhoneLink (SHA-256 topics on ntfy.sh),
     *  so a Lab can talk to a PhoneLink peer sharing this code.
     *  Empty = peer actions log "not configured" and do nothing. */

    /** Default MQTT broker for mqtt_publish (e.g. tcp://host:1883).
     *  Empty = the action logs "not configured" and does nothing. */

    /** Lost-mode flag for lost_on / lost_off. NOTE: this is only
     *  the flag + a local notification. PhoneLink's lost-mode
     *  tracking (periodic location/photo/sound relay to a peer)
     *  is a whole subsystem and is NOT ported in v1.5.0. */

    /** App-lock PIN, stored as a SHA-256 hash only (never the PIN).
     *  Empty = no PIN set, so lock enforcement stays inactive. */
            ?: mutableSetOf()

    /** Grace window after a correct PIN: the app may return
     *  without re-prompting until this timestamp (ms). */

    fun log(ctx: Context, msg: String) {
        val p = prefs(ctx)
        val old = p.getString("log", "") ?: ""
        val lines = (old.split("\n").filter { it.isNotEmpty() } + msg).takeLast(300)
        p.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun recentLog(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("log", "") ?: ""
        return raw.split("\n").filter { it.isNotEmpty() }
    }

    fun clearLog(ctx: Context) {
        prefs(ctx).edit().putString("log", "").apply()
    }

    fun getVar(ctx: Context, name: String): String =
        prefs(ctx).getString("var_" + name, "") ?: ""

    fun setVar(ctx: Context, name: String, value: String) {
        prefs(ctx).edit().putString("var_" + name, value).apply()
    }

    fun deleteVar(ctx: Context, name: String) {
        prefs(ctx).edit().remove("var_" + name).apply()
    }

    fun lastFired(ctx: Context, key: String): Long =
        prefs(ctx).getLong("fired_" + key, 0L)

    fun markFired(ctx: Context, key: String, now: Long) {
        prefs(ctx).edit().putLong("fired_" + key, now).apply()
    }

    // ---------------- v1.4.0: generic trigger state ----------------

    /** Last observed value for a state trigger (edge detection).
     *  Empty string means "never observed yet" — callers must not
     *  fire on the first sighting, only on a real change. */
    fun stateStr(ctx: Context, key: String): String =
        prefs(ctx).getString("state_" + key, "") ?: ""

    fun setStateStr(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString("state_" + key, value).apply()
    }

    // ---------------- flows ----------------

    private fun flowsDir(ctx: Context): File {
        val d = File(ctx.filesDir, "flows")
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    fun flowFiles(ctx: Context): List<File> {
        val files = flowsDir(ctx).listFiles()
        if (files == null) {
            return emptyList()
        }
        return files.filter { it.name.endsWith(".json") }.sortedBy { it.name }
    }

    fun loadFlow(ctx: Context, id: String): JSONObject? {
        return try {
            val f = File(flowsDir(ctx), id + ".json")
            if (!f.exists()) {
                null
            } else {
                JSONObject(f.readText())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveFlow(ctx: Context, flow: JSONObject) {
        val id = flow.optString("id")
        if (id.isEmpty()) {
            return
        }
        try {
            File(flowsDir(ctx), id + ".json").writeText(flow.toString())
        } catch (_: Exception) {
        }
    }

    fun deleteFlow(ctx: Context, id: String) {
        try {
            File(flowsDir(ctx), id + ".json").delete()
        } catch (_: Exception) {
        }
    }

    /** v1.4.1: all saved flows as parsed JSON (background dispatch
     *  iterates this; a broken file is skipped, never fatal). */
    fun flows(ctx: Context): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (f in flowFiles(ctx)) {
            try {
                out.add(JSONObject(f.readText()))
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** v1.4.1: per-flow ON/OFF, mirroring setEnabled for rules.
     *  Missing "enabled" reads as true everywhere (old flows stay on). */
    fun setFlowEnabled(ctx: Context, id: String, on: Boolean) {
        val flow = loadFlow(ctx, id) ?: return
        flow.put("enabled", on)
        saveFlow(ctx, flow)
    }

    // ---------------- v1.3.0: per-flow logs + stats ----------------

    private fun flowLogFile(ctx: Context, flowId: String): File {
        val d = File(ctx.filesDir, "flow_logs")
        if (!d.exists()) {
            d.mkdirs()
        }
        val safe = flowId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(d, safe + ".log")
    }

    fun flowLogAppend(ctx: Context, flowId: String, msg: String) {
        try {
            val f = flowLogFile(ctx, flowId)
            val old = if (f.exists()) f.readLines().filter { it.isNotEmpty() } else emptyList()
            val lines = (old + msg).takeLast(300)
            f.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    fun flowLog(ctx: Context, flowId: String): List<String> {
        return try {
            val f = flowLogFile(ctx, flowId)
            if (f.exists()) f.readLines().filter { it.isNotEmpty() } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun flowStatsBump(ctx: Context, flowId: String) {
        val p = prefs(ctx)
        val key = "stat_" + flowId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        p.edit()
            .putInt(key + "_count", p.getInt(key + "_count", 0) + 1)
            .putLong(key + "_last", System.currentTimeMillis())
            .apply()
    }

    fun flowStats(ctx: Context, flowId: String): Pair<Int, Long> {
        val p = prefs(ctx)
        val key = "stat_" + flowId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return p.getInt(key + "_count", 0) to p.getLong(key + "_last", 0L)
    }
}
