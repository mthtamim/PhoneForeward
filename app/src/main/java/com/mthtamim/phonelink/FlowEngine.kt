package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar

/**
 * Flow engine for PhoneLink v2.24.0 (the Lab merge).
 *
 * What runs here: flow event dispatch (onEvent), the service tick
 * (time + battery triggers), sensor ticks, trigger-param matching,
 * local constraint evaluation, local action execution, and the
 * flowchart walker (runFlowSyncFrom / walkFrom) with if/else,
 * loop/for_each, fork and call_flow control flow, variables and
 * per-flow logs/stats.
 *
 * What does NOT run here, by design: PhoneLink RULES. The Flow
 * engine never reads Automation's rule store — rules are owned
 * solely by PhoneLink's Automation engine. Rule-control actions
 * (rule_enable/disable/run) and the rule_enabled condition reach
 * rules only through Automation's public APIs, never by editing
 * rule JSON directly.
 *
 * PhoneLink reuse (never duplicated): peer relay -> Relay,
 * redial -> Redial, MQTT -> MqttManager, app lock -> AppLocker,
 * QR show -> QrShow + QrShowActivity, FocusLock token ->
 * Config.focusLockToken, privileged shell -> ShizukuHelper.
 * answer_call/end_call use Shizuku keyevents ONLY in this merge:
 * PhoneLink has no CallShield token settings card, so the
 * CallShield bridge fallback from Lab is NOT available here and
 * is logged plainly as unavailable, never faked.
 *
 * NOT ported in this merge (absent from FlowCatalog, so a flow
 * can never mistake them for working): the lost-tracking
 * subsystem and the AutoTap engine.
 */
object FlowEngine {

    const val FOCUSLOCK_PKG = "com.focuslock.app"
    const val FOCUSLOCK_ACTION = "com.focuslock.app.AUTOMATION"
    const val NOTIF_CHANNEL_ID = "phonelink_flows"

    /** Deliberately empty in this merge: every action Lab
     *  dropped and later implemented is either ported (torch
     *  level, install, reboot, perm, pin, qr_scan, wol, ping,
     *  backup/restore, .flo import) or routed to a PhoneLink
     *  implementation (peer, redial, MQTT, app lock, QR show).
     *  The two Lab exclusions — lost_on/lost_off and
     *  autotap_control — are absent from FlowCatalog instead of
     *  sitting here, so they can never be picked. The check in
     *  runAction stays for future drops. */
    val DROPPED_ACTIONS = setOf<String>()

    /** v1.4.0: triggers the tick DOES evaluate now (state edges).
     *  The rest of the old ignored set stays event-driven (receivers,
     *  listeners, SensorHub) or Lab-specific and unchanged. */
    val TICK_IGNORED_TRIGGERS = setOf(
        "sunrise", "sunset", "shake", "flip_down", "flip_up",
        "proximity_near", "proximity_far", "light_below", "light_above",
        "headphone_in", "headphone_out", "usb_connected",
        "geofence_in", "geofence_out", "steps_goal", "activity",
        "weather", "weather_forecast", "nfc_tag", "screen_text",
        "peer_online", "peer_offline", "peer_battery_below",
        "volume_up", "volume_down", "qr_scanned", "webhook",
        "magnet_above", "pressure_above",
        "hotspot_on", "hotspot_off", "rule_toggled",
        "screenshot_taken", "photo_taken", "video_taken",
        "app_updated", "sms_delivered", "orientation_changed", "pick_up",
        "bt_near", "bt_far", "sim_changed", "ip_changed", "camera_used",
        "split_screen", "notify_button",
        "ui_click", "var_changed", "file_changed",
        "mqtt_message", "applock_failed",
        "study_guard_continuous", "study_guard_daily", "screen_on_continuous",
        "clipboard", "airplane_on", "airplane_off",
        "storage_low", "timezone_changed", "locale_changed",
        "bt_connected", "bt_disconnected", "sms_sent",
        "notif_received", "notif_cleared"
    )

    /** v1.4.0: SensorHub kind -> rule trigger types it feeds. */
    val SENSOR_TICK_TYPES = mapOf(
        "light" to setOf("light_below", "light_above"),
        "proximity" to setOf("proximity_near", "proximity_far"),
        "magnetic" to setOf("magnet_above"),
        "pressure" to setOf("pressure_above"),
        "temp" to setOf("temp"),
        "humidity" to setOf("humidity"),
        "heart" to setOf("heart_rate"),
        "hinge" to setOf("hinge_angle"),
        "accel" to setOf("accel"),
        "sound" to setOf("sound_level")
    )

    private var stopRequested = false
    private var runDepth = 0

    private class BreakSignal : Exception()
    private class StopSignal : Exception()

    /**
     * v1.3.0 live execution highlight: the walker calls this with
     * each node id it visits. The editor sets it while open (and
     * posts to the UI thread itself); it is null the rest of the
     * time. Never called from rule execution, only the flow walker.
     */
    @Volatile
    var nodeListener: ((String) -> Unit)? = null

    /** v1.3.0 running-flow cancellation (stop_flow action): flow
     *  name -> cancel flags of its live runs. A stop sets every
     *  flag for that name; the walker checks at each step. */
    private val runningFlows =
        java.util.concurrent.ConcurrentHashMap<String, MutableList<java.util.concurrent.atomic.AtomicBoolean>>()

    fun requestFlowStop(name: String) {
        val flags = runningFlows[name] ?: return
        synchronized(flags) {
            for (f in flags) {
                f.set(true)
            }
        }
    }

    private fun registerFlowRun(name: String): java.util.concurrent.atomic.AtomicBoolean {
        val flag = java.util.concurrent.atomic.AtomicBoolean(false)
        val list = runningFlows.getOrPut(name) { mutableListOf() }
        synchronized(list) {
            list.add(flag)
        }
        return flag
    }

    private fun unregisterFlowRun(name: String, flag: java.util.concurrent.atomic.AtomicBoolean) {
        val list = runningFlows[name] ?: return
        synchronized(list) {
            list.remove(flag)
            if (list.isEmpty()) {
                runningFlows.remove(name)
            }
        }
    }

    /** v1.3.0 last action error on this thread (walker onerror
     *  handling): runAction's catch sets it; the walker reads and
     *  clears it after each action node. */
    private val lastActionErrorLocal = ThreadLocal<String?>()

    /** v1.3.0 flow-local variable scope (see vGet/vSet below). */
    private val flowStateLocal = ThreadLocal<FlowEngine.FlowState>()

    /**
     * Variable read: a flow-local value wins over the global store.
     * Locals exist only while a flow walk is on this thread (call_flow
     * args, set_local); rule execution and plain threads see globals.
     */
    fun vGet(ctx: Context, name: String): String {
        val local = flowStateLocal.get()?.locals?.get(name)
        if (local != null) {
            return local
        }
        return FlowStore.getVar(ctx, name)
    }

    /**
     * Variable write: if the name is already a local of the current
     * flow run it stays local, otherwise it goes to the global store.
     * Legacy actions (set_var/inc_var/calc_var) always write global;
     * set_local is the action that creates a local.
     */
    fun vSet(ctx: Context, name: String, value: String) {
        val state = flowStateLocal.get()
        if (state != null && state.locals.containsKey(name)) {
            state.locals[name] = value
        } else {
            FlowStore.setVar(ctx, name, value)
        }
    }

    /**
     * v1.3.0 ${...} substitution for action params (walker + rule
     * paths call this before runAction). A bare ${name} reads the
     * variable (locals first); anything else is evaluated as
     * arithmetic after identifiers are replaced by their numeric
     * values (missing/invalid -> 0). Text without ${...} passes
     * through untouched, so regex $ anchors and {key} templates
     * (fill()) are unaffected.
     */
    fun substituteVars(ctx: Context, text: String): String {
        if (!text.contains("\${")) {
            return text
        }
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("\${", i)
            if (start < 0) {
                out.append(text.substring(i))
                break
            }
            out.append(text.substring(i, start))
            val end = text.indexOf("}", start + 2)
            if (end < 0) {
                out.append(text.substring(start))
                break
            }
            val inner = text.substring(start + 2, end).trim()
            if (inner.matches(Regex("[A-Za-z0-9_]+"))) {
                out.append(vGet(ctx, inner))
            } else {
                var expr = inner
                val idPattern = Regex("[A-Za-z0-9_]+")
                expr = idPattern.replace(expr) { m ->
                    vGet(ctx, m.value).toDoubleOrNull()?.toString() ?: "0"
                }
                out.append(
                    try {
                        val v = CalcParser(expr).parse()
                        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                    } catch (_: Exception) {
                        ""
                    }
                )
            }
            i = end + 1
        }
        return out.toString()
    }

    fun requestStop() {
        stopRequested = true
    }

    // ---------------- event entry (mirrors Automation.onEvent) ----------------

    fun onEvent(ctx: Context, event: String, data: JSONObject = JSONObject()) {
        if (!FlowStore.flowServiceOn(ctx)) {
            return
        }
        Thread {
            try {
                // v1.4.1: flows never ran in background — onEvent only
                // iterated rules, so a shake/notif/screen flow only
                // ever fired from manual Run-now. Dispatch enabled
                // flows whose trigger node matches this event, starting
                // the walk AT that trigger node.
                for (flow in FlowStore.flows(ctx)) {
                    if (!flow.optBoolean("enabled", true)) {
                        continue
                    }
                    val nodes = flow.optJSONArray("nodes") ?: continue
                    for (k in 0 until nodes.length()) {
                        val n = nodes.optJSONObject(k) ?: continue
                        if (n.optString("kind") != "t") {
                            continue
                        }
                        if (n.optString("type") != event) {
                            continue
                        }
                        if (!triggerParamMatches(event, n.optString("param"), data)) {
                            continue
                        }
                        dispatchFlow(ctx, flow, n, event, data)
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** v1.4.1: background flow dispatch (mirrors dispatchRule).
     *  2s debounce per flow trigger node; the run goes to the main
     *  log, the per-flow log and the flow stats via runFlowSyncFrom,
     *  and the event reaches the walk as ${event} / ${event_data}
     *  locals. Disabled flows never reach here (callers check). */
    private fun dispatchFlow(
        ctx: Context,
        flow: JSONObject,
        triggerNode: JSONObject,
        event: String,
        data: JSONObject
    ) {
        val flowId = flow.optString("id")
        val nodeId = triggerNode.optString("id")
        val now = System.currentTimeMillis()
        val last = FlowStore.lastFired(ctx, "deb_flow_" + flowId + "_" + nodeId)
        if (now - last < 2000) {
            return
        }
        FlowStore.markFired(ctx, "deb_flow_" + flowId + "_" + nodeId, now)
        FlowStore.log(ctx, "run flow " + flow.optString("name") + " (" + event + ")")
        Thread {
            try {
                runFlowSyncFrom(
                    ctx, flow, triggerNode, 0,
                    mapOf("event" to event, "event_data" to data.toString())
                )
            } catch (_: Exception) {
            }
        }.start()
    }

    /** The per-rule body of onEvent, shared with sensorTick: 2s
     *  debounce, constraints, run log, actions. */

    /** v1.4.0: sensor threshold/state delivery from FlowTriggers.
     *  `kind` is the SensorHub kind ("light", "proximity", "magnetic",
     *  "pressure", "temp", "humidity", "heart", "hinge", "accel",
     *  "sound"). Threshold crossing is per-rule; state is stored in
     *  FlowStore state keys so a rule fires once per crossing, not
     *  on every reading. */
    fun sensorTick(ctx: Context, kind: String, value: Float) {
        if (!FlowStore.flowServiceOn(ctx)) {
            return
        }
        Thread {
            try {
                val types = SENSOR_TICK_TYPES[kind] ?: return@Thread
                // v1.4.1: sensor flows, same as rules above but with
                // the flow id as the sensorFire state-key namespace.
                for (flow in FlowStore.flows(ctx)) {
                    if (!flow.optBoolean("enabled", true)) {
                        continue
                    }
                    val nodes = flow.optJSONArray("nodes") ?: continue
                    for (k in 0 until nodes.length()) {
                        val n = nodes.optJSONObject(k) ?: continue
                        if (n.optString("kind") != "t") {
                            continue
                        }
                        val type = n.optString("type")
                        if (type !in types) {
                            continue
                        }
                        val data = sensorFire(
                            ctx, flow.optString("id"), type,
                            n.optString("param"), kind, value
                        ) ?: continue
                        if (!triggerParamMatches(type, n.optString("param"), data)) {
                            continue
                        }
                        dispatchFlow(ctx, flow, n, type, data)
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Threshold/edge test for one sensor rule. Returns the event
     *  data on a crossing, null otherwise. First sighting only
     *  records state, never fires. */
    private fun sensorFire(
        ctx: Context,
        ruleId: String,
        type: String,
        param: String,
        kind: String,
        value: Float
    ): JSONObject? {
        val data = JSONObject()
        val key = "sens_" + ruleId + "_" + type
        when (type) {
            "light_below", "light_above" -> {
                val thr = param.toFloatOrNull() ?: 100f
                val side = if (value < thr) "below" else "above"
                val want = if (type == "light_below") "below" else "above"
                if (!edgeTo(ctx, key, side, want)) {
                    return null
                }
                data.put("lux", value)
            }
            "proximity_near", "proximity_far" -> {
                val thr = param.toFloatOrNull() ?: 1f
                val side = if (value < thr) "near" else "far"
                val want = if (type == "proximity_near") "near" else "far"
                if (!edgeTo(ctx, key, side, want)) {
                    return null
                }
                data.put("distance", value)
            }
            "magnet_above" -> {
                val thr = param.toFloatOrNull() ?: 50f
                if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                    return null
                }
                data.put("ut", value)
            }
            "pressure_above" -> {
                val thr = param.toFloatOrNull() ?: 1013f
                if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                    return null
                }
                data.put("hpa", value)
            }
            "temp" -> {
                val thr = param.toFloatOrNull()
                if (thr != null) {
                    if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                        return null
                    }
                } else {
                    val prev = FlowStore.stateStr(ctx, key)
                    if (prev.isEmpty()) {
                        FlowStore.setStateStr(ctx, key, value.toString())
                        return null
                    }
                    FlowStore.setStateStr(ctx, key, value.toString())
                }
                data.put("temp", value)
            }
            "humidity" -> {
                val thr = param.toFloatOrNull()
                if (thr != null) {
                    if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                        return null
                    }
                } else {
                    val prev = FlowStore.stateStr(ctx, key)
                    if (prev.isEmpty()) {
                        FlowStore.setStateStr(ctx, key, value.toString())
                        return null
                    }
                    FlowStore.setStateStr(ctx, key, value.toString())
                }
                data.put("humidity", value)
            }
            "heart_rate" -> {
                val thr = param.toFloatOrNull()
                if (thr != null) {
                    if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                        return null
                    }
                } else {
                    if (value <= 0f) {
                        return null
                    }
                    if (FlowStore.stateStr(ctx, key) == "seen") {
                        return null
                    }
                    FlowStore.setStateStr(ctx, key, "seen")
                }
                data.put("bpm", value)
            }
            "hinge_angle" -> {
                val thr = param.toFloatOrNull()
                if (thr != null) {
                    if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                        return null
                    }
                } else {
                    val prev = FlowStore.stateStr(ctx, key)
                    if (prev.isEmpty()) {
                        FlowStore.setStateStr(ctx, key, value.toString())
                        return null
                    }
                    FlowStore.setStateStr(ctx, key, value.toString())
                }
                data.put("angle", value)
            }
            "accel" -> {
                val thrG = param.toFloatOrNull() ?: 2f
                val thr = thrG * 9.8f
                if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                    return null
                }
                data.put("magnitude", value)
            }
            "sound_level" -> {
                val thr = param.toFloatOrNull() ?: 500f
                if (!edgeTo(ctx, key, if (value > thr) "above" else "below", "above")) {
                    return null
                }
                data.put("amplitude", value)
            }
            else -> return null
        }
        return data
    }

    /** Edge helper: stores the current state always; fires only
     *  when the state BECOMES `want` (first sighting never fires). */
    private fun edgeTo(ctx: Context, key: String, cur: String, want: String): Boolean {
        val prev = FlowStore.stateStr(ctx, key)
        FlowStore.setStateStr(ctx, key, cur)
        if (prev.isEmpty()) {
            return false
        }
        return cur == want && prev != want
    }

    /** Change helper: returns the new value when it changed
     *  (first sighting returns null and only records). */
    private fun changedTo(ctx: Context, key: String, cur: String): String? {
        val prev = FlowStore.stateStr(ctx, key)
        FlowStore.setStateStr(ctx, key, cur)
        if (prev.isEmpty() || prev == cur) {
            return null
        }
        return cur
    }

    /** Manual run: trigger is treated as satisfied, constraints still apply. */

    // ---------------- foreground tick (time + battery triggers) ----------------

    fun tickForeground(ctx: Context) {
        if (!FlowStore.flowServiceOn(ctx)) {
            return
        }
        try {
            val now = System.currentTimeMillis()
            // v1.4.1: time/state flows in background (same tick, same
            // once-per-window semantics via tickTriggerDue; the flow
            // id + node id form the state-key namespace so a flow
            // never shares a rule's fired markers). Event-driven
            // types stay ignored here — they arrive via onEvent.
            for (flow in FlowStore.flows(ctx)) {
                if (!flow.optBoolean("enabled", true)) {
                    continue
                }
                val nodes = flow.optJSONArray("nodes") ?: continue
                for (k in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(k) ?: continue
                    if (n.optString("kind") != "t") {
                        continue
                    }
                    val type = n.optString("type")
                    if (type in TICK_IGNORED_TRIGGERS) {
                        continue
                    }
                    val ns = "flow_" + flow.optString("id") + "_" + n.optString("id")
                    val tickData = tickTriggerDue(ctx, ns, type, n.optString("param"), now)
                        ?: continue
                    FlowStore.log(ctx, "tick flow " + flow.optString("name") + " (" + type + ")")
                    Thread {
                        try {
                            runFlowSyncFrom(
                                ctx, flow, n, 0,
                                mapOf("event" to type, "event_data" to tickData.toString())
                            )
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            }
        } catch (_: Exception) {
        }
        // PhoneLink v2.24.0: NO LabRedial tick here. Flow
        // redial_control sessions are PhoneLink Redial sessions,
        // advanced by PhoneLink's own engine tick — never from Flow.
    }

    private fun tickTriggerDue(
        ctx: Context,
        ruleId: String,
        type: String,
        param: String,
        now: Long
    ): JSONObject? {
        return when (type) {
            "time_at", "time_at_days" -> {
                val time = if (type == "time_at") {
                    param.trim()
                } else {
                    param.substringAfter("time=", "").substringBefore("|").trim()
                }
                if (type == "time_at_days") {
                    val days = param.substringAfter("days=", "").split(",").map { it.trim() }
                    if (!days.contains(dayCodeToday())) {
                        return null
                    }
                }
                val t = parseHm(time)
                if (t < 0) {
                    return null
                }
                val cal = Calendar.getInstance()
                val today = cal.get(Calendar.YEAR).toString() + "-" +
                    cal.get(Calendar.DAY_OF_YEAR).toString()
                val key = "day_" + ruleId + "_" + type + "_" + today
                if (FlowStore.lastFired(ctx, key) > 0) {
                    return null
                }
                val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                if (nowMin in t until t + 65) {
                    FlowStore.markFired(ctx, key, now)
                    return JSONObject()
                }
                return null
            }
            "time_interval" -> {
                val mins = param.toIntOrNull() ?: return null
                val last = FlowStore.lastFired(ctx, "int_" + ruleId)
                if (last == 0L || now - last >= mins * 60_000L) {
                    FlowStore.markFired(ctx, "int_" + ruleId, now)
                    return JSONObject()
                }
                return null
            }
            "date_at" -> {
                val key = "once_" + ruleId
                if (FlowStore.lastFired(ctx, key) > 0) {
                    return null
                }
                val parts = param.trim().split(" ")
                if (parts.size != 2) {
                    return null
                }
                val hm = parseHm(parts[1])
                if (hm < 0) {
                    return null
                }
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(now))
                if (parts[0] == today) {
                    val cal = Calendar.getInstance()
                    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    if (nowMin in hm until hm + 65) {
                        FlowStore.markFired(ctx, key, now)
                        return JSONObject()
                    }
                }
                return null
            }
            "battery_below" -> {
                val want = param.toIntOrNull() ?: return null
                if (batteryPct(ctx) > want) {
                    return null
                }
                return if (edgeOnce(ctx, "batlo_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "battery_above" -> {
                val want = param.toIntOrNull() ?: return null
                if (batteryPct(ctx) < want) {
                    return null
                }
                return if (edgeOnce(ctx, "bathi_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "battery_full" -> {
                if (batteryPct(ctx) < 100) {
                    return null
                }
                return if (edgeOnce(ctx, "batfull_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "battery_temp_above" -> {
                val want = param.toFloatOrNull() ?: return null
                if (batteryTempC(ctx) < want) {
                    return null
                }
                return if (edgeOnce(ctx, "battemp_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "charging_on" -> {
                if (!isCharging(ctx)) {
                    return null
                }
                return if (edgeOnce(ctx, "chgon_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "charging_off" -> {
                if (isCharging(ctx)) {
                    return null
                }
                return if (edgeOnce(ctx, "chgoff_" + ruleId, now, 60 * 60_000L)) JSONObject() else null
            }
            "stopwatch" -> {
                val secs = param.toIntOrNull() ?: return null
                val start = FlowStore.lastFired(ctx, "sw_start")
                if (start == 0L) {
                    return null
                }
                if (now - start < secs * 1000L) {
                    return null
                }
                return if (edgeOnce(ctx, "swfire_" + ruleId, now, 24 * 60 * 60_000L)) JSONObject() else null
            }
            // ---- v1.4.0 state triggers (fire on change only) ----
            "device_doze" -> {
                val pm = ctx.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
                val cur = if (pm.isDeviceIdleMode) "on" else "off"
                if (!edgeTo(ctx, "doze_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("active", "true")
            }
            "device_interactive" -> {
                val pm = ctx.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
                val cur = if (pm.isInteractive) "on" else "off"
                if (!edgeTo(ctx, "inter_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("active", "true")
            }
            "display_power_mode" -> {
                val pm = ctx.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
                val mode = when {
                    pm.isDeviceIdleMode -> "doze"
                    pm.isInteractive -> "on"
                    else -> "off"
                }
                val changed = changedTo(ctx, "dispmode_" + ruleId, mode) ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("mode", changed)
            }
            "dark_on", "dark_off" -> {
                val um = ctx.getSystemService(Context.UI_MODE_SERVICE)
                    as android.app.UiModeManager
                val cur = if (um.nightMode == android.app.UiModeManager.MODE_NIGHT_YES) {
                    "on"
                } else {
                    "off"
                }
                val want = if (type == "dark_on") "on" else "off"
                if (!edgeTo(ctx, "dark_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("night", cur)
            }
            "dnd_on", "dnd_off" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                val cur = if (nm.currentInterruptionFilter ==
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    "off"
                } else {
                    "on"
                }
                val want = if (type == "dnd_on") "on" else "off"
                if (!edgeTo(ctx, "dnd_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("dnd", cur)
            }
            "vpn_on", "vpn_off" -> {
                val cur = if (activeTransport(ctx) == "vpn") "on" else "off"
                val want = if (type == "vpn_on") "on" else "off"
                if (!edgeTo(ctx, "vpn_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("vpn", cur)
            }
            "roaming_on", "roaming_off" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val cur = if (tm.isNetworkRoaming) "on" else "off"
                val want = if (type == "roaming_on") "on" else "off"
                if (!edgeTo(ctx, "roam_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("roaming", cur)
            }
            "music_started", "music_stopped" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val cur = if (am.isMusicActive) "on" else "off"
                val want = if (type == "music_started") "on" else "off"
                if (!edgeTo(ctx, "music_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("music", cur)
            }
            "battery_saver_on", "battery_saver_off" -> {
                val pm = ctx.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
                val cur = if (pm.isPowerSaveMode) "on" else "off"
                val want = if (type == "battery_saver_on") "on" else "off"
                if (!edgeTo(ctx, "bsaver_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("saver", cur)
            }
            "wireless_charging" -> {
                val intent = ctx.registerReceiver(
                    null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val plugged = intent?.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED, -1
                ) ?: -1
                val cur = if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                    "on"
                } else {
                    "off"
                }
                if (!edgeTo(ctx, "wcharge_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("wireless", "true")
            }
            "screen_locked" -> {
                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE)
                    as android.app.KeyguardManager
                val cur = if (km.isKeyguardLocked) "on" else "off"
                if (!edgeTo(ctx, "slock_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("locked", "true")
            }
            "mobile_data_on", "mobile_data_off" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val cur = if (tm.isDataEnabled) "on" else "off"
                val want = if (type == "mobile_data_on") "on" else "off"
                if (!edgeTo(ctx, "mdata_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("data", cur)
            }
            "call_active" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val cur = if (tm.callState ==
                    android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                ) {
                    "on"
                } else {
                    "off"
                }
                if (!edgeTo(ctx, "callact_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("active", "true")
            }
            "gps_on", "gps_off" -> {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
                    as android.location.LocationManager
                val cur = try {
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        "on"
                    } else {
                        "off"
                    }
                } catch (_: SecurityException) {
                    return null
                }
                val want = if (type == "gps_on") "on" else "off"
                if (!edgeTo(ctx, "gps_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("gps", cur)
            }
            "audio_device" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val names = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .map { it.productName?.toString() ?: "" }
                    .filter { it.isNotBlank() }
                    .joinToString(",")
                val changed = changedTo(ctx, "auddev_" + ruleId, names) ?: return null
                if (changed.isBlank()) {
                    return null
                }
                if (param.isNotBlank() && !changed.contains(param, ignoreCase = true)) {
                    return null
                }
                JSONObject().put("devices", changed)
            }
            "audio_recording", "mic_used" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val cur = if (am.activeRecordingConfigurations.isNotEmpty()) {
                    "on"
                } else {
                    "off"
                }
                if (!edgeTo(ctx, "audrec_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("active", "true")
            }
            "audio_muted" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val stream = streamConst(param.trim().lowercase())
                val cur = if (am.isStreamMute(stream)) "muted" else "unmuted"
                if (!edgeTo(ctx, "audmute_" + ruleId, cur, "muted")) {
                    return null
                }
                val out = JSONObject()
                out.put("stream", param.trim().ifBlank { "music" })
                out.put("muted", "true")
                out
            }
            "bt_active" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val name = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    }?.productName?.toString() ?: ""
                val changed = changedTo(ctx, "btact_" + ruleId, name) ?: return null
                if (changed.isBlank()) {
                    return null
                }
                if (param.isNotBlank() && !changed.contains(param, ignoreCase = true)) {
                    return null
                }
                JSONObject().put("device", changed)
            }
            "bt_sco" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val cur = if (am.isBluetoothScoOn) "on" else "off"
                if (!edgeTo(ctx, "btsco_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("sco", "true")
            }
            "mic_muted" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val cur = if (am.isMicrophoneMute) "muted" else "unmuted"
                if (!edgeTo(ctx, "micmute_" + ruleId, cur, "muted")) {
                    return null
                }
                JSONObject().put("muted", "true")
            }
            "ringer_mode" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val mode = when (am.ringerMode) {
                    android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                val changed = changedTo(ctx, "ringmode_" + ruleId, mode) ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("mode", changed)
            }
            "speakerphone" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                val cur = if (am.isSpeakerphoneOn) "on" else "off"
                if (!edgeTo(ctx, "spk_" + ruleId, cur, "on")) {
                    return null
                }
                JSONObject().put("speakerphone", "true")
            }
            "bt_enabled" -> {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?: return null
                val cur = if (adapter.isEnabled) "on" else "off"
                val changed = changedTo(ctx, "bten_" + ruleId, cur) ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("enabled", changed)
            }
            "bt_tethering", "eth_tethering", "usb_tethering" -> {
                val ifaces = FlowTriggers.tetheredIfaces(ctx)
                val needle = when (type) {
                    "bt_tethering" -> "bt-pan"
                    "eth_tethering" -> "eth"
                    else -> "rndis"
                }
                val cur = if (ifaces.any { it.contains(needle) }) "on" else "off"
                if (!edgeTo(ctx, "teth_" + ruleId + "_" + type, cur, "on")) {
                    return null
                }
                JSONObject().put("tethering", "true")
            }
            "data_default", "network_type" -> {
                val transport = activeTransport(ctx)
                val changed = changedTo(ctx, "nettype_" + ruleId, transport)
                    ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("transport", changed)
            }
            "mobile_type" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val typeName = networkTypeName(tm.dataNetworkType)
                val changed = changedTo(ctx, "mobtype_" + ruleId, typeName)
                    ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("type", changed)
            }
            "nfc_enabled" -> {
                val adapter = android.nfc.NfcAdapter.getDefaultAdapter(ctx)
                    ?: return null
                val cur = if (adapter.isEnabled) "on" else "off"
                val changed = changedTo(ctx, "nfcen_" + ruleId, cur) ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("enabled", changed)
            }
            "data_saver_on", "data_saver_off" -> {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                val cur = if (cm.restrictBackgroundStatus ==
                    android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                ) {
                    "on"
                } else {
                    "off"
                }
                val want = if (type == "data_saver_on") "on" else "off"
                if (!edgeTo(ctx, "dsaver_" + ruleId, cur, want)) {
                    return null
                }
                JSONObject().put("saver", cur)
            }
            "wifi_enabled" -> {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
                val cur = if (wm.isWifiEnabled) "on" else "off"
                val changed = changedTo(ctx, "wifien_" + ruleId, cur) ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("enabled", changed)
            }
            "wifi_rssi" -> {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
                val info = wm.connectionInfo ?: return null
                val level = android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5)
                val changed = changedTo(ctx, "wifirssi_" + ruleId, level.toString())
                    ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("level", changed).put("rssi", info.rssi)
            }
            "hotspot_clients" -> {
                val count = FlowTriggers.hotspotClientCount(ctx)
                if (count < 0) {
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(now))
                    if (FlowStore.stateStr(ctx, "hs_logged") != today) {
                        FlowStore.setStateStr(ctx, "hs_logged", today)
                        FlowStore.log(ctx, "hotspot_clients needs Shizuku (arp read)")
                    }
                    return null
                }
                val changed = changedTo(ctx, "hsclients_" + ruleId, count.toString())
                    ?: return null
                if (param.isNotBlank() && param.trim() != changed) {
                    return null
                }
                JSONObject().put("clients", changed)
            }
            "app_recent" -> appRecentDue(ctx, ruleId, param)
            "app_notif_enabled" -> appNotifEnabledDue(ctx, ruleId, param)
            "app_notif_priority" -> appNotifPriorityDue(ctx, ruleId, param)
            "app_notif_visibility" -> appNotifVisibilityDue(ctx, ruleId, param)
            "appop_mode" -> appopDue(ctx, ruleId, param)
            "location_at" -> locationDue(ctx, ruleId, param)
            "calendar_query", "calendar_start", "calendar_end" -> calendarDue(
                ctx, ruleId, type, param, now
            )
            "cell_tower" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val cell = try {
                    tm.allCellInfo?.firstOrNull()?.toString() ?: ""
                } catch (_: SecurityException) {
                    return null
                }
                val changed = changedTo(ctx, "celltwr_" + ruleId, cell) ?: return null
                if (param.isNotBlank() && !changed.contains(param, ignoreCase = true)) {
                    return null
                }
                JSONObject().put("cell", changed)
            }
            "operator" -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
                val name = tm.networkOperatorName ?: ""
                val changed = changedTo(ctx, "oper_" + ruleId, name) ?: return null
                if (param.isNotBlank() && !changed.contains(param, ignoreCase = true)) {
                    return null
                }
                JSONObject().put("name", changed)
            }
            else -> {
                return null
            }
        }
    }

    // ---------------- v1.4.0 tick-branch helpers ----------------

    private fun activeTransport(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        } catch (_: Exception) {
            "none"
        }
    }

    private fun streamConst(name: String): Int {
        return when (name) {
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            "ring" -> android.media.AudioManager.STREAM_RING
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "call", "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL
            "system" -> android.media.AudioManager.STREAM_SYSTEM
            else -> android.media.AudioManager.STREAM_MUSIC
        }
    }

    private fun networkTypeName(type: Int): String {
        return when (type) {
            android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "NR"
            android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            android.telephony.TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
            android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            android.telephony.TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            android.telephony.TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            else -> "unknown"
        }
    }

    /** app_recent: param "pkg" or "pkg|minutes" (default 60).
     *  UsageStats last-used within the window counts as recent.
     *  Needs usage access; without it the query is empty and the
     *  rule never fires (documented approximation). */
    private fun appRecentDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val pkg = param.substringBefore("|").trim()
        if (pkg.isBlank()) {
            return null
        }
        val minutes = param.substringAfter("|", "60").toIntOrNull() ?: 60
        val now = System.currentTimeMillis()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as android.app.usage.UsageStatsManager
        val stats = try {
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - minutes * 60_000L, now
            )
        } catch (_: Exception) {
            null
        } ?: return null
        val lastUsed = stats.firstOrNull { it.packageName == pkg }?.lastTimeUsed ?: 0L
        val cur = if (lastUsed > 0L && now - lastUsed <= minutes * 60_000L) {
            "recent"
        } else {
            "old"
        }
        if (!edgeTo(ctx, "apprecent_" + ruleId, cur, "recent")) {
            return null
        }
        return JSONObject().put("pkg", pkg)
    }

    /** app_notif_enabled: own package is exact (areNotificationsEnabled).
     *  Other packages are observable only while a notification is
     *  posted (listener ranking); otherwise unknown -> no fire. */
    private fun appNotifEnabledDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val pkg = param.trim()
        if (pkg.isBlank()) {
            return null
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val enabled: Boolean = if (pkg == ctx.packageName) {
            nm.areNotificationsEnabled()
        } else {
            if (FlowNotifListener.rankingFor(pkg) == null) {
                return null
            }
            true
        }
        val cur = if (enabled) "on" else "off"
        val changed = changedTo(ctx, "appnoten_" + ruleId, cur) ?: return null
        return JSONObject().put("pkg", pkg).put("enabled", changed)
    }

    /** app_notif_priority: ranking importance for a posted
     *  notification; Shizuku dumpsys parse as fallback. */
    private fun appNotifPriorityDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val pkg = param.substringBefore("|").trim()
        if (pkg.isBlank()) {
            return null
        }
        var importance: Int? = FlowNotifListener.rankingFor(pkg)?.importance
        if (importance == null && ShizukuHelper.granted()) {
            importance = dumpsysImportance(pkg)
        }
        if (importance == null) {
            return null
        }
        val name = when (importance) {
            android.app.NotificationManager.IMPORTANCE_NONE -> "none"
            android.app.NotificationManager.IMPORTANCE_MIN -> "min"
            android.app.NotificationManager.IMPORTANCE_LOW -> "low"
            android.app.NotificationManager.IMPORTANCE_DEFAULT -> "default"
            android.app.NotificationManager.IMPORTANCE_HIGH -> "high"
            android.app.NotificationManager.IMPORTANCE_MAX -> "max"
            else -> "unspecified"
        }
        val changed = changedTo(ctx, "appnotpri_" + ruleId, name) ?: return null
        return JSONObject().put("pkg", pkg).put("importance", changed)
    }

    /** Best-effort: find "importance=N" near a "pkg=<pkg>" line in
     *  dumpsys notification output. Returns null when unparseable. */
    private fun dumpsysImportance(pkg: String): Int? {
        return try {
            val out = ShizukuHelper.run("dumpsys notification") ?: return null
            val idx = out.indexOf("pkg=" + pkg)
            if (idx < 0) {
                return null
            }
            val window = out.substring(idx, minOf(out.length, idx + 600))
            val match = Regex("importance=(-?\\d+)").find(window) ?: return null
            match.groupValues[1].toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** app_notif_visibility: lockscreen visibility of the posted
     *  notification's channel (own + other packages, while posted). */
    private fun appNotifVisibilityDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val pkg = param.substringBefore("|").trim()
        if (pkg.isBlank()) {
            return null
        }
        val ranking = FlowNotifListener.rankingFor(pkg) ?: return null
        val vis = try {
            ranking.channel?.lockscreenVisibility
        } catch (_: Exception) {
            null
        } ?: return null
        val name = when (vis) {
            android.app.Notification.VISIBILITY_PUBLIC -> "public"
            android.app.Notification.VISIBILITY_PRIVATE -> "private"
            android.app.Notification.VISIBILITY_SECRET -> "secret"
            else -> "unknown"
        }
        val changed = changedTo(ctx, "appnotvis_" + ruleId, name) ?: return null
        return JSONObject().put("pkg", pkg).put("visibility", changed)
    }

    /** appop_mode: param "pkg|op" where op is a short name
     *  (location, camera, mic, contacts, calendar, sms, phone,
     *  storage, sensors, activity, notification, overlay, usage)
     *  or a full "android:..." OPSTR. */
    private fun appopDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val parts = param.split("|")
        if (parts.size < 2) {
            return null
        }
        val pkg = parts[0].trim()
        val opName = parts[1].trim()
        val op = if (opName.startsWith("android:")) {
            opName
        } else {
            when (opName.lowercase()) {
                "location" -> android.app.AppOpsManager.OPSTR_FINE_LOCATION
                "coarse_location" -> android.app.AppOpsManager.OPSTR_COARSE_LOCATION
                "camera" -> android.app.AppOpsManager.OPSTR_CAMERA
                "mic", "audio" -> android.app.AppOpsManager.OPSTR_RECORD_AUDIO
                "contacts" -> android.app.AppOpsManager.OPSTR_READ_CONTACTS
                "calendar" -> android.app.AppOpsManager.OPSTR_READ_CALENDAR
                "sms" -> android.app.AppOpsManager.OPSTR_SEND_SMS
                "phone" -> android.app.AppOpsManager.OPSTR_CALL_PHONE
                "storage" -> android.app.AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE
                "sensors" -> android.app.AppOpsManager.OPSTR_BODY_SENSORS
                "activity" -> "android:activity_recognition"
                "notification" -> "android:post_notification"
                "overlay" -> android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW
                "usage" -> android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
                else -> return null
            }
        }
        val uid = try {
            ctx.packageManager.getPackageUid(pkg, 0)
        } catch (_: Exception) {
            return null
        }
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE)
            as android.app.AppOpsManager
        val mode = try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                aom.unsafeCheckOpNoThrow(op, uid, pkg)
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(op, uid, pkg)
            }
        } catch (_: Exception) {
            return null
        }
        val name = when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allowed"
            android.app.AppOpsManager.MODE_IGNORED -> "ignored"
            android.app.AppOpsManager.MODE_ERRORED -> "errored"
            android.app.AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        val changed = changedTo(ctx, "appop_" + ruleId, name) ?: return null
        return JSONObject().put("pkg", pkg).put("mode", changed)
    }

    /** location_at: param "lat|lon|radiusMeters". Last-known
     *  location only (no active fix) — accuracy documented. */
    private fun locationDue(ctx: Context, ruleId: String, param: String): JSONObject? {
        val parts = param.split("|")
        if (parts.size < 3) {
            return null
        }
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val radius = parts[2].toFloatOrNull() ?: return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val loc = try {
            val gps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            when {
                gps == null -> net
                net == null -> gps
                gps.time >= net.time -> gps
                else -> net
            }
        } catch (_: SecurityException) {
            return null
        } ?: return null
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, loc.latitude, loc.longitude, results)
        val cur = if (results[0] <= radius) "in" else "out"
        if (!edgeTo(ctx, "locat_" + ruleId, cur, "in")) {
            return null
        }
        val out = JSONObject()
        out.put("lat", loc.latitude)
        out.put("lon", loc.longitude)
        out.put("distance", results[0])
        return out
    }

    /** calendar_query / calendar_start / calendar_end: param is a
     *  title substring (blank = any event). Fires at the event
     *  start (query/start) or end (end), deduped by event id. */
    private fun calendarDue(
        ctx: Context,
        ruleId: String,
        type: String,
        titlePart: String,
        now: Long
    ): JSONObject? {
        val events = try {
            FlowTriggers.calendarEvents(ctx, titlePart)
        } catch (_: Exception) {
            emptyList()
        }
        val key = "cal_" + ruleId + "_" + type
        val seen = FlowStore.stateStr(ctx, key)
            .split(",").filter { it.isNotBlank() }.toMutableList()
        for (event in events) {
            val moment = if (type == "calendar_end") event.end else event.begin
            if (moment < now - 60_000L || moment > now) {
                continue
            }
            if (event.id in seen) {
                continue
            }
            seen.add(event.id)
            while (seen.size > 50) {
                seen.removeAt(0)
            }
            FlowStore.setStateStr(ctx, key, seen.joinToString(","))
            val out = JSONObject()
            out.put("title", event.title)
            out.put("id", event.id)
            return out
        }
        return null
    }

    private fun edgeOnce(ctx: Context, key: String, now: Long, windowMs: Long): Boolean {
        val last = FlowStore.lastFired(ctx, key)
        if (now - last < windowMs) {
            return false
        }
        FlowStore.markFired(ctx, key, now)
        return true
    }

    private fun dayCodeToday(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "sat"
            Calendar.SUNDAY -> "sun"
            Calendar.MONDAY -> "mon"
            Calendar.TUESDAY -> "tue"
            Calendar.WEDNESDAY -> "wed"
            Calendar.THURSDAY -> "thu"
            else -> "fri"
        }
    }

    // ---------------- trigger param matching (mirrors PhoneLink) ----------------

    private fun triggerParamMatches(event: String, param: String, data: JSONObject): Boolean {
        if (param.isBlank()) {
            return true
        }
        return when (event) {
            "sms_received", "notif_received", "screen_text", "notif_cleared", "clipboard",
            "ui_click", "sms_sent", "call_outgoing", "call_ended", "qr_scanned",
            "calendar_start", "calendar_end", "rule_toggled",
            "notify_button", "sms_delivered", "screenshot_taken",
            "photo_taken", "video_taken", "alarm", "toast_posted",
            "ui_focused", "notif_interacted", "mms_received",
            "calendar_query" -> {
                val hay = (data.optString("title") + " " + data.optString("body") +
                    " " + data.optString("app")).lowercase()
                hay.contains(param.lowercase())
            }
            "app_launched", "app_closed", "app_installed", "app_removed",
            "app_updated", "camera_used", "mic_used", "applock_failed" ->
                data.optString("pkg") == param
            "app_recent" -> data.optString("pkg") == param.substringBefore("|")
            "app_notif_enabled", "app_notif_priority", "app_notif_visibility" ->
                data.optString("pkg") == param.substringBefore("|")
            "appop_mode" -> data.optString("pkg") == param.substringBefore("|")
            "broadcast_receive" -> data.optString("action") == param
            "content_changed" -> data.optString("uri") == param
            "log_await" -> {
                val line = data.optString("line")
                try {
                    Regex(param).containsMatchIn(line)
                } catch (_: Exception) {
                    line.contains(param)
                }
            }
            "display_power_mode" -> data.optString("mode") == param
            "audio_device" -> data.optString("devices").contains(param, ignoreCase = true)
            "audio_muted" -> param.isBlank() ||
                data.optString("stream") == param.substringBefore("|")
            "bt_active" -> data.optString("device").contains(param, ignoreCase = true)
            "camera_available" -> param.isBlank() || data.optString("id") == param
            "torch_state" -> param.isBlank() || data.optString("on") == param
            "bt_enabled", "wifi_enabled", "nfc_enabled" ->
                param.isBlank() || data.optString("enabled") == param
            "data_default", "network_type" ->
                param.isBlank() || data.optString("transport") == param
            "mobile_type" -> param.isBlank() || data.optString("type") == param
            "ringer_mode" -> param.isBlank() || data.optString("mode") == param
            "network_connected" ->
                param.isBlank() || data.optString("transport") == param
            "cell_signal" -> param.isBlank() || data.optString("level") == param
            "cell_tower" -> data.optString("cell").contains(param, ignoreCase = true)
            "operator" -> data.optString("name").contains(param, ignoreCase = true)
            "sim_state" -> param.isBlank() || data.optString("state") == param
            "dialog_interacted" -> param.isBlank() || data.optString("button") == param
            "key_pressed" -> param.isBlank() ||
                data.optString("key").equals(param, ignoreCase = true) ||
                data.optString("code") == param
            "media_button", "headset_button" ->
                param.isBlank() || data.optString("key") == param
            "keyboard_visible" -> param.isBlank() || data.optString("visible") == param
            "storage_mounted" -> param.isBlank() || data.optString("mounted") == param
            "settings_changed" -> data.optString("key") == param
            "activity_recognition" -> data.optString("kind") == param
            "media_added", "media_removed" ->
                param.isBlank() || data.optString("kind") == param ||
                    data.optString("title").contains(param, ignoreCase = true)
            "usb_attached" -> data.optString("title").contains(param, ignoreCase = true)
            "bt_connected", "bt_disconnected", "bt_near", "bt_far" ->
                data.optString("title").contains(param, ignoreCase = true)
            "orientation_changed" -> data.optString("kind") == param
            "webhook" -> data.optString("title") == param
            "wifi_connected" -> data.optString("ssid").equals(param, true)
            "var_changed" -> data.optString("title") == param
            "file_changed" -> data.optString("title") == param
            "activity" -> data.optString("kind") == param
            else -> true
        }
    }

    // ---------------- shared readers ----------------

    fun batteryPct(ctx: Context): Int {
        return try {
            val intent = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) {
                0
            } else {
                level * 100 / scale
            }
        } catch (_: Exception) {
            0
        }
    }

    fun isCharging(ctx: Context): Boolean {
        return try {
            val intent = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    fun batteryTempC(ctx: Context): Float {
        return try {
            val intent = ctx.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (t < 0) {
                0f
            } else {
                t / 10f
            }
        } catch (_: Exception) {
            0f
        }
    }

    fun minutesNow(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun parseHm(s: String): Int {
        val parts = s.trim().split(":")
        if (parts.size != 2) {
            return -1
        }
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) {
            return -1
        }
        return h * 60 + m
    }

    fun toastMain(ctx: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    fun evalVar(ctx: Context, param: String): Boolean {
        val ops = listOf(">=", "<=", ">", "<", "=")
        for (op in ops) {
            val idx = param.indexOf(op)
            if (idx > 0) {
                val name = param.substring(0, idx).trim()
                val want = param.substring(idx + op.length).trim()
                val have = FlowStore.getVar(ctx, name)
                return when (op) {
                    "=" -> have == want
                    else -> {
                        val a = have.toDoubleOrNull() ?: return false
                        val b = want.toDoubleOrNull() ?: return false
                        when (op) {
                            ">" -> a > b
                            "<" -> a < b
                            ">=" -> a >= b
                            else -> a <= b
                        }
                    }
                }
            }
        }
        return FlowStore.getVar(ctx, param).isNotEmpty()
    }

    // ---------------- constraints ----------------

    fun constraintOk(ctx: Context, type: String, param: String): Boolean {
        return try {
            when (type) {
                "time_range" -> {
                    val parts = param.split("-")
                    if (parts.size != 2) {
                        true
                    } else {
                        val now = minutesNow()
                        val s = parseHm(parts[0])
                        val e = parseHm(parts[1])
                        if (s < 0 || e < 0) {
                            true
                        } else if (s <= e) {
                            now in s..e
                        } else {
                            now >= s || now <= e
                        }
                    }
                }
                "day_of_week" -> {
                    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                    param.split(",").map { it.trim() }.contains(today.toString())
                }
                "date_range" -> {
                    val parts = param.split("~")
                    if (parts.size != 2) {
                        true
                    } else {
                        val today = java.text.SimpleDateFormat(
                            "yyyy-MM-dd", java.util.Locale.US
                        ).format(java.util.Date())
                        today >= parts[0].trim() && today <= parts[1].trim()
                    }
                }
                "day_of_month" -> {
                    val dom = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                    param.split(",").map { it.trim() }.contains(dom.toString())
                }
                "battery_above" -> batteryPct(ctx) >= (param.toIntOrNull() ?: 0)
                "battery_below" -> batteryPct(ctx) <= (param.toIntOrNull() ?: 100)
                "battery_temp_below" -> batteryTempC(ctx) <= (param.toFloatOrNull() ?: 999f)
                "battery_temp_above" -> batteryTempC(ctx) >= (param.toFloatOrNull() ?: -999f)
                "charging" -> isCharging(ctx) == (param.trim().lowercase() != "off")
                "charging_type" -> {
                    val intent = ctx.registerReceiver(
                        null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                    val kind = when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                        else -> "none"
                    }
                    kind == param.trim().lowercase()
                }
                "wifi_ssid" -> currentSsid(ctx).equals(param.trim(), ignoreCase = true)
                "screen" -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isInteractive == (param.trim().lowercase() != "off")
                }
                "foreground_app" -> foregroundApp(ctx) == param.trim()
                "variable" -> evalVar(ctx, param)
                "music_playing" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.isMusicActive == (param.trim().lowercase() != "off")
                }
                "call_active" -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    (tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE) ==
                        (param.trim().lowercase() != "off")
                }
                "vpn" -> {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val on = caps != null &&
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    on == (param.trim().lowercase() != "off")
                }
                "mobile_data" -> {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val on = caps != null &&
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    on == (param.trim().lowercase() != "off")
                }
                "roaming" -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    tm.isNetworkRoaming == (param.trim().lowercase() != "off")
                }
                "dark_mode" -> {
                    val night = (ctx.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    night == (param.trim().lowercase() != "off")
                }
                "dnd" -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val on = nm.currentInterruptionFilter !=
                        android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    on == (param.trim().lowercase() != "off")
                }
                "airplane" -> {
                    val on = android.provider.Settings.Global.getInt(
                        ctx.contentResolver,
                        android.provider.Settings.Global.AIRPLANE_MODE_ON, 0
                    ) == 1
                    on == (param.trim().lowercase() != "off")
                }
                "keyguard_locked" -> {
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    km.isKeyguardLocked == (param.trim().lowercase() != "off")
                }
                "battery_saver" -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isPowerSaveMode == (param.trim().lowercase() != "off")
                }
                "data_saver" -> {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val on = cm.restrictBackgroundStatus ==
                        android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                    on == (param.trim().lowercase() != "off")
                }
                "uptime_above" -> {
                    val mins = param.toLongOrNull() ?: 0L
                    android.os.SystemClock.elapsedRealtime() >= mins * 60_000L
                }
                "calendar_busy" -> calendarBusyNow(ctx) == (param.trim().lowercase() != "off")
                "headset_mic" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    val on = devices.any {
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    on == (param.trim().lowercase() != "off")
                }
                "process_running" -> {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val procs = am.runningAppProcesses ?: emptyList()
                    procs.any { it.processName == param.trim() }
                }
                "gps_state" -> {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ==
                        (param.trim().lowercase() != "off")
                }
                "hotspot_state" -> {
                    // Best effort: WifiManager AP state is hidden API; reflect
                    // guarded, otherwise report off (never guess on).
                    val wm = ctx.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    val on = try {
                        val m = wm.javaClass.getMethod("isWifiApEnabled")
                        m.invoke(wm) as? Boolean ?: false
                    } catch (_: Exception) {
                        false
                    }
                    on == (param.trim().lowercase() != "off")
                }
                "location_in" -> {
                    val parts = param.split(",").map { it.trim() }
                    if (parts.size != 3) {
                        false
                    } else {
                        val lat = parts[0].toDoubleOrNull() ?: return false
                        val lng = parts[1].toDoubleOrNull() ?: return false
                        val radius = parts[2].toDoubleOrNull() ?: return false
                        val here = lastLocation(ctx) ?: return false
                        val res = FloatArray(1)
                        android.location.Location.distanceBetween(
                            here.first, here.second, lat, lng, res
                        )
                        res[0] <= radius
                    }
                }
                "rule_enabled" -> {
                    // PhoneLink rules via Automation, never FlowStore.
                    val target = Automation.rules(ctx).firstOrNull {
                        it.optString("id") == param.trim() ||
                            it.optString("name") == param.trim()
                    }
                    target?.optBoolean("enabled", true) ?: false
                }
                "signal_above" -> {
                    val want = param.toIntOrNull() ?: return false
                    val level = signalLevel(ctx)
                    level >= want
                }
                "notif_present" -> {
                    // PhoneLink v2.24.0: the Flow listener IS real.
                    // Blank param = any active notification; else
                    // title/body contains, or package equals.
                    FlowNotifListener.hasActive(param.trim())
                }
                "peer_online", "peer_battery_above" -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_dropped_action, type))
                    false
                }
                "steps_today", "proximity_near", "light_below", "light_above",
                "speed_above", "bt_connected" -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_dropped_action, type))
                    false
                }
                else -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_dropped_action, type))
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun currentSsid(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ssid = wm.connectionInfo?.ssid ?: ""
            ssid.removeSurrounding("\"")
        } catch (_: Exception) {
            ""
        }
    }

    private fun foregroundApp(ctx: Context): String {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val list = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now
            ) ?: return ""
            var best: String? = null
            var bestTime = 0L
            for (s in list) {
                if (s.lastTimeUsed > bestTime) {
                    bestTime = s.lastTimeUsed
                    best = s.packageName
                }
            }
            best ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun lastLocation(ctx: Context): Pair<Double, Double>? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = lm.getProviders(true)
            for (p in providers) {
                val loc = try {
                    lm.getLastKnownLocation(p)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
                if (loc != null) {
                    return Pair(loc.latitude, loc.longitude)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun calendarBusyNow(ctx: Context): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI
            val cur = ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.CalendarContract.Instances.EVENT_ID),
                android.provider.CalendarContract.Instances.BEGIN + " <= ? AND " +
                    android.provider.CalendarContract.Instances.END + " >= ?",
                arrayOf(now.toString(), now.toString()),
                null
            )
            val busy = cur != null && cur.count > 0
            try {
                cur?.close()
            } catch (_: Exception) {
            }
            busy
        } catch (_: Exception) {
            false
        }
    }

    private fun signalLevel(ctx: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                tm.signalStrength?.level ?: 0
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    // ---------------- action execution ----------------

    // ---------------- flowchart walker ----------------

    /** Per-run flow state: loop counters + for_each progress, keyed by node id.
     *  v1.3.0 adds flow-local variables (call_flow args, set_local). */
    private class FlowState {
        var flowId = ""
        val loopCounts = mutableMapOf<String, Int>()
        val forEachItems = mutableMapOf<String, Pair<List<String>, Int>>()
        val forEachVars = mutableMapOf<String, String>()
        val locals = mutableMapOf<String, String>()

        fun copy(): FlowState {
            val c = FlowState()
            c.flowId = flowId
            c.loopCounts.putAll(loopCounts)
            c.forEachItems.putAll(forEachItems)
            c.forEachVars.putAll(forEachVars)
            c.locals.putAll(locals)
            return c
        }
    }

    /** Manual / Home run: the walk happens on a background thread. */
    fun runFlow(ctx: Context, flow: JSONObject) {
        Thread { runFlowSync(ctx, flow, 0) }.start()
    }

    /**
     * Synchronous flow walk (v1.2.0: loop_count, for_each, call_flow,
     * flow_stop; v1.3.0: fork, disabled nodes, onerror, call_flow
     * args, live highlight, per-flow log + stats). call_flow nesting
     * is capped at depth 5; each nested flow gets its own state and
     * its own 200-step budget. Flows without the new node types walk
     * exactly as before. A callee's local "result" (if set via
     * set_local) is copied to the global "result" var on return, so
     * the caller can read it; other locals never leak back.
     */
    fun runFlowSync(ctx: Context, flow: JSONObject, depth: Int) {
        runFlowSync(ctx, flow, depth, emptyMap())
    }

    fun runFlowSync(
        ctx: Context,
        flow: JSONObject,
        depth: Int,
        initialLocals: Map<String, String>
    ) {
        runFlowSyncFrom(ctx, flow, null, depth, initialLocals)
    }

    /**
     * v1.4.1: run starting AT a given trigger node (background
     * dispatch) instead of always at the first trigger node.
     * With startNode == null this is exactly the old manual run.
     * Stats, per-flow log, cancel registration and the "result"
     * copy-back are identical in both cases.
     */
    fun runFlowSyncFrom(
        ctx: Context,
        flow: JSONObject,
        startNode: JSONObject?,
        depth: Int,
        initialLocals: Map<String, String>
    ) {
        val flowId = flow.optString("id")
        val flowName = flow.optString("name")
        val cancelFlag = if (depth == 0) registerFlowRun(flowName) else null
        if (depth == 0) {
            FlowStore.flowStatsBump(ctx, flowId)
        }
        val state = FlowState()
        state.flowId = flowId
        state.locals.putAll(initialLocals)
        try {
            val nodes = flow.optJSONArray("nodes") ?: return
            val edges = flow.optJSONArray("edges") ?: org.json.JSONArray()
            if (nodes.length() == 0) {
                FlowStore.log(ctx, "flow empty: " + flowName)
                return
            }
            var current: JSONObject? = null
            if (startNode != null) {
                current = nodeById(nodes, startNode.optString("id")) ?: startNode
            } else {
                for (k in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(k) ?: continue
                    if (n.optString("kind") == "t") {
                        current = n
                        break
                    }
                }
                if (current == null) {
                    current = nodes.optJSONObject(0)
                }
            }
            FlowStore.flowLogAppend(ctx, flowId, "run start: " + flowName)
            walkFrom(ctx, flow, nodes, edges, current, state, depth)
            val result = state.locals["result"]
            if (result != null) {
                FlowStore.setVar(ctx, "result", result)
            }
            FlowStore.flowLogAppend(ctx, flowId, "run done: " + flowName)
        } catch (e: Exception) {
            FlowStore.flowLogAppend(
                ctx, flowId,
                "run error: " + (e.message ?: e.javaClass.simpleName)
            )
            FlowStore.log(ctx, "flow error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            if (cancelFlag != null) {
                unregisterFlowRun(flowName, cancelFlag)
            }
        }
    }

    /**
     * The walk itself, shared by the top-level run and by fork
     * branches (each branch gets its own state copy and its own
     * 200-step budget on its own thread). A fork node ends the
     * current walk after spawning one thread per outgoing edge —
     * there are no join semantics; branches run to completion.
     * A disabled node is skipped (its "any" edge is followed)
     * without running. The previous ThreadLocal state is restored
     * on exit so nested call_flow runs cannot leak locals.
     */
    private fun walkFrom(
        ctx: Context,
        flow: JSONObject,
        nodes: org.json.JSONArray,
        edges: org.json.JSONArray,
        start: JSONObject?,
        state: FlowState,
        depth: Int
    ) {
        val flowId = flow.optString("id")
        val flowName = flow.optString("name")
        val previous = flowStateLocal.get()
        flowStateLocal.set(state)
        try {
            var current = start
            var steps = 0
            while (current != null && steps < 200) {
                if (depth == 0) {
                    val flags = runningFlows[flowName]
                    var cancelled = false
                    if (flags != null) {
                        synchronized(flags) {
                            cancelled = flags.any { it.get() }
                        }
                    }
                    if (cancelled) {
                        FlowStore.log(ctx, "flow stopped by stop_flow: " + flowName)
                        return
                    }
                }
                steps++
                val kind = current.optString("kind")
                val type = current.optString("type")
                val param = current.optString("param")
                nodeListener?.invoke(current.optString("id"))
                FlowStore.log(ctx, "flow " + flowName + ": " + kind + "/" + type)
                FlowStore.flowLogAppend(ctx, flowId, kind + "/" + type)
                if (!current.optBoolean("enabled", true)) {
                    current = followEdges(
                        ctx, flow, nodes, edges, current.optString("id"), "any", state, depth
                    )
                    continue
                }
                var branch = "any"
                when (kind) {
                    "t" -> {
                        // Manual flow run treats the entry trigger as satisfied.
                    }
                    "c" -> {
                        branch = if (constraintOk(ctx, type, param)) "yes" else "no"
                    }
                    "a" -> {
                        when (type) {
                            "loop_count" -> {
                                branch = loopBranch(state, current.optString("id"), param)
                            }
                            "for_each" -> {
                                branch = forEachBranch(ctx, state, current.optString("id"), param)
                            }
                            "fork" -> {
                                val outs = ArrayList<JSONObject>()
                                for (i in 0 until edges.length()) {
                                    val e = edges.optJSONObject(i) ?: continue
                                    if (e.optString("from") == current.optString("id")) {
                                        nodeById(nodes, e.optString("to"))?.let { outs.add(it) }
                                    }
                                }
                                for (target in outs) {
                                    val branchState = state.copy()
                                    Thread {
                                        try {
                                            walkFrom(ctx, flow, nodes, edges, target, branchState, depth)
                                        } catch (e: Exception) {
                                            FlowStore.log(
                                                ctx,
                                                "fork branch error: " +
                                                    (e.message ?: e.javaClass.simpleName)
                                            )
                                        }
                                    }.start()
                                }
                                return
                            }
                            "call_flow" -> {
                                if (depth >= 5) {
                                    FlowStore.log(ctx, ctx.getString(R.string.flow_call_depth))
                                } else {
                                    val segs = param.split("|")
                                    val targetName = segs[0].trim()
                                    val args = mutableMapOf<String, String>()
                                    for (seg in segs.drop(1)) {
                                        val eq = seg.indexOf("=")
                                        if (eq > 0) {
                                            args[seg.substring(0, eq).trim()] =
                                                seg.substring(eq + 1).trim()
                                        }
                                    }
                                    val target = findFlowByName(ctx, targetName)
                                    if (target == null) {
                                        FlowStore.log(
                                            ctx,
                                            ctx.getString(R.string.flow_call_missing, targetName)
                                        )
                                    } else {
                                        runFlowSync(ctx, target, depth + 1, args)
                                    }
                                }
                            }
                            "flow_stop" -> {
                                FlowStore.log(ctx, ctx.getString(R.string.flow_stopped, "flow_stop"))
                                return
                            }
                            else -> {
                                lastActionErrorLocal.set(null)
                                runAction(
                                    ctx, type, substituteVars(ctx, param),
                                    JSONObject(), null
                                )
                                val err = lastActionErrorLocal.get()
                                lastActionErrorLocal.set(null)
                                if (err != null) {
                                    if (current.optString("onerror", "stop") == "continue") {
                                        FlowStore.log(
                                            ctx,
                                            "flow: continued after error at " + type
                                        )
                                    } else {
                                        FlowStore.log(
                                            ctx,
                                            "flow stopped: error at " + type + ": " + err
                                        )
                                        return
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                    }
                }
                current = followEdges(
                    ctx, flow, nodes, edges, current.optString("id"), branch, state, depth
                )
            }
            if (steps >= 200) {
                FlowStore.log(ctx, ctx.getString(R.string.flow_stopped, "step limit"))
            } else {
                FlowStore.log(ctx, ctx.getString(R.string.flow_run_done))
            }
        } finally {
            flowStateLocal.set(previous)
        }
    }

    /**
     * loop_count as a YES/NO branch (canvas draws YES/NO ports for
     * it): YES while the body still has runs left, NO when the count
     * is reached (counter resets, so a later re-entry loops again).
     * Draw: loop YES -> body start, body end -> loop, loop NO -> next.
     */
    private fun loopBranch(state: FlowState, nodeId: String, param: String): String {
        val n = param.trim().toIntOrNull()?.coerceIn(1, 1000) ?: 1
        val count = state.loopCounts.getOrDefault(nodeId, 0)
        return if (count < n) {
            state.loopCounts[nodeId] = count + 1
            "yes"
        } else {
            state.loopCounts.remove(nodeId)
            "no"
        }
    }

    /**
     * for_each as a YES/NO branch: YES binds the next item and enters
     * the body, NO when the list is exhausted. Param "list|item": the
     * list is a variable holding comma-separated values, or a literal
     * comma-separated list when that variable is empty.
     */
    private fun forEachBranch(
        ctx: Context,
        state: FlowState,
        nodeId: String,
        param: String
    ): String {
        var st = state.forEachItems[nodeId]
        if (st == null) {
            val parts = param.split("|", limit = 2)
            val src = parts[0].trim()
            val itemVar = if (parts.size > 1) parts[1].trim().ifEmpty { "item" } else "item"
            val stored = FlowStore.getVar(ctx, src)
            val items = if (stored.isNotEmpty()) {
                stored.split(",")
            } else {
                src.split(",")
            }.map { it.trim() }.filter { it.isNotEmpty() }
            state.forEachVars[nodeId] = itemVar
            st = items to 0
            state.forEachItems[nodeId] = st
        }
        val items = st.first
        val idx = st.second
        if (idx >= items.size) {
            state.forEachItems.remove(nodeId)
            state.forEachVars.remove(nodeId)
            return "no"
        }
        val itemVar = state.forEachVars[nodeId] ?: "item"
        FlowStore.setVar(ctx, itemVar, items[idx])
        state.forEachItems[nodeId] = items to (idx + 1)
        return "yes"
    }

    /** Find a saved flow by its display name (or id as fallback). */
    private fun findFlowByName(ctx: Context, name: String): JSONObject? {
        if (name.isEmpty()) {
            return null
        }
        for (file in FlowStore.flowFiles(ctx)) {
            try {
                val f = JSONObject(file.readText())
                if (f.optString("name") == name || f.optString("id") == name) {
                    return f
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * v1.4.1 fan-out: ALL outgoing edges matching a branch, in
     * order — exact-branch edges win as a group, else "any" edges.
     * With a single edge this returns exactly the old single-edge
     * result, so single-edge flows walk identically to before.
     */
    private fun outgoingNodes(
        nodes: org.json.JSONArray,
        edges: org.json.JSONArray,
        fromId: String,
        branch: String
    ): List<JSONObject> {
        val exact = ArrayList<JSONObject>()
        val any = ArrayList<JSONObject>()
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            if (e.optString("from") != fromId) {
                continue
            }
            val b = e.optString("branch", "any")
            val target = nodeById(nodes, e.optString("to")) ?: continue
            if (b == branch) {
                exact.add(target)
            } else if (b == "any") {
                any.add(target)
            }
        }
        return if (exact.isNotEmpty()) exact else any
    }

    /**
     * v1.4.1 fan-out step: extra outgoing edges run fork-style
     * (own state copy, own 200-step budget, own thread, same as
     * the fork node); the first edge continues inline. Returns
     * null when nothing follows.
     */
    private fun followEdges(
        ctx: Context,
        flow: JSONObject,
        nodes: org.json.JSONArray,
        edges: org.json.JSONArray,
        fromId: String,
        branch: String,
        state: FlowState,
        depth: Int
    ): JSONObject? {
        val outs = outgoingNodes(nodes, edges, fromId, branch)
        for (target in outs.drop(1)) {
            val branchState = state.copy()
            Thread {
                try {
                    walkFrom(ctx, flow, nodes, edges, target, branchState, depth)
                } catch (e: Exception) {
                    FlowStore.log(
                        ctx,
                        "fork branch error: " +
                            (e.message ?: e.javaClass.simpleName)
                    )
                }
            }.start()
        }
        return outs.firstOrNull()
    }

    private fun nodeById(nodes: org.json.JSONArray, id: String): JSONObject? {
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("id") == id) {
                return n
            }
        }
        return null
    }

    // ---------------- action dispatch (local-only subset) ----------------

    private fun runAction(
        ctx: Context,
        type: String,
        param: String,
        data: JSONObject,
        rule: JSONObject?
    ) {
        if (type in DROPPED_ACTIONS) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_dropped_action, type))
            return
        }
        try {
            when (type) {
                "local_notify" -> localNotify(ctx, param)
                "toast_local" -> toastMain(ctx, param)
                "speak_local" -> speak(ctx, param)
                "vibrate" -> vibrate(ctx)
                "torch", "torch_on" -> setTorch(ctx, true)
                "torch_off" -> setTorch(ctx, false)
                "torch_toggle" -> toggleTorch(ctx)
                "flash_pattern" -> flashPattern(ctx, param)
                "set_var" -> {
                    val idx = param.indexOf("=")
                    if (idx > 0) {
                        FlowStore.setVar(ctx, param.substring(0, idx).trim(), param.substring(idx + 1))
                    }
                }
                "inc_var" -> {
                    val parts = param.split("=").map { it.trim() }
                    val name = parts[0]
                    val by = if (parts.size > 1) parts[1].toDoubleOrNull() ?: 1.0 else 1.0
                    val cur = FlowStore.getVar(ctx, name).toDoubleOrNull() ?: 0.0
                    val next = cur + by
                    FlowStore.setVar(ctx, name, if (next % 1.0 == 0.0) next.toLong().toString() else next.toString())
                }
                "delete_var" -> FlowStore.deleteVar(ctx, param.trim())
                "calc_var" -> {
                    val idx = param.indexOf("=")
                    if (idx > 0) {
                        val name = param.substring(0, idx).trim()
                        val expr = param.substring(idx + 1)
                        val value = evalCalc(ctx, expr)
                        FlowStore.setVar(ctx, name, value)
                    }
                }
                "clipboard_set" -> {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PhoneLink", param))
                }
                "gesture_tap" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else {
                        val parts = param.split(",").map { it.trim() }
                        val x = if (parts.size == 2) parts[0].toFloatOrNull() else null
                        val y = if (parts.size == 2) parts[1].toFloatOrNull() else null
                        if (x == null || y == null || !svc.tap(x, y)) {
                            FlowStore.log(ctx, "gesture_tap failed: " + param)
                        }
                    }
                }
                "gesture_swipe" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else {
                        val parts = param.split(",").map { it.trim() }
                        val v = parts.map { it.toFloatOrNull() }
                        val ms = if (parts.size > 4) parts[4].toLongOrNull() ?: 300L else 300L
                        if (v.size < 4 || v[0] == null || v[1] == null || v[2] == null || v[3] == null ||
                            !svc.swipe(v[0]!!, v[1]!!, v[2]!!, v[3]!!, ms)
                        ) {
                            FlowStore.log(ctx, "gesture_swipe failed: " + param)
                        }
                    }
                }
                "tap_text" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else if (!svc.tapText(param.trim())) {
                        FlowStore.log(ctx, "tap_text not found: " + param.trim())
                    }
                }
                "type_text" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else if (!svc.typeText(param)) {
                        FlowStore.log(ctx, "type_text failed: no focused input")
                    }
                }
                "read_screen" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else {
                        val varName = param.trim().ifEmpty { "screen_text" }
                        val text = svc.readScreen()
                        FlowStore.setVar(ctx, varName, text)
                        FlowStore.log(ctx, "read_screen: " + text.length + " chars into " + varName)
                    }
                }
                "global_key" -> {
                    val svc = FlowA11yService.instance
                    if (svc == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_a11y_needed))
                    } else if (!svc.globalAction(param.trim())) {
                        FlowStore.log(ctx, "global_key unknown or failed: " + param.trim())
                    }
                }
                "take_photo" -> {
                    if (param.contains("|")) {
                        val parts = param.split("|")
                        val facing = parts.getOrNull(0)?.trim() ?: ""
                        val flash = parts.getOrNull(1)?.trim() ?: ""
                        if (!FlowCamera.captureOptions(ctx, facing, flash)) {
                            FlowMedia.capture(ctx, false)
                        }
                    } else {
                        FlowMedia.capture(ctx, false)
                    }
                }
                "record_video" -> FlowMedia.capture(ctx, true)
                "record_audio" -> FlowMedia.audio(ctx, param)
                "call_flow" -> {
                    val segs = param.split("|")
                    val targetName = segs[0].trim()
                    val args = mutableMapOf<String, String>()
                    for (seg in segs.drop(1)) {
                        val eq = seg.indexOf("=")
                        if (eq > 0) {
                            args[seg.substring(0, eq).trim()] = seg.substring(eq + 1).trim()
                        }
                    }
                    val target = findFlowByName(ctx, targetName)
                    if (target == null) {
                        FlowStore.log(ctx, ctx.getString(R.string.flow_call_missing, targetName))
                    } else {
                        runFlowSync(ctx, target, 1, args)
                    }
                }
                "loop_count", "for_each" -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_flow_only))
                }
                "flow_stop" -> {
                    throw StopSignal()
                }
                "open_app" -> {
                    val segs = param.split("|")
                    val pkg = segs[0].trim()
                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        for (seg in segs.drop(1)) {
                            val eq = seg.indexOf("=")
                            if (eq > 0) {
                                intent.putExtra(seg.substring(0, eq).trim(), seg.substring(eq + 1))
                            }
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    } else {
                        FlowStore.log(ctx, "open_app: no launch intent for " + pkg)
                    }
                }
                "open_url" -> {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(param.trim()))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                "share_text" -> {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_TEXT, param)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(Intent.createChooser(intent, null))
                }
                "http" -> {
                    val body = httpGet(param.trim())
                    FlowStore.log(ctx, "http: " + body.take(200))
                }
                "http_to_var" -> {
                    FlowStore.setVar(ctx, "http_body", httpGet(param.trim()))
                }
                "json_get" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        FlowStore.setVar(ctx, "json_value", jsonPath(parts[0], parts[1]) ?: "")
                    }
                }
                "regex_op" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        try {
                            FlowStore.setVar(ctx, "regex_result", Regex(parts[1]).replace(parts[0], parts[2]))
                        } catch (_: Exception) {
                        }
                    }
                }
                "date_calc" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        FlowStore.setVar(ctx, "date_result", dateAdd(parts[0].trim(), parts[1].trim()))
                    }
                }
                "write_file" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        writeFlowFile(ctx, parts[0].trim(), parts[1])
                    }
                }
                "read_file" -> {
                    val parts = param.split("|")
                    val name = parts[0].trim()
                    val varName = if (parts.size > 1) parts[1].trim() else "file_text"
                    FlowStore.setVar(ctx, varName, readFlowFile(ctx, name))
                }
                "file_copy" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        copyFlowFile(ctx, parts[0].trim(), parts[1].trim(), false)
                    }
                }
                "file_move" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        copyFlowFile(ctx, parts[0].trim(), parts[1].trim(), true)
                    }
                }
                "delete_file" -> {
                    try {
                        java.io.File(flowFilesDir(ctx), param.trim()).delete()
                    } catch (_: Exception) {
                    }
                }
                "file_zip" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        zipFlowFile(ctx, parts[0].trim(), parts[1].trim())
                    }
                }
                "send_sms" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        sendSms(ctx, parts[0].trim(), parts[1])
                    }
                }
                "call_number", "ussd_run" -> {
                    val num = param.substringBefore("|").trim()
                    if (num.isEmpty()) {
                        return
                    }
                    val intent = if (PermChecks.has(ctx, android.Manifest.permission.CALL_PHONE)) {
                        Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + num))
                    } else {
                        Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + num))
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                "send_email" -> {
                    val parts = param.split("|")
                    if (parts.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_SENDTO)
                        intent.data = android.net.Uri.parse("mailto:" + parts[0].trim())
                        if (parts.size > 1) {
                            intent.putExtra(Intent.EXTRA_SUBJECT, parts[1])
                        }
                        if (parts.size > 2) {
                            intent.putExtra(Intent.EXTRA_TEXT, parts[2])
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                }
                "whatsapp_msg" -> shareToPackage(ctx, "com.whatsapp", param)
                "telegram_msg" -> shareToPackage(ctx, "org.telegram.messenger", param)
                "rich_notify" -> richNotify(ctx, param)
                "set_wallpaper" -> {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(
                            java.io.File(flowFilesDir(ctx), param.trim()).absolutePath
                        )
                        if (bmp != null) {
                            val wm = android.app.WallpaperManager.getInstance(ctx)
                            wm.setBitmap(bmp)
                        }
                    } catch (_: Exception) {
                    }
                }
                "mode_set", "ringer" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    when (param.trim().lowercase()) {
                        "silent" -> am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                        "vibrate" -> am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                        else -> am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                }
                "volume_set" -> {
                    val parts = param.split("=").map { it.trim() }
                    if (parts.size == 2) {
                        val stream = when (parts[0].lowercase()) {
                            "ring" -> android.media.AudioManager.STREAM_RING
                            "alarm" -> android.media.AudioManager.STREAM_ALARM
                            "voice" -> android.media.AudioManager.STREAM_VOICE_CALL
                            "system" -> android.media.AudioManager.STREAM_SYSTEM
                            else -> android.media.AudioManager.STREAM_MUSIC
                        }
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        am.setStreamVolume(stream, parts[1].toIntOrNull() ?: 0, 0)
                    }
                }
                "brightness_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        android.provider.Settings.System.putInt(
                            ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            param.toIntOrNull()?.coerceIn(0, 255) ?: 128
                        )
                    } else {
                        FlowStore.log(ctx, "brightness_set needs Modify system settings")
                    }
                }
                "brightness_auto" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        android.provider.Settings.System.putInt(
                            ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            if (param.trim().lowercase() == "off") 0 else 1
                        )
                    }
                }
                "screen_timeout_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        android.provider.Settings.System.putLong(
                            ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            param.toLongOrNull() ?: 60000L
                        )
                    }
                }
                "auto_rotate_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        android.provider.Settings.System.putInt(
                            ctx.contentResolver,
                            android.provider.Settings.System.ACCELEROMETER_ROTATION,
                            if (param.trim().lowercase() == "off") 0 else 1
                        )
                    }
                }
                "dnd_set" -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(
                            if (param.trim().lowercase() == "off") {
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            } else {
                                android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                            }
                        )
                    } else {
                        FlowStore.log(ctx, "dnd_set needs Do Not Disturb access")
                    }
                }
                "bt_toggle" -> {
                    try {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        if (param.trim().lowercase() == "off") {
                            @Suppress("DEPRECATION")
                            adapter?.disable()
                        } else {
                            @Suppress("DEPRECATION")
                            adapter?.enable()
                        }
                    } catch (_: Exception) {
                    }
                }
                "play_sound" -> {
                    try {
                        val uri = android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_NOTIFICATION
                        )
                        android.media.RingtoneManager.getRingtone(ctx, uri)?.play()
                    } catch (_: Exception) {
                    }
                }
                "set_alarm" -> {
                    val hm = parseHm(param.trim())
                    if (hm >= 0) {
                        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                        intent.putExtra(android.provider.AlarmClock.EXTRA_HOUR, hm / 60)
                        intent.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, hm % 60)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        // v1.4.0: the alarm trigger covers Lab-set alarms.
                        // Schedule an exact Lab alarm for the same time so
                        // FlowSystemReceiver fires "alarm" when it goes off.
                        try {
                            val cal = Calendar.getInstance()
                            cal.set(Calendar.HOUR_OF_DAY, hm / 60)
                            cal.set(Calendar.MINUTE, hm % 60)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            if (cal.timeInMillis <= System.currentTimeMillis()) {
                                cal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            val alarmIntent = Intent(ctx, FlowSystemReceiver::class.java)
                            alarmIntent.action = "flow_alarm"
                            alarmIntent.putExtra("time", param.trim())
                            val flags = if (android.os.Build.VERSION.SDK_INT >= 31) {
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                    android.app.PendingIntent.FLAG_IMMUTABLE
                            } else {
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            }
                            val pending = android.app.PendingIntent.getBroadcast(
                                ctx, 0, alarmIntent, flags
                            )
                            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE)
                                as android.app.AlarmManager
                            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                                !alarmManager.canScheduleExactAlarms()
                            ) {
                                FlowStore.log(ctx, "alarm trigger needs exact-alarm permission")
                            } else {
                                alarmManager.setExactAndAllowWhileIdle(
                                    android.app.AlarmManager.RTC_WAKEUP,
                                    cal.timeInMillis, pending
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                "set_timer" -> {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    intent.putExtra(
                        android.provider.AlarmClock.EXTRA_LENGTH,
                        param.toIntOrNull() ?: 0
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                "calendar_event" -> {
                    val parts = param.split("|")
                    if (parts.isNotEmpty()) {
                        val intent = Intent(Intent.ACTION_INSERT)
                        intent.data = android.provider.CalendarContract.Events.CONTENT_URI
                        intent.putExtra(android.provider.CalendarContract.Events.TITLE, parts[0])
                        if (parts.size > 2) {
                            intent.putExtra(
                                android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                parts[1].toLongOrNull() ?: 0L
                            )
                            intent.putExtra(
                                android.provider.CalendarContract.EXTRA_EVENT_END_TIME,
                                parts[2].toLongOrNull() ?: 0L
                            )
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }
                }
                "lock_screen" -> {
                    if (AdminReceiver.isActive(ctx)) {
                        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        dpm.lockNow()
                    } else {
                        FlowStore.log(ctx, "lock_screen needs device admin")
                    }
                }
                "wake_screen" -> {
                    try {
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        @Suppress("DEPRECATION")
                        val wl = pm.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "flow:wake"
                        )
                        wl.acquire(5000L)
                    } catch (_: Exception) {
                    }
                }
                "keep_awake" -> {
                    try {
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        val wl = pm.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK, "flow:keep"
                        )
                        wl.acquire(10 * 60 * 1000L)
                    } catch (_: Exception) {
                    }
                }
                "clear_notif" -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancelAll()
                }
                "kill_app" -> {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses(param.trim())
                }
                "media_control" -> {
                    val code = when (param.trim().lowercase()) {
                        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        "prev", "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    }
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val down = android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, code
                    )
                    val up = android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP, code
                    )
                    am.dispatchMediaKeyEvent(down)
                    am.dispatchMediaKeyEvent(up)
                }
                "stopwatch_start" -> {
                    FlowStore.markFired(ctx, "sw_start", System.currentTimeMillis())
                }
                "stopwatch_stop" -> {
                    val start = FlowStore.lastFired(ctx, "sw_start")
                    if (start > 0) {
                        val secs = (System.currentTimeMillis() - start) / 1000L
                        FlowStore.log(ctx, "stopwatch: " + secs + "s")
                        FlowStore.markFired(ctx, "sw_start", 0L)
                    }
                }
                "rule_enable" -> setRuleEnabled(ctx, param, true)
                "rule_disable" -> setRuleEnabled(ctx, param, false)
                "rule_run" -> {
                    // PhoneLink rules via Automation, never FlowStore.
                    val target = Automation.rules(ctx).firstOrNull {
                        it.optString("id") == param.trim() ||
                            it.optString("name") == param.trim()
                    }
                    if (target != null) {
                        Automation.runActions(ctx, target, JSONObject(), 0)
                    }
                }
                // ---------------- v1.3.0: variables ----------------
                "set_local" -> {
                    val idx = param.indexOf("=")
                    if (idx > 0) {
                        val name = param.substring(0, idx).trim()
                        val value = param.substring(idx + 1)
                        val state = flowStateLocal.get()
                        if (state != null) {
                            state.locals[name] = value
                        } else {
                            FlowStore.setVar(ctx, name, value)
                        }
                    }
                }
                "list_append" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        val arr = try {
                            org.json.JSONArray(vGet(ctx, parts[0].trim()))
                        } catch (_: Exception) {
                            org.json.JSONArray()
                        }
                        arr.put(parts[1])
                        vSet(ctx, parts[0].trim(), arr.toString())
                    }
                }
                "list_get" -> {
                    val parts = param.split("|")
                    if (parts.size >= 2) {
                        val dest = if (parts.size > 2) parts[2].trim() else "list_value"
                        val item = try {
                            val arr = org.json.JSONArray(vGet(ctx, parts[0].trim()))
                            val idx = parts[1].trim().toIntOrNull() ?: -1
                            if (idx in 0 until arr.length()) arr.get(idx).toString() else ""
                        } catch (_: Exception) {
                            ""
                        }
                        vSet(ctx, dest, item)
                    }
                }
                "dict_get" -> {
                    val parts = param.split("|")
                    if (parts.size >= 2) {
                        val dest = if (parts.size > 2) parts[2].trim() else "dict_value"
                        val value = try {
                            val obj = org.json.JSONObject(vGet(ctx, parts[0].trim()))
                            if (obj.has(parts[1].trim())) obj.get(parts[1].trim()).toString() else ""
                        } catch (_: Exception) {
                            ""
                        }
                        vSet(ctx, dest, value)
                    }
                }
                "text_replace" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        vSet(ctx, parts[0].trim(), vGet(ctx, parts[0].trim()).replace(parts[1], parts[2]))
                    }
                }
                "text_split" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        val arr = org.json.JSONArray()
                        for (piece in vGet(ctx, parts[0].trim()).split(parts[1])) {
                            arr.put(piece)
                        }
                        vSet(ctx, parts[2].trim(), arr.toString())
                    }
                }
                "text_join" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        val joined = try {
                            val arr = org.json.JSONArray(vGet(ctx, parts[0].trim()))
                            val pieces = ArrayList<String>()
                            for (i in 0 until arr.length()) {
                                pieces.add(arr.get(i).toString())
                            }
                            pieces.joinToString(parts[1])
                        } catch (_: Exception) {
                            ""
                        }
                        vSet(ctx, parts[2].trim(), joined)
                    }
                }
                "random_int" -> {
                    val parts = param.split("|")
                    if (parts.size >= 2) {
                        val dest = if (parts.size > 2) parts[2].trim() else "random"
                        val lo = parts[0].trim().toIntOrNull() ?: 0
                        val hi = parts[1].trim().toIntOrNull() ?: 100
                        val value = if (hi > lo) {
                            lo + (Math.random() * (hi - lo + 1)).toInt()
                        } else {
                            lo
                        }
                        vSet(ctx, dest, value.toString())
                    }
                }
                // ---------------- v1.3.0: dialogs (FlowDialogs) ----------------
                "dialog_input" -> {
                    val parts = param.split("|")
                    val title = parts.getOrNull(0) ?: ""
                    val def = parts.getOrNull(1) ?: ""
                    val dest = parts.getOrNull(2)?.trim()?.ifEmpty { "dialog_input" } ?: "dialog_input"
                    val result = FlowDialogs.input(ctx, title, def)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                "dialog_message" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        FlowDialogs.message(ctx, parts[0], parts[1])
                    }
                }
                "dialog_progress" -> {
                    val parts = param.split("|")
                    val title = parts.getOrNull(0) ?: ""
                    val secs = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 5
                    FlowDialogs.progress(ctx, title, secs.coerceIn(1, 300))
                }
                "date_picker" -> {
                    val parts = param.split("|")
                    val title = parts.getOrNull(0) ?: ""
                    val dest = parts.getOrNull(1)?.trim()?.ifEmpty { "picked_date" } ?: "picked_date"
                    val result = FlowDialogs.date(ctx, title)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                "time_picker" -> {
                    val parts = param.split("|")
                    val title = parts.getOrNull(0) ?: ""
                    val dest = parts.getOrNull(1)?.trim()?.ifEmpty { "picked_time" } ?: "picked_time"
                    val result = FlowDialogs.time(ctx, title)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                "listen" -> {
                    val parts = param.split("|", limit = 2)
                    val dest = parts.getOrNull(0)?.trim()?.ifEmpty { "speech_text" } ?: "speech_text"
                    val prompt = parts.getOrNull(1) ?: "Speak now"
                    val result = FlowDialogs.listen(ctx, prompt)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                "file_pick" -> {
                    val dest = param.trim().ifEmpty { "picked_file" }
                    val result = FlowDialogs.pickFile(ctx)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                "dir_pick" -> {
                    val dest = param.trim().ifEmpty { "picked_dir" }
                    val result = FlowDialogs.pickDir(ctx)
                    if (result != null) {
                        vSet(ctx, dest, result)
                    }
                }
                // ---------------- v1.3.0: file / clipboard ----------------
                "file_list" -> {
                    val parts = param.split("|", limit = 2)
                    val dest = if (parts.size > 1) parts[1].trim() else "file_list"
                    val dir = flowFilesDir(ctx)
                    val sub = if (parts[0].trim().isEmpty()) {
                        dir
                    } else {
                        java.io.File(dir, parts[0].trim())
                    }
                    val names = try {
                        sub.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    vSet(ctx, dest, names.joinToString("\n"))
                }
                "file_exists" -> {
                    val parts = param.split("|", limit = 2)
                    val dest = if (parts.size > 1) parts[1].trim() else "file_exists"
                    val f = resolveFlowFile(ctx, parts[0].trim())
                    vSet(ctx, dest, if (f.exists()) "1" else "0")
                }
                "unzip" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        unzipFlowFile(ctx, parts[0].trim(), parts[1].trim())
                    }
                }
                "clipboard_get" -> {
                    val dest = param.trim().ifEmpty { "clipboard_text" }
                    val text = try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = cm.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0)?.text?.toString() ?: ""
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    if (text.isEmpty()) {
                        FlowStore.log(ctx, "clipboard_get: no text (Android 10+ may block background reads)")
                    } else {
                        vSet(ctx, dest, text)
                    }
                }
                "stop" -> throw StopSignal()
                "send_intent" -> sendGenericIntent(ctx, param)
                // ---------------- v1.3.0: Shizuku shell port ----------------
                "shell_cmd" -> {
                    val out = shRun(ctx, param)
                    if (out != null) {
                        FlowStore.setVar(ctx, "shell_out", out)
                        FlowStore.log(ctx, "shell_cmd: " + out.take(500))
                    }
                }
                "shell_to_var" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        val out = shRun(ctx, parts[0])
                        if (out != null) {
                            FlowStore.setVar(ctx, parts[1].trim(), out)
                        }
                    }
                }
                "shell_tap" -> {
                    val parts = param.split(",").map { it.trim() }
                    if (parts.size == 2) {
                        shRun(ctx, "input tap " + parts[0] + " " + parts[1])
                    }
                }
                "shell_swipe" -> {
                    val parts = param.split(",").map { it.trim() }
                    if (parts.size >= 4) {
                        val ms = if (parts.size > 4) parts[4] else "300"
                        shRun(
                            ctx,
                            "input swipe " + parts[0] + " " + parts[1] + " " +
                                parts[2] + " " + parts[3] + " " + ms
                        )
                    }
                }
                "shell_input" -> {
                    shRun(ctx, "input text " + shQuote(param))
                }
                "shell_key" -> {
                    val code = when (param.trim().lowercase()) {
                        "back" -> "KEYCODE_BACK"
                        "home" -> "KEYCODE_HOME"
                        "menu" -> "KEYCODE_MENU"
                        "power" -> "KEYCODE_POWER"
                        "volume_up" -> "KEYCODE_VOLUME_UP"
                        "volume_down" -> "KEYCODE_VOLUME_DOWN"
                        "enter" -> "KEYCODE_ENTER"
                        "delete", "del" -> "KEYCODE_DEL"
                        else -> {
                            val raw = param.trim()
                            if (raw.startsWith("KEYCODE_")) raw else "KEYCODE_" + raw.uppercase()
                        }
                    }
                    shRun(ctx, "input keyevent " + code)
                }
                "shell_screenshot", "screenshot" -> {
                    screenshotViaShizuku(ctx)
                }
                "mobile_data_toggle" -> {
                    val cur = shRun(ctx, "settings get global mobile_data")?.trim()
                    if (cur != null) {
                        if (cur == "1") {
                            shRun(ctx, "svc data disable")
                        } else {
                            shRun(ctx, "svc data enable")
                        }
                        FlowStore.log(ctx, "mobile_data toggled (was " + cur + ")")
                    }
                }
                "airplane_toggle" -> {
                    val cur = shRun(ctx, "settings get global airplane_mode_on")?.trim()
                    if (cur != null) {
                        val on = cur != "1"
                        shRun(ctx, "settings put global airplane_mode_on " + if (on) "1" else "0")
                        shRun(
                            ctx,
                            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " +
                                if (on) "true" else "false"
                        )
                        FlowStore.log(ctx, "airplane " + if (on) "on" else "off")
                    }
                }
                "wifi_toggle" -> {
                    val cur = shRun(ctx, "settings get global wifi_on")?.trim()
                    if (cur != null) {
                        if (cur == "1") {
                            shRun(ctx, "svc wifi disable")
                        } else {
                            shRun(ctx, "svc wifi enable")
                        }
                        FlowStore.log(ctx, "wifi toggled (was " + cur + ")")
                    }
                }
                "clear_app_data" -> {
                    shRun(ctx, "pm clear " + param.trim())
                }
                "force_stop" -> {
                    shRun(ctx, "am force-stop " + param.trim())
                }
                "install_silent" -> {
                    val f = resolveFlowFile(ctx, param.trim())
                    shRun(ctx, "pm install -r " + shQuote(f.absolutePath))
                }
                "uninstall_app" -> {
                    shRun(ctx, "pm uninstall " + param.trim())
                }
                "app_disable" -> {
                    shRun(ctx, "pm disable " + param.trim())
                }
                "app_enable" -> {
                    shRun(ctx, "pm enable " + param.trim())
                }
                "appops_set" -> {
                    val parts = param.split("|")
                    if (parts.size == 3) {
                        shRun(
                            ctx,
                            "appops set " + parts[0].trim() + " " + parts[1].trim() +
                                " " + parts[2].trim()
                        )
                    }
                }
                "wm_density" -> {
                    val v = param.trim()
                    shRun(ctx, if (v == "reset") "wm density reset" else "wm density " + v)
                }
                "wm_size" -> {
                    val v = param.trim()
                    shRun(ctx, if (v == "reset") "wm size reset" else "wm size " + v)
                }
                "anim_scale" -> {
                    val s = param.trim()
                    shRun(ctx, "settings put global animator_duration_scale " + s)
                    shRun(ctx, "settings put global window_animation_scale " + s)
                    shRun(ctx, "settings put global transition_animation_scale " + s)
                }
                "set_time" -> {
                    shRun(ctx, "date -s " + shQuote(param.trim()))
                }
                "set_locale" -> {
                    shRun(ctx, "setprop persist.sys.locale " + param.trim())
                    FlowStore.log(ctx, "locale set — reboot to apply fully")
                }
                "private_dns" -> {
                    val parts = param.split("|")
                    if (parts.isNotEmpty()) {
                        if (parts[0].trim() == "off") {
                            shRun(ctx, "settings put global private_dns_mode off")
                        } else {
                            shRun(ctx, "settings put global private_dns_mode hostname")
                            shRun(ctx, "settings put global private_dns_specifier " + parts[0].trim())
                        }
                    }
                }
                "stay_on" -> {
                    val on = param.trim().lowercase() != "off"
                    shRun(
                        ctx,
                        "settings put global stay_on_while_plugged_in " + if (on) "7" else "0"
                    )
                }
                "aod_toggle" -> {
                    val on = param.trim().lowercase() != "off"
                    shRun(ctx, "settings put secure doze_always_on " + if (on) "1" else "0")
                }
                "sqlite_query" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size >= 2) {
                        val dest = if (parts.size > 2) parts[2].trim() else "sqlite_result"
                        val db = resolveFlowFile(ctx, parts[0].trim()).absolutePath
                        val out = shRun(ctx, "sqlite3 " + shQuote(db) + " " + shQuote(parts[1]))
                        if (out != null) {
                            FlowStore.setVar(ctx, dest, out)
                            FlowStore.log(ctx, "sqlite: " + out.take(200))
                        }
                    }
                }
                "font_scale" -> {
                    shRun(ctx, "settings put system font_scale " + param.trim())
                }
                "night_light" -> {
                    val on = param.trim().lowercase() != "off"
                    shRun(ctx, "settings put secure night_display_activated " + if (on) "1" else "0")
                }
                // ---------------- v1.3.0: connectivity set-states ----------------
                "bt_connect" -> {
                    btConnect(ctx, param.trim())
                }
                "wifi_connect" -> {
                    val parts = param.split("|", limit = 2)
                    wifiConnect(ctx, parts[0].trim(), if (parts.size > 1) parts[1] else null)
                }
                "hotspot_set", "hotspot_toggle" -> {
                    val on = when (param.trim().lowercase()) {
                        "off" -> false
                        "on" -> true
                        else -> !hotspotActive
                    }
                    hotspotSet(ctx, on)
                }
                "battery_saver_set", "battery_saver" -> {
                    val on = param.trim().lowercase() != "off"
                    shRun(ctx, "settings put global low_power " + if (on) "1" else "0")
                }
                "data_saver_set" -> {
                    val on = param.trim().lowercase() != "off"
                    val out = shRun(
                        ctx,
                        "cmd netpolicy set restrict-background " + if (on) "true" else "false"
                    )
                    FlowStore.log(
                        ctx,
                        "data_saver " + if (on) "on" else "off" +
                            " (best-effort: netpolicy output: " + (out ?: "?").take(120) + ")"
                    )
                }
                "dark_set", "dark_theme" -> {
                    val on = when (param.trim().lowercase()) {
                        "off", "light", "0" -> false
                        else -> true
                    }
                    shRun(ctx, "cmd uimode night " + if (on) "yes" else "no")
                }
                // ---------------- v1.3.0: location / notif / fiber ----------------
                "get_location" -> {
                    getLocationVars(ctx)
                }
                "notif_reply" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        val ok = FlowNotifListener.reply(parts[0].trim(), parts[1])
                        FlowStore.log(ctx, "notif_reply " + if (ok) "sent" else "no matching notification")
                    }
                }
                "stop_flow" -> {
                    requestFlowStop(param.trim())
                    FlowStore.log(ctx, "stop_flow requested: " + param.trim())
                }
                "fork" -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_flow_only))
                }
                // ---------------- v1.3.0: image / crypto / ftp ----------------
                "image_resize" -> {
                    val parts = param.split("|")
                    if (parts.size == 4) {
                        imageResize(
                            ctx, parts[0].trim(), parts[1].trim(),
                            parts[2].trim().toIntOrNull() ?: 0,
                            parts[3].trim().toIntOrNull() ?: 0
                        )
                    }
                }
                "file_encrypt" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        cryptoFile(ctx, parts[0].trim(), parts[1].trim(), parts[2], true)
                    }
                }
                "file_decrypt" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        cryptoFile(ctx, parts[0].trim(), parts[1].trim(), parts[2], false)
                    }
                }
                "ftp_upload" -> {
                    val parts = param.split("|")
                    if (parts.size == 5) {
                        ftpTransfer(ctx, true, parts[0].trim(), parts[1], parts[2], parts[3].trim(), parts[4].trim())
                    }
                }
                "ftp_download" -> {
                    val parts = param.split("|")
                    if (parts.size == 5) {
                        ftpTransfer(ctx, false, parts[0].trim(), parts[1], parts[2], parts[3].trim(), parts[4].trim())
                    }
                }
                "focuslock_control" -> runFocusLock(ctx, param)
                // ---------------- v1.4.3: QR scan, WoL, ping, CallShield bridge ----------------
                "qr_scan" -> runQrScan(ctx, param)
                "wol" -> runWol(ctx, param)
                "ping" -> runPing(ctx, param)
                "answer_call" -> sendCallCommand(ctx, "answer")
                "end_call" -> sendCallCommand(ctx, "end")
                // ---------------- v1.5.0: formerly dropped actions ----------------
                // PhoneLink v2.24.0: peer trio via PhoneLink's
                // Relay (same topic derivation as the rule engine).
                // Guarded on an empty pair code, exactly as the
                // rule engine does — never posted to an empty topic.
                "notify_peer" -> {
                    val parts = param.split("|", limit = 2)
                    if (Config.pairCode(ctx).isEmpty()) {
                        FlowStore.log(ctx, "peer not configured (pair code empty)")
                    } else {
                        Relay.send(
                            ctx, "NOTIF",
                            parts.getOrNull(0) ?: "",
                            parts.getOrNull(1) ?: ""
                        )
                    }
                }
                "speak_peer" -> {
                    if (Config.pairCode(ctx).isEmpty()) {
                        FlowStore.log(ctx, "peer not configured (pair code empty)")
                    } else {
                        Relay.sendCommand(ctx, "SPEAK", param)
                    }
                }
                "command_peer" -> {
                    val parts = param.split("|", limit = 2)
                    if (Config.pairCode(ctx).isEmpty()) {
                        FlowStore.log(ctx, "peer not configured (pair code empty)")
                    } else {
                        Relay.sendCommand(
                            ctx,
                            parts.getOrNull(0) ?: "",
                            parts.getOrNull(1) ?: ""
                        )
                    }
                }
                // lost_on / lost_off / autotap_control: NOT in
                // FlowCatalog in this merge (lost-tracking not
                // ported; AutoTap engine stays rule-side). If an
                // old Lab flow still carries one, it lands in the
                // else branch below as a dropped action — never
                // faked, never silently skipped.
                "redial_control" -> {
                    // PhoneLink Redial (v2.23.0), not a Lab port.
                    // param: number|gap|max (Lab shape); sim =
                    // default, no ring limit, stop on answer, no
                    // speaker — the Redial card keeps full control.
                    if (param.trim() == "stop") {
                        Redial.stop(ctx, "stopped")
                    } else {
                        val parts = param.split("|")
                        val number = parts.getOrNull(0)?.trim() ?: ""
                        val gap = parts.getOrNull(1)?.toIntOrNull() ?: 30
                        val max = parts.getOrNull(2)?.toIntOrNull() ?: 20
                        val rc = Redial.start(
                            ctx, number, "default", gap, max, 0, "stop", false
                        )
                        if (rc != 0) {
                            FlowStore.log(ctx, "redial_control failed: code " + rc)
                        }
                    }
                }
                "reboot_device" -> FlowNewActions.runReboot(ctx, param)
                "perm_grant" -> FlowNewActions.runPerm(ctx, param, true)
                "perm_revoke" -> FlowNewActions.runPerm(ctx, param, false)
                "install_app" -> FlowNewActions.runInstallApp(ctx, param)
                // PhoneLink v2.24.0: AppLocker owns the lock
                // set AND its PIN — Lab's separate PIN gate is not
                // ported, so lock/unlock only edit that set.
                "lock_app" -> {
                    val pkg = param.trim()
                    if (pkg.isEmpty()) {
                        FlowStore.log(ctx, "lock_app: empty package")
                    } else {
                        AppLocker.addLocked(ctx, pkg)
                        FlowStore.log(ctx, "lock_app: " + pkg)
                    }
                }
                "unlock_app" -> {
                    val pkg = param.trim()
                    if (pkg.isEmpty()) {
                        FlowStore.log(ctx, "unlock_app: empty package")
                    } else {
                        AppLocker.removeLocked(ctx, pkg)
                        FlowStore.log(ctx, "unlock_app: " + pkg)
                    }
                }
                "qr_show" -> {
                    // PhoneLink's QrShow + QrShowActivity (the class
                    // lives in QrShow.kt and reads QrShow.text).
                    QrShow.text = param
                    try {
                        val i = Intent(ctx, QrShowActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (e: Exception) {
                        FlowStore.log(ctx, "qr_show failed: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
                "torch_level" -> FlowNewActions.runTorchLevel(ctx, param)
                "screen_pin" -> FlowNewActions.runPin(ctx, true)
                "unpin" -> FlowNewActions.runPin(ctx, false)
                "mqtt_publish" -> {
                    // PhoneLink's MqttManager, mirroring the rule
                    // branch: false means not published.
                    if (!Config.mqttOn(ctx) || Config.mqttBroker(ctx).isBlank()) {
                        FlowStore.log(ctx, "mqtt not configured")
                    } else if (!MqttManager.publishParam(ctx, param)) {
                        FlowStore.log(ctx, "mqtt publish failed")
                    }
                }
                "dialog_choice" -> dialogChoice(ctx, param)
                else -> {
                    FlowStore.log(ctx, ctx.getString(R.string.flow_dropped_action, type))
                }
            }
        } catch (e: StopSignal) {
            throw e
        } catch (e: BreakSignal) {
            throw e
        } catch (e: Exception) {
            lastActionErrorLocal.set(e.message ?: e.javaClass.simpleName)
            FlowStore.log(ctx, "action " + type + " error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- v1.4.3: flow result, QR scan, WoL, ping, CallShield bridge ----------------

    /**
     * Writes the flow "result" var: a flow-local in a flow walk
     * (copied to the global "result" at flow end by
     * runFlowSyncFrom, and readable by later nodes as ${result}),
     * the global "result" for a rule action. The same line is
     * appended to the per-flow log when the walk belongs to a flow.
     */
    private fun setFlowResult(ctx: Context, value: String, logLine: String) {
        val state = flowStateLocal.get()
        if (state != null) {
            state.locals["result"] = value
            if (state.flowId.isNotEmpty()) {
                FlowStore.flowLogAppend(ctx, state.flowId, logLine)
            }
        } else {
            FlowStore.setVar(ctx, "result", value)
        }
    }

    /**
     * QR/barcode scan (v1.4.3). Param: timeout in seconds, empty =
     * 60, clamped 5..300. Launches FlowScanActivity (CameraX +
     * ML Kit, same blocking-latch pattern as FlowDialogs) and waits
     * for one code. Cancel/timeout logs clearly and leaves an empty
     * result — never a stale one. Needs CAMERA; the activity itself
     * is the foreground surface, so a background-triggered scan
     * behaves like the dialog actions (SYSTEM_ALERT_WINDOW is the
     * background-start allowance, same as FlowDialogActivity).
     */
    private fun runQrScan(ctx: Context, param: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FlowStore.log(ctx, "qr_scan called on main thread, ignored")
            return
        }
        val seconds = (param.trim().toIntOrNull() ?: 60).coerceIn(5, 300)
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            FlowStore.log(ctx, "qr_scan: camera permission not granted")
            return
        }
        val text = FlowScan.scan(ctx, seconds)
        if (text.isNullOrEmpty()) {
            setFlowResult(ctx, "", "qr_scan: cancelled or timed out")
            FlowStore.setVar(ctx, "qr_text", "")
            FlowStore.log(ctx, "qr_scan: cancelled or timed out")
        } else {
            setFlowResult(ctx, text, "qr_scan: " + text)
            FlowStore.setVar(ctx, "qr_text", text)
            FlowStore.log(ctx, "qr_scan: " + text)
        }
    }

    /**
     * Wake-on-LAN (v1.4.3). Param: MAC|broadcastIP|port, only the
     * MAC is required; defaults 255.255.255.255 / 9. Sends one
     * magic packet (6 x 0xFF + 16 x MAC) as a UDP broadcast. No
     * root, no special permission beyond INTERNET. The packet is
     * fire-and-forget: a "sent" log means the datagram left the
     * phone, not that the target woke up.
     */
    private fun runWol(ctx: Context, param: String) {
        val parts = param.split("|")
        val mac = parts.getOrNull(0)?.trim() ?: ""
        val ip = parts.getOrNull(1)?.trim()?.ifEmpty { "255.255.255.255" }
            ?: "255.255.255.255"
        val port = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 9
        val macBytes = parseMac(mac)
        if (macBytes == null) {
            FlowStore.log(ctx, "wol: bad MAC address: " + mac)
            return
        }
        try {
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0 until 6) {
                packet[i] = 0xFF.toByte()
            }
            for (r in 0 until 16) {
                System.arraycopy(macBytes, 0, packet, 6 + r * 6, 6)
            }
            val socket = java.net.DatagramSocket()
            socket.broadcast = true
            val addr = java.net.InetAddress.getByName(ip)
            socket.send(java.net.DatagramPacket(packet, packet.size, addr, port))
            socket.close()
            FlowStore.log(ctx, "wol: magic packet sent to " + mac + " via " + ip + ":" + port)
        } catch (e: Exception) {
            FlowStore.log(ctx, "wol failed to " + mac + ": " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun parseMac(mac: String): ByteArray? {
        val parts = mac.split(":", "-")
        if (parts.size != 6) {
            return null
        }
        val out = ByteArray(6)
        for (i in 0 until 6) {
            val v = parts[i].toIntOrNull(16) ?: return null
            if (v < 0 || v > 255) {
                return null
            }
            out[i] = v.toByte()
        }
        return out
    }

    /**
     * Ping (v1.4.3). Param: host|count|timeoutMs, only the host is
     * required; defaults count 4, timeout 2000 ms. Uses
     * /system/bin/ping, not InetAddress reachability: on Android
     * without root, isReachable falls back to a TCP echo on port 7,
     * which almost nothing answers, so it would report live hosts
     * as dead. The system ping binary sends real ICMP echo. Result
     * "true"/"false" goes to the flow "result" var (or the global
     * one for rules); the average reply time in ms goes to the
     * global "ping_ms" var (empty when unreachable).
     */
    private fun runPing(ctx: Context, param: String) {
        val parts = param.split("|")
        val host = parts.getOrNull(0)?.trim() ?: ""
        val count = (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 4).coerceIn(1, 10)
        val timeoutMs = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 2000
        if (host.isEmpty()) {
            FlowStore.log(ctx, "ping: no host")
            return
        }
        try {
            val timeoutSec = maxOf(1, timeoutMs / 1000).toString()
            val proc = ProcessBuilder(
                "/system/bin/ping", "-c", count.toString(), "-W", timeoutSec, host
            ).redirectErrorStream(true).start()
            val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroy()
                FlowStore.log(ctx, "ping " + host + ": timed out")
                return
            }
            val out = proc.inputStream.bufferedReader().readText()
            val reachable = proc.exitValue() == 0
            val times = Regex("time=([0-9.]+)").findAll(out)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            val ms = if (times.isNotEmpty()) "%.1f".format(times.average()) else ""
            val line = "ping " + host + ": " + if (reachable) {
                "reachable" + if (ms.isNotEmpty()) " (" + ms + " ms)" else ""
            } else {
                "unreachable"
            }
            setFlowResult(ctx, if (reachable) "true" else "false", line)
            FlowStore.setVar(ctx, "ping_ms", ms)
            FlowStore.log(ctx, line)
        } catch (e: Exception) {
            FlowStore.log(ctx, "ping failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * v1.5.0: answer_call/end_call try Shizuku FIRST, CallShield
     * bridge second. WHY THIS ORDER: `input keyevent` is a
     * system-wide key injection — it reaches the phone app no
     * matter which window is in the foreground, so unlike the
     * Accessibility path it does NOT require the dialer to be
     * the active window (manual Run-now from Lab puts Lab
     * itself in front, which made every bridge attempt fail
     * with BUTTON_NOT_FOUND on the XOS dialer). Only one path
     * runs per command: a Shizuku exit code of 0 ends the
     * routing; any other outcome (not granted, non-zero exit,
     * exception, timeout) falls back to the unchanged
     * CallShield bridge below. Dispatch success is NOT a
     * guarantee the call state changed — a dialer may ignore
     * the key — and the log says exactly that, never more.
     */
    private fun sendCallCommand(ctx: Context, cmd: String) {
        // PhoneLink v2.24.0: Shizuku keyevent ONLY. Lab's CallShield
        // bridge fallback is NOT available here — PhoneLink has no
        // CallShield token settings card — so that case is logged
        // plainly as unavailable, never faked. Dispatch only, not a
        // call-state guarantee (the dialer may ignore the key).
        val keyCode = if (cmd == "answer") 5 else 6
        if (!ShizukuHelper.granted()) {
            FlowStore.log(ctx, "call " + cmd + ": Shizuku not available, CallShield bridge not available in PhoneLink")
            return
        }
        val code = ShizukuHelper.runCode("input keyevent " + keyCode)
        if (code == 0) {
            FlowStore.log(ctx, "call " + cmd + " acted (shizuku keyevent " + keyCode + ")")
        } else {
            FlowStore.log(
                ctx,
                "call " + cmd + " shizuku keyevent failed (" +
                    (ShizukuHelper.lastError ?: ("exit " + code)) + ")"
            )
        }
    }

    private fun setRuleEnabled(ctx: Context, param: String, on: Boolean) {
        // PhoneLink rules via Automation: lookup by id-or-name,
        // then Automation.setEnabled (which itself fires the
        // rule_toggled event to the RULE engine, never to Flow).
        val target = Automation.rules(ctx).firstOrNull {
            it.optString("id") == param.trim() ||
                it.optString("name") == param.trim()
        }
        if (target != null) {
            Automation.setEnabled(ctx, target.optString("id"), on)
        }
    }

    // ---------------- FocusLock (contract mirror) ----------------

    fun parseFocusLockParam(param: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (seg in param.split("|")) {
            val idx = seg.indexOf("=")
            if (idx > 0) {
                out[seg.substring(0, idx).trim()] = seg.substring(idx + 1).trim()
            }
        }
        return out
    }

    private fun runFocusLock(ctx: Context, param: String) {
        val map = parseFocusLockParam(param)
        val token = Config.focusLockToken(ctx)
        if (token.isEmpty()) {
            FlowStore.log(ctx, "focuslock: token missing, set it on the Automation screen FocusLock card")
            return
        }
        val intent = Intent(FOCUSLOCK_ACTION)
        intent.setPackage(FOCUSLOCK_PKG)
        intent.putExtra("token", token)
        intent.putExtra("cmd", map["cmd"] ?: "lock_full")
        map["app"]?.let { intent.putExtra("app", it) }
        map["mins"]?.let { intent.putExtra("mins", it.toIntOrNull() ?: 0) }
        map["preset"]?.let { intent.putExtra("preset", it) }
        ctx.sendBroadcast(intent)
        FlowStore.log(ctx, "focuslock sent: " + (map["cmd"] ?: "lock_full"))
    }

    private fun sendGenericIntent(ctx: Context, param: String) {
        var action = ""
        var pkg: String? = null
        val extras = mutableMapOf<String, String>()
        for (seg in param.split("|")) {
            val idx = seg.indexOf("=")
            if (idx <= 0) {
                continue
            }
            val k = seg.substring(0, idx).trim()
            val v = seg.substring(idx + 1).trim()
            when (k) {
                "action" -> action = v
                "package" -> pkg = v
                "extras" -> {
                    for (pair in v.split(";")) {
                        val ei = pair.indexOf("=")
                        if (ei > 0) {
                            extras[pair.substring(0, ei).trim()] = pair.substring(ei + 1).trim()
                        }
                    }
                }
            }
        }
        if (action.isEmpty()) {
            return
        }
        val intent = Intent(action)
        if (pkg != null) {
            intent.setPackage(pkg)
        }
        for ((k, v) in extras) {
            intent.putExtra(k, v)
        }
        ctx.sendBroadcast(intent)
    }

    private fun dialogChoice(ctx: Context, param: String) {
        val activity = ctx as? android.app.Activity ?: run {
            FlowStore.log(ctx, "dialog_choice needs a visible screen")
            return
        }
        val parts = param.split("|", limit = 2)
        if (parts.size != 2) {
            return
        }
        val options = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (options.isEmpty()) {
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(parts[0])
                    .setItems(options.toTypedArray()) { _, which ->
                        FlowStore.setVar(ctx, "dialog_choice", options[which])
                        latch.countDown()
                    }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    // ---------------- small helpers ----------------

    private var torchOn = false

    private fun setTorch(ctx: Context, on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
        }
    }

    private fun toggleTorch(ctx: Context) {
        setTorch(ctx, !torchOn)
    }

    private fun flashPattern(ctx: Context, param: String) {
        val steps = param.split(",").mapNotNull { it.trim().toLongOrNull() }
        var on = true
        for (ms in steps) {
            setTorch(ctx, on)
            on = !on
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                break
            }
        }
        setTorch(ctx, false)
    }

    private fun vibrate(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(300)
            }
        } catch (_: Exception) {
        }
    }

    private var tts: android.speech.tts.TextToSpeech? = null
    private var pendingSpeech: String? = null

    private fun speak(ctx: Context, text: String) {
        try {
            val current = tts
            if (current == null) {
                pendingSpeech = text
                tts = android.speech.tts.TextToSpeech(ctx) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        val say = pendingSpeech
                        pendingSpeech = null
                        if (say != null) {
                            tts?.speak(say, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "flow")
                        }
                    }
                }
            } else {
                current.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "flow")
            }
        } catch (_: Exception) {
        }
    }

    private fun localNotify(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    ctx.getString(R.string.notif_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = ctx.getString(R.string.notif_channel_desc)
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, NOTIF_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(ctx)
            }
            builder.setContentTitle(ctx.getString(R.string.app_name))
            builder.setContentText(text)
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            nm.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
        } catch (_: Exception) {
            toastMain(ctx, text)
        }
    }

    private fun richNotify(ctx: Context, param: String) {
        val parts = param.split("|")
        if (parts.isEmpty()) {
            return
        }
        val title = parts[0]
        val body = if (parts.size > 1) parts[1] else ""
        val buttons = if (parts.size > 2) {
            parts[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        } else {
            emptyList()
        }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    ctx.getString(R.string.notif_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(ctx, NOTIF_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(ctx)
            }
            builder.setContentTitle(title)
            builder.setContentText(body)
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            for ((index, label) in buttons.withIndex()) {
                val intent = Intent(ctx, FlowActionReceiver::class.java)
                intent.action = "flow_notify_button"
                intent.putExtra("button", index)
                intent.putExtra("label", label)
                val flags = if (Build.VERSION.SDK_INT >= 31) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    ctx, index, intent, flags
                )
                builder.addAction(android.app.Notification.Action.Builder(null, label, pi).build())
            }
            nm.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
        } catch (_: Exception) {
            localNotify(ctx, title + " " + body)
        }
    }

    private fun shareToPackage(ctx: Context, pkg: String, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.setPackage(pkg)
            intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {
            FlowStore.log(ctx, "share: " + pkg + " not installed")
        }
    }

    private fun sendSms(ctx: Context, number: String, body: String) {
        try {
            if (!PermChecks.has(ctx, android.Manifest.permission.SEND_SMS)) {
                FlowStore.log(ctx, "send_sms needs SEND_SMS")
                return
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val sentIntent = Intent(ctx, FlowSystemReceiver::class.java)
            sentIntent.action = "flow_sms_sent"
            sentIntent.putExtra("number", number)
            val sentPending = android.app.PendingIntent.getBroadcast(
                ctx, number.hashCode(), sentIntent, flags
            )
            val deliveredIntent = Intent(ctx, FlowSystemReceiver::class.java)
            deliveredIntent.action = "flow_sms_delivered"
            deliveredIntent.putExtra("number", number)
            val deliveredPending = android.app.PendingIntent.getBroadcast(
                ctx, number.hashCode() + 1, deliveredIntent, flags
            )
            val sm = android.telephony.SmsManager.getDefault()
            val parts = sm.divideMessage(body)
            for ((index, part) in parts.withIndex()) {
                // Only the last part carries the delivery intents, so
                // sms_sent / sms_delivered fire once per message.
                if (index == parts.size - 1) {
                    sm.sendTextMessage(number, null, part, sentPending, deliveredPending)
                } else {
                    sm.sendTextMessage(number, null, part, null, null)
                }
            }
        } catch (e: Exception) {
            FlowStore.log(ctx, "send_sms error: " + (e.message ?: ""))
        }
    }

    private fun httpGet(url: String): String {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText()?.take(65536) ?: ""
            conn.disconnect()
            body
        } catch (e: Exception) {
            "http error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun flowFilesDir(ctx: Context): java.io.File {
        val d = java.io.File(ctx.filesDir, "flowfiles")
        if (!d.exists()) {
            d.mkdirs()
        }
        return d
    }

    private fun writeFlowFile(ctx: Context, name: String, content: String) {
        try {
            java.io.File(flowFilesDir(ctx), name).writeText(content)
        } catch (_: Exception) {
        }
    }

    private fun readFlowFile(ctx: Context, name: String): String {
        return try {
            java.io.File(flowFilesDir(ctx), name).readText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun copyFlowFile(ctx: Context, src: String, dst: String, move: Boolean) {
        try {
            val s = java.io.File(flowFilesDir(ctx), src)
            val d = java.io.File(flowFilesDir(ctx), dst)
            s.copyTo(d, overwrite = true)
            if (move) {
                s.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun zipFlowFile(ctx: Context, src: String, zipName: String) {
        try {
            val s = java.io.File(flowFilesDir(ctx), src)
            val out = java.io.File(flowFilesDir(ctx), zipName)
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(out)).use { zos ->
                if (s.isFile) {
                    zos.putNextEntry(java.util.zip.ZipEntry(s.name))
                    s.inputStream().copyTo(zos)
                    zos.closeEntry()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Flow-relative paths resolve under the flow files dir
     *  (filesDir/flowfiles); absolute paths are used as-is
     *  (Shizuku commands, media). */
    private fun resolveFlowFile(ctx: Context, path: String): java.io.File {
        val p = path.trim()
        return if (p.startsWith("/")) {
            java.io.File(p)
        } else {
            java.io.File(flowFilesDir(ctx), p)
        }
    }

    /** v1.3.0: unzip with a zip-slip guard — no entry may escape
     *  the destination directory. */
    private fun unzipFlowFile(ctx: Context, zipName: String, destDir: String) {
        try {
            val zip = resolveFlowFile(ctx, zipName)
            val dest = java.io.File(flowFilesDir(ctx), destDir)
            if (!dest.exists()) {
                dest.mkdirs()
            }
            val destCanon = dest.canonicalPath + java.io.File.separator
            java.util.zip.ZipInputStream(java.io.FileInputStream(zip)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = java.io.File(dest, entry.name)
                    if (!out.canonicalPath.startsWith(destCanon)) {
                        FlowStore.log(ctx, "unzip: blocked escaping entry " + entry.name)
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        java.io.FileOutputStream(out).use { fos ->
                            zin.copyTo(fos)
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            FlowStore.log(ctx, "unzip done: " + zipName + " -> " + destDir)
        } catch (e: Exception) {
            FlowStore.log(ctx, "unzip error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- v1.3.0: Shizuku helpers ----------------

    /** Single-quote a shell argument (escape embedded quotes). */
    private fun shQuote(s: String): String {
        return "'" + s.replace("'", "'\"'\"'") + "'"
    }

    /**
     * Run a shell command via Shizuku. Returns trimmed stdout, or
     * null when Shizuku is missing/ungranted (logs a clear message
     * in that case). Never throws.
     */
    private fun shRun(ctx: Context, command: String): String? {
        if (!ShizukuHelper.granted()) {
            FlowStore.log(ctx, ctx.getString(R.string.flow_shizuku_needed))
            return null
        }
        val out = ShizukuHelper.run(command)
        if (ShizukuHelper.lastError != null) {
            FlowStore.log(ctx, "shizuku error: " + ShizukuHelper.lastError)
            return null
        }
        return out
    }

    /** Screencap to /sdcard/Pictures/Lab via Shizuku (the shell
     *  user can write there directly). Logs the path or the miss. */
    private fun screenshotViaShizuku(ctx: Context) {
        val stamp = System.currentTimeMillis().toString()
        val path = "/sdcard/Pictures/PhoneLink/lab_shot_" + stamp + ".png"
        val out = shRun(ctx, "mkdir -p /sdcard/Pictures/Lab && screencap -p " + path)
        if (out != null) {
            FlowStore.setVar(ctx, "screenshot_path", path)
            FlowStore.log(ctx, "screenshot saved: " + path)
        }
    }

    // ---------------- v1.3.0: connectivity helpers ----------------

    @Volatile
    private var hotspotActive = false

    @Volatile
    private var hotspotReservation: Any? = null

    /**
     * Local-only hotspot (WifiManager.startLocalOnlyHotspot, public
     * API since 26). This is NOT the internet-sharing tethering
     * toggle — that needs a privileged tethering call Lab cannot
     * make. The reservation is kept so "off" can close it.
     */
    private fun hotspotSet(ctx: Context, on: Boolean) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            if (!on) {
                (hotspotReservation as? android.net.wifi.WifiManager.LocalOnlyHotspotReservation)?.close()
                hotspotReservation = null
                hotspotActive = false
                FlowStore.log(ctx, "hotspot off")
                onEvent(ctx, "hotspot_off", JSONObject())
                return
            }
            if (hotspotActive) {
                FlowStore.log(ctx, "hotspot already on")
                return
            }
            wm.startLocalOnlyHotspot(
                object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        hotspotActive = true
                        FlowStore.log(ctx, "hotspot on (local-only)")
                        onEvent(ctx, "hotspot_on", JSONObject())
                    }

                    override fun onFailed(reason: Int) {
                        FlowStore.log(ctx, "hotspot failed: " + reason)
                    }
                },
                null
            )
        } catch (e: Exception) {
            FlowStore.log(ctx, "hotspot error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Best-effort A2DP connect to a paired device by name. The
     * BluetoothA2dp.connect method is hidden, so it is reached by
     * reflection and may fail on some builds — the failure is
     * logged, never hidden.
     */
    private fun btConnect(ctx: Context, name: String) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.BLUETOOTH_CONNECT
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                FlowStore.log(ctx, "bt_connect needs BLUETOOTH_CONNECT")
                return
            }
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                FlowStore.log(ctx, "bt_connect: no adapter")
                return
            }
            val device = adapter.bondedDevices.firstOrNull { it.name == name }
            if (device == null) {
                FlowStore.log(ctx, "bt_connect: not paired: " + name)
                return
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var proxy: android.bluetooth.BluetoothProfile? = null
            adapter.getProfileProxy(
                ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: android.bluetooth.BluetoothProfile) {
                        proxy = p
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        latch.countDown()
                    }
                },
                android.bluetooth.BluetoothProfile.A2DP
            )
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val p = proxy
            if (p == null) {
                FlowStore.log(ctx, "bt_connect: no A2DP proxy")
                return
            }
            val m = p.javaClass.getMethod("connect", android.bluetooth.BluetoothDevice::class.java)
            val ok = m.invoke(p, device) as? Boolean ?: false
            FlowStore.log(ctx, "bt_connect " + name + ": " + ok)
        } catch (e: Exception) {
            FlowStore.log(ctx, "bt_connect error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Connect to an SSID via WifiNetworkSpecifier (Android 10+).
     * The system shows a user-approval dialog — the request is
     * fire-and-forget; the callback logs the outcome.
     */
    private fun wifiConnect(ctx: Context, ssid: String, password: String?) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                FlowStore.log(ctx, "wifi_connect needs Android 10+")
                return
            }
            val specBuilder = android.net.wifi.WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
            if (!password.isNullOrEmpty()) {
                specBuilder.setWpa2Passphrase(password)
            }
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specBuilder.build())
                .build()
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.requestNetwork(
                request,
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        FlowStore.log(ctx, "wifi connected: " + ssid)
                    }

                    override fun onUnavailable() {
                        FlowStore.log(ctx, "wifi unavailable: " + ssid)
                    }

                    override fun onLost(network: android.net.Network) {
                        FlowStore.log(ctx, "wifi lost: " + ssid)
                    }
                }
            )
            FlowStore.log(ctx, "wifi_connect requested: " + ssid + " (approve the system dialog)")
        } catch (e: Exception) {
            FlowStore.log(ctx, "wifi_connect error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- v1.3.0: location ----------------

    /**
     * Last-known GPS/network location first; if none, one single
     * update with a 10s latch (engine threads only — never call
     * from the main thread). Writes vars lat/lon.
     */
    private fun getLocationVars(ctx: Context) {
        try {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                FlowStore.log(ctx, "get_location needs location permission")
                return
            }
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            var loc = try {
                lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            } catch (_: Exception) {
                null
            }
            if (loc == null) {
                loc = try {
                    lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) {
                    null
                }
            }
            if (loc == null) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var fresh: android.location.Location? = null
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) {
                        fresh = l
                        latch.countDown()
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                    }

                    override fun onProviderEnabled(provider: String) {
                    }

                    override fun onProviderDisabled(provider: String) {
                    }
                }
                try {
                    val criteria = android.location.Criteria()
                    lm.requestSingleUpdate(criteria, listener, android.os.Looper.getMainLooper())
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
                loc = fresh
            }
            if (loc == null) {
                FlowStore.log(ctx, "get_location: no fix")
                return
            }
            FlowStore.setVar(ctx, "lat", loc.latitude.toString())
            FlowStore.setVar(ctx, "lon", loc.longitude.toString())
            FlowStore.log(ctx, "location: " + loc.latitude + "," + loc.longitude)
        } catch (e: Exception) {
            FlowStore.log(ctx, "get_location error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------- v1.3.0: image / crypto / ftp ----------------

    private fun imageResize(ctx: Context, src: String, dst: String, w: Int, h: Int) {
        try {
            if (w <= 0 || h <= 0) {
                FlowStore.log(ctx, "image_resize needs width|height > 0")
                return
            }
            val inFile = resolveFlowFile(ctx, src)
            val bmp = android.graphics.BitmapFactory.decodeFile(inFile.absolutePath)
            if (bmp == null) {
                FlowStore.log(ctx, "image_resize: cannot decode " + src)
                return
            }
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            val outFile = resolveFlowFile(ctx, dst)
            outFile.parentFile?.mkdirs()
            java.io.FileOutputStream(outFile).use { fos ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
            }
            FlowStore.log(ctx, "image resized: " + src + " -> " + dst)
        } catch (e: Exception) {
            FlowStore.log(ctx, "image_resize error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * AES/CBC/PKCS5Padding with a SHA-256(passphrase) key. Encrypt
     * prepends the random 16-byte IV; decrypt reads it back.
     */
    private fun cryptoFile(ctx: Context, src: String, dst: String, pass: String, encrypt: Boolean) {
        try {
            val keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(pass.toByteArray(Charsets.UTF_8))
            val key = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val inBytes = resolveFlowFile(ctx, src).readBytes()
            val outBytes = if (encrypt) {
                val iv = ByteArray(16)
                java.security.SecureRandom().nextBytes(iv)
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
                iv + cipher.doFinal(inBytes)
            } else {
                if (inBytes.size <= 16) {
                    FlowStore.log(ctx, "file_decrypt: file too short (no IV)")
                    return
                }
                val iv = inBytes.copyOfRange(0, 16)
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
                cipher.doFinal(inBytes.copyOfRange(16, inBytes.size))
            }
            val outFile = resolveFlowFile(ctx, dst)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(outBytes)
            FlowStore.log(ctx, (if (encrypt) "file encrypted: " else "file decrypted: ") + src + " -> " + dst)
        } catch (e: Exception) {
            FlowStore.log(
                ctx,
                (if (encrypt) "file_encrypt" else "file_decrypt") +
                    " error: " + (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /**
     * Minimal passive-mode FTP (plain sockets, no TLS). Best-effort:
     * any protocol surprise is logged, never hidden. Upload reads a
     * lab file; download writes one. 15s socket timeouts.
     */
    private fun ftpTransfer(
        ctx: Context,
        upload: Boolean,
        host: String,
        user: String,
        pass: String,
        local: String,
        remote: String
    ) {
        var ctrl: java.net.Socket? = null
        var data: java.net.Socket? = null
        try {
            ctrl = java.net.Socket()
            ctrl.soTimeout = 15000
            ctrl.connect(java.net.InetSocketAddress(host, 21), 15000)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(ctrl.getInputStream()))
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(ctrl.getOutputStream()))
            fun readReply(): String {
                var line = reader.readLine() ?: ""
                var last = line
                while (line.length > 3 && line[3] == '-') {
                    line = reader.readLine() ?: ""
                    last = line
                }
                return last
            }
            fun cmd(c: String): String {
                writer.write(c + "\r\n")
                writer.flush()
                return readReply()
            }
            readReply() // server greeting on connect (read, never answered)
            val userResp = cmd("USER " + user)
            if (!userResp.startsWith("2") && !userResp.startsWith("3")) {
                FlowStore.log(ctx, "ftp login failed: " + userResp)
                return
            }
            if (userResp.startsWith("3")) {
                val passResp = cmd("PASS " + pass)
                if (!passResp.startsWith("2")) {
                    FlowStore.log(ctx, "ftp login failed: " + passResp)
                    return
                }
            }
            cmd("TYPE I")
            val pasv = cmd("PASV")
            val m = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(pasv)
            if (m == null) {
                FlowStore.log(ctx, "ftp PASV failed: " + pasv)
                return
            }
            val dataHost = (1..4).joinToString(".") { m.groupValues[it] }
            val dataPort = m.groupValues[5].toInt() * 256 + m.groupValues[6].toInt()
            data = java.net.Socket()
            data.soTimeout = 15000
            data.connect(java.net.InetSocketAddress(dataHost, dataPort), 15000)
            if (upload) {
                val bytes = resolveFlowFile(ctx, local).readBytes()
                val stor = cmd("STOR " + remote)
                if (!stor.startsWith("1")) {
                    FlowStore.log(ctx, "ftp STOR refused: " + stor)
                    return
                }
                data.getOutputStream().write(bytes)
                data.getOutputStream().flush()
                data.close()
                data = null
                val done = readReply()
                FlowStore.log(ctx, "ftp uploaded: " + local + " -> " + remote + " (" + done + ")")
            } else {
                val retr = cmd("RETR " + remote)
                if (!retr.startsWith("1")) {
                    FlowStore.log(ctx, "ftp RETR refused: " + retr)
                    return
                }
                val bytes = data.getInputStream().readBytes()
                data.close()
                data = null
                val done = readReply()
                val outFile = resolveFlowFile(ctx, local)
                outFile.parentFile?.mkdirs()
                outFile.writeBytes(bytes)
                FlowStore.log(ctx, "ftp downloaded: " + remote + " -> " + local + " (" + done + ")")
            }
        } catch (e: Exception) {
            FlowStore.log(ctx, "ftp error: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            try {
                data?.close()
            } catch (_: Exception) {
            }
            try {
                ctrl?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun jsonPath(src: String, path: String): String? {
        return try {
            var cur: Any? = org.json.JSONObject(src)
            for (seg in path.split(".")) {
                cur = when (cur) {
                    is org.json.JSONObject -> cur.opt(seg)
                    is org.json.JSONArray -> cur.opt(seg.toIntOrNull() ?: -1)
                    else -> null
                }
                if (cur == null || cur == JSONObject.NULL) {
                    return null
                }
            }
            cur.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun dateAdd(date: String, days: String): String {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val d = fmt.parse(date) ?: return ""
            val cal = Calendar.getInstance()
            cal.time = d
            cal.add(Calendar.DAY_OF_YEAR, days.toIntOrNull() ?: 0)
            fmt.format(cal.time)
        } catch (_: Exception) {
            ""
        }
    }

    private class CalcParser(private val text: String) {
        private var pos = 0

        fun parse(): Double {
            val v = parseSum()
            return v
        }

        private fun skip() {
            while (pos < text.length && text[pos] == ' ') {
                pos++
            }
        }

        private fun parseSum(): Double {
            var v = parseTerm()
            while (true) {
                skip()
                if (pos >= text.length) {
                    break
                }
                val c = text[pos]
                if (c != '+' && c != '-') {
                    break
                }
                pos++
                val rhs = parseTerm()
                v = if (c == '+') v + rhs else v - rhs
            }
            return v
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skip()
                if (pos >= text.length) {
                    break
                }
                val c = text[pos]
                if (c != '*' && c != '/') {
                    break
                }
                pos++
                val rhs = parseFactor()
                v = if (c == '*') v * rhs else if (rhs == 0.0) 0.0 else v / rhs
            }
            return v
        }

        private fun parseFactor(): Double {
            skip()
            if (pos < text.length && text[pos] == '(') {
                pos++
                val v = parseSum()
                skip()
                if (pos < text.length && text[pos] == ')') {
                    pos++
                }
                return v
            }
            if (pos < text.length && text[pos] == '-') {
                pos++
                return -parseFactor()
            }
            val start = pos
            while (pos < text.length &&
                (text[pos].isDigit() || text[pos] == '.')
            ) {
                pos++
            }
            return text.substring(start, pos).toDoubleOrNull() ?: 0.0
        }
    }

    private fun evalCalc(ctx: Context, expr: String): String {
        var replaced = expr
        val varPattern = Regex("\\{([A-Za-z0-9_]+)\\}")
        replaced = varPattern.replace(replaced) { m ->
            FlowStore.getVar(ctx, m.groupValues[1]).ifEmpty { "0" }
        }
        return try {
            val v = CalcParser(replaced).parse()
            if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
