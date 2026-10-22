package com.mthtamim.phonelink

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * v2.4 automation engine. Rules are JSON objects:
 * {id, name, enabled, trigger:{type,param}, constraints:[{type,param}],
 *  actions:[{type,param}]} kept in the "phonelink_auto" prefs.
 * Existing app events (SMS, notifications, calls, battery, geofence…)
 * are fed in through onEvent(); time-based and polled triggers run on
 * tick(), which LinkService calls from its heartbeat.
 */
object Automation {

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_auto", Context.MODE_PRIVATE)

    // ---------------- rule store ----------------
    fun rules(ctx: Context): MutableList<JSONObject> {
        val raw = prefs(ctx).getString("rules", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    fun saveAll(ctx: Context, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        val newJson = arr.toString()
        // v2.9: keep the last 5 previous rule sets as restorable backups.
        val oldJson = prefs(ctx).getString("rules", "[]") ?: "[]"
        if (oldJson != newJson && oldJson.length > 2) {
            try {
                val backs = try { JSONArray(
                    prefs(ctx).getString("rules_backups", "[]")) }
                    catch (e: Exception) { JSONArray() }
                backs.put(JSONObject().put("t", System.currentTimeMillis())
                    .put("json", oldJson))
                while (backs.length() > 5) backs.remove(0)
                prefs(ctx).edit().putString("rules_backups",
                    backs.toString()).apply()
            } catch (_: Exception) { }
        }
        prefs(ctx).edit().putString("rules", newJson).apply()
        refreshWatchers(ctx)
        scheduleTimeRules(ctx)
        // v2.9: optional two-phone rule sync over the existing relay.
        if (!applyingRemote && Config.rulesSyncOn(ctx) && newJson.length < 120_000) {
            try { Relay.send(ctx, "RULESYNC", "", newJson) } catch (_: Exception) { }
        }
    }

    /** v2.9: replaces the rule set with one received from the peer phone. */
    fun applyRemoteRules(ctx: Context, json: String): Boolean {
        val incoming = try { JSONArray(json) } catch (e: Exception) { return false }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until incoming.length())
            incoming.optJSONObject(i)?.let { list.add(it) }
        applyingRemote = true
        try { saveAll(ctx, list) } finally { applyingRemote = false }
        return true
    }

    /** v2.9: (time, json) pairs of the stored rule backups, newest first. */
    fun backups(ctx: Context): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        try {
            val arr = JSONArray(prefs(ctx).getString("rules_backups", "[]") ?: "[]")
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optLong("t") to o.optString("json"))
            }
        } catch (_: Exception) { }
        return out
    }

    fun restoreBackup(ctx: Context, json: String): Boolean {
        val arr = try { JSONArray(json) } catch (e: Exception) { return false }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        saveAll(ctx, list)
        return true
    }

    fun upsert(ctx: Context, rule: JSONObject) {
        val list = rules(ctx)
        val id = rule.optString("id")
        val idx = list.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, rules(ctx).filter { it.optString("id") != id })
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        val list = rules(ctx)
        val target = list.firstOrNull { it.optString("id") == id }
        target?.put("enabled", on)
        saveAll(ctx, list)
        if (target != null) onEvent(ctx, "rule_toggled", JSONObject()
            .put("title", target.optString("name"))
            .put("body", if (on) "on" else "off"))
    }

    // ---------------- v2.8: run log + variable index ----------------
    fun log(ctx: Context, msg: String) {
        try {
            val arr = try { JSONArray(prefs(ctx).getString("auto_log", "[]")) }
                catch (e: Exception) { JSONArray() }
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg))
            while (arr.length() > 50) arr.remove(0)
            prefs(ctx).edit().putString("auto_log", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    fun recentLog(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs(ctx).getString("auto_log", "[]") ?: "[]")
            val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fmt.format(Date(o.optLong("t"))) + " — " + o.optString("m"))
            }
        } catch (_: Exception) { }
        return out
    }

    fun clearLog(ctx: Context) {
        prefs(ctx).edit().remove("auto_log").apply()
    }

    fun exportJson(ctx: Context): String =
        prefs(ctx).getString("rules", "[]") ?: "[]"

    fun importJson(ctx: Context, text: String): Int {
        val incoming = try { JSONArray(text) } catch (e: Exception) { return -1 }
        val list = rules(ctx)
        val ids = list.map { it.optString("id") }.toMutableSet()
        var added = 0
        for (i in 0 until incoming.length()) {
            val r = incoming.optJSONObject(i) ?: continue
            var id = r.optString("id")
            if (id.isEmpty() || id in ids) {
                id = "r" + System.currentTimeMillis() + "_" + i
                r.put("id", id)
            }
            ids.add(id)
            list.add(r); added++
        }
        if (added > 0) saveAll(ctx, list)
        return added
    }

    fun varNames(ctx: Context): List<String> {
        return try {
            val arr = JSONArray(prefs(ctx).getString("var_names", "[]") ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
    }

    fun deleteVar(ctx: Context, name: String) {
        val names = varNames(ctx).filter { it != name }
        val arr = JSONArray(); names.forEach { arr.put(it) }
        prefs(ctx).edit().remove("var_$name").putString("var_names", arr.toString()).apply()
    }

    // ---------------- variables ----------------
    fun getVar(ctx: Context, name: String): String =
        prefs(ctx).getString("var_$name", "") ?: ""

    fun setVar(ctx: Context, name: String, value: String) {
        val names = varNames(ctx)
        val e = prefs(ctx).edit().putString("var_$name", value)
        if (name !in names) {
            val arr = JSONArray(); (names + name).forEach { arr.put(it) }
            e.putString("var_names", arr.toString())
        }
        e.apply()
    }

    /** v2.9: a rule may carry several triggers ("trigger" + "triggers[]"). */
    fun ruleTriggers(rule: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        rule.optJSONObject("trigger")?.let { out.add(it) }
        val arr = rule.optJSONArray("triggers")
        if (arr != null) for (i in 0 until arr.length())
            arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    @Volatile var applyingRemote = false

    // ---------------- event entry ----------------
    fun onEvent(ctx: Context, event: String, data: JSONObject = JSONObject()) {
        if (!Config.automationOn(ctx)) return
        Thread {
            try {
                val now = System.currentTimeMillis()
                for (rule in rules(ctx)) {
                    if (!rule.optBoolean("enabled", true)) continue
                    val trig = ruleTriggers(rule).firstOrNull {
                        it.optString("type") == event } ?: continue
                    if (!triggerParamMatches(ctx, event, trig.optString("param"), data)) continue
                    if (debounced(ctx, rule.optString("id"), now)) continue
                    if (!constraintsPass(ctx, rule)) continue
                    markFired(ctx, rule.optString("id"), now)
                    ruleNameHint = rule.optString("name")
                    log(ctx, "▶ " + rule.optString("name") + " (" + event + ")")
                    runActions(ctx, rule, data, 0)
                }
            } catch (e: Exception) { /* automation must never crash the host */ }
        }.start()
    }

    /** v2.14.1: single entry for app launch/close transitions, shared
     *  by the accessibility live path and the usage-stats poll; the
     *  appstate map guarantees each transition fires exactly once. */
    fun fireAppEvent(ctx: Context, kind: String, pkg: String) {
        try {
            val p = prefs(ctx)
            val states = (p.getString("appstate", "") ?: "").split(";")
                .filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
                .toMutableMap()
            if (states[pkg] == kind) return
            states[pkg] = kind
            p.edit().putString("appstate",
                states.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
            val label = appLabel(ctx, pkg)
            onEvent(ctx, kind, JSONObject().put("pkg", pkg)
                .put("app", label).put("title", label))
        } catch (_: Exception) { }
    }

    /** v2.14.1: the clipboard trigger previously had NO event source
     *  at all. Android 10+ only lets the focused app read the clipboard,
     *  so this runs when a change listener fires (app focused) and when
     *  the main screen resumes; it stays inert unless a clipboard rule
     *  exists. */
    fun checkClipboard(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val used = rules(app).any { r ->
                r.optBoolean("enabled", true) &&
                    ruleTriggers(r).any { it.optString("type") == "clipboard" }
            }
            if (!used) return
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(app)?.toString()
                ?.take(2000) ?: return
            if (text.isBlank()) return
            val p = prefs(app)
            val hash = text.hashCode()
            if (p.getInt("clip_hash", 0) == hash) return
            p.edit().putInt("clip_hash", hash).apply()
            onEvent(app, "clipboard", JSONObject()
                .put("title", "").put("body", text).put("app", ""))
        } catch (_: Exception) { }
    }

    private fun debounced(ctx: Context, ruleId: String, now: Long): Boolean {
        val rest = restSeconds(ctx)
        if (rest <= 0) return false
        val last = prefs(ctx).getLong("fired_$ruleId", 0L)
        return now - last < rest * 1000L
    }

    /** v2.13: rest before the same rule may fire again, in seconds
     *  (default 10, customizable on the automation screen). */
    fun restSeconds(ctx: Context): Int =
        prefs(ctx).getInt("debounce_sec", 10).coerceIn(0, 300)

    fun setRestSeconds(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt("debounce_sec",
            sec.coerceIn(0, 300)).apply()
    }

    private fun markFired(ctx: Context, ruleId: String, now: Long) {
        prefs(ctx).edit().putLong("fired_$ruleId", now).apply()
    }

    private fun triggerParamMatches(ctx: Context, event: String, param: String, data: JSONObject): Boolean {
        if (param.isBlank()) return true
        return when (event) {
            "sms_received", "notif_received", "screen_text", "notif_cleared", "clipboard",
            "sms_sent", "call_outgoing", "call_ended", "qr_scanned",
            "calendar_start", "calendar_end", "rule_toggled",
            "notify_button", "sms_delivered", "screenshot_taken",
            "photo_taken", "video_taken" -> {
                val hay = (data.optString("title") + " " + data.optString("body") +
                    " " + data.optString("app")).lowercase()
                hay.contains(param.lowercase())
            }
            "app_launched", "app_closed", "app_installed", "app_removed",
            "app_updated", "camera_used", "mic_used" ->
                data.optString("pkg") == param
            "bt_connected", "bt_disconnected", "bt_near", "bt_far" ->
                data.optString("title").contains(param, ignoreCase = true)
            "orientation_changed" -> data.optString("kind") == param
            "webhook" -> data.optString("title") == param
            "wifi_connected" -> data.optString("ssid").equals(param, true)
            "activity" -> data.optString("kind") == param
            else -> true
        }
    }

    // ---------------- constraints ----------------
    fun constraintsPass(ctx: Context, rule: JSONObject): Boolean {
        val arr = rule.optJSONArray("constraints") ?: return true
        val any = rule.optString("constraints_mode") == "any"
        var saw = false
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            saw = true
            var ok = constraintOk(ctx, c.optString("type"), c.optString("param"))
            if (c.optBoolean("negate")) ok = !ok
            if (any && ok) return true
            if (!any && !ok) return false
        }
        return if (any) !saw else true
    }

    private fun constraintOk(ctx: Context, type: String, param: String): Boolean {
        return try {
            when (type) {
                "time_range" -> {
                    val parts = param.split("-")
                    if (parts.size != 2) true else {
                        val now = minutesNow()
                        val s = parseHm(parts[0]); val e = parseHm(parts[1])
                        if (s <= e) now in s..e else now >= s || now <= e
                    }
                }
                "day_of_week" -> {
                    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                    param.split(",").map { it.trim() }.contains(today.toString())
                }
                "battery_above" -> batteryPct(ctx) >= (param.toIntOrNull() ?: 0)
                "battery_below" -> batteryPct(ctx) <= (param.toIntOrNull() ?: 100)
                "charging" -> isCharging(ctx) == (param != "off")
                "wifi_ssid" -> currentSsid(ctx).equals(param, true)
                "location_in" -> {
                    if (!Config.geoIsSet(ctx)) false else {
                        var inside = false
                        val latch = java.util.concurrent.CountDownLatch(1)
                        LocationHelper.fetchCoords(ctx, { lat, lng ->
                            val d = distanceM(lat, lng, Config.geoLat(ctx), Config.geoLng(ctx))
                            inside = d <= Config.geoRadius(ctx)
                            latch.countDown()
                        }, { latch.countDown() })
                        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                        inside
                    }
                }
                "screen" -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isInteractive == (param != "off")
                }
                "peer_online" -> System.currentTimeMillis() -
                    Config.lastSenderSeen(ctx) < 75L * 60 * 1000
                "foreground_app" -> foregroundPkg(ctx) == param
                "variable" -> evalVar(ctx, param)
                // ---- v2.7 ----
                "music_playing" -> musicActive(ctx) == (param != "off")
                "call_active" -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    @Suppress("DEPRECATION")
                    (try { tm.callState } catch (e: Exception) {
                        android.telephony.TelephonyManager.CALL_STATE_IDLE }) !=
                        android.telephony.TelephonyManager.CALL_STATE_IDLE
                }
                "vpn" -> isVpnOn(ctx) == (param != "off")
                "roaming" -> (try {
                    (ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager).isNetworkRoaming
                } catch (e: Exception) { false }) == (param != "off")
                "dark_mode" -> isDarkMode(ctx) == (param != "off")
                "dnd" -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    (nm.currentInterruptionFilter !=
                        android.app.NotificationManager.INTERRUPTION_FILTER_ALL) ==
                        (param != "off")
                }
                "charging_type" -> {
                    val bi = ctx.registerReceiver(null,
                        android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val plugged = bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                    val kind = when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                        else -> "none"
                    }
                    kind == param.trim().lowercase()
                }
                "battery_temp_below" -> {
                    val tv = batteryTempC(ctx)
                    tv >= 0 && tv <= (param.toFloatOrNull() ?: 999f)
                }
                "battery_temp_above" -> {
                    val tv = batteryTempC(ctx)
                    tv >= (param.toFloatOrNull() ?: -999f)
                }
                "steps_today" -> ctx.getSharedPreferences("phonelink_state",
                    Context.MODE_PRIVATE).getInt("steps_today", 0) >=
                    (param.toIntOrNull() ?: Int.MAX_VALUE)
                "proximity_near" ->
                    prefs(ctx).getBoolean("last_prox_near", false) == (param != "off")
                "light_below" -> {
                    val lux = prefs(ctx).getFloat("last_lux", -1f)
                    lux >= 0 && lux < (param.toFloatOrNull() ?: 0f)
                }
                "light_above" -> {
                    val lux = prefs(ctx).getFloat("last_lux", -1f)
                    lux > (param.toFloatOrNull() ?: Float.MAX_VALUE)
                }
                "airplane" -> (android.provider.Settings.Global.getInt(
                    ctx.contentResolver,
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1) ==
                    (param != "off")
                "peer_battery_above" ->
                    (Peers.list(ctx).firstOrNull()?.battery ?: -1) >=
                        (param.toIntOrNull() ?: 101)
                // ---- v2.8 ----
                "date_range" -> {
                    val parts = param.split("..")
                    if (parts.size != 2) true else {
                        val today = dayKey()
                        today >= parts[0].trim() && today <= parts[1].trim()
                    }
                }
                "day_of_month" -> {
                    val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                    param.split(",").map { it.trim() }.contains(today.toString())
                }
                "signal_above" -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    val lvl = try { tm.signalStrength?.level ?: -1 }
                        catch (e: Exception) { -1 }
                    lvl >= (param.toIntOrNull() ?: 5)
                }
                "rule_enabled" -> {
                    val wantOff = param.endsWith(":off")
                    val name = param.removeSuffix(":off").trim()
                    val target = rules(ctx).firstOrNull {
                        it.optString("name").equals(name, true) }
                    val on = target?.optBoolean("enabled", false) ?: false
                    on != wantOff
                }
                "bt_connected" -> {
                    val ad = BtShare.adapter()
                    val on = try {
                        ad != null && (ad.getProfileConnectionState(
                            android.bluetooth.BluetoothProfile.A2DP) ==
                            android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                            ad.getProfileConnectionState(
                                android.bluetooth.BluetoothProfile.HEADSET) ==
                            android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                    } catch (e: Exception) { false }
                    on == (param != "off")
                }
                // ---- v2.9 constraints ----
                "keyguard_locked" -> {
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE)
                        as android.app.KeyguardManager
                    km.isKeyguardLocked == (param != "off")
                }
                "battery_saver" -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE)
                        as android.os.PowerManager
                    pm.isPowerSaveMode == (param != "off")
                }
                "data_saver" -> {
                    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                    (cm.restrictBackgroundStatus ==
                        android.net.ConnectivityManager
                            .RESTRICT_BACKGROUND_STATUS_ENABLED) == (param != "off")
                }
                "speed_above" -> speedKmh(ctx) >= (param.toDoubleOrNull() ?: 0.0)
                "uptime_above" -> (android.os.SystemClock.elapsedRealtime() / 60_000) >=
                    (param.toLongOrNull() ?: 0)
                "calendar_busy" -> calendarBusyNow(ctx) == (param != "off")
                "headset_mic" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    @Suppress("DEPRECATION")
                    val on = am.isWiredHeadsetOn || am.isBluetoothA2dpOn
                    on == (param != "off")
                }
                "process_running" -> appRecentlyUsed(ctx, param.trim())
                "mobile_data" -> {
                    val on = try {
                        android.provider.Settings.Global.getInt(
                            ctx.contentResolver, "mobile_data", 0) == 1
                    } catch (e: Exception) { false }
                    on == (param != "off")
                }
                else -> true
            }
        } catch (e: Exception) { true }
    }

    private fun evalVar(ctx: Context, param: String): Boolean {
        // forms: name=value | name>12 | name<12
        val ops = listOf(">=", "<=", ">", "<", "=")
        for (op in ops) {
            val idx = param.indexOf(op)
            if (idx > 0) {
                val name = param.substring(0, idx).trim()
                val want = param.substring(idx + op.length).trim()
                val have = getVar(ctx, name)
                return when (op) {
                    "=" -> have == want
                    else -> {
                        val a = have.toDoubleOrNull() ?: return false
                        val b = want.toDoubleOrNull() ?: return false
                        when (op) { ">" -> a > b; "<" -> a < b; ">=" -> a >= b; else -> a <= b }
                    }
                }
            }
        }
        return getVar(ctx, param).isNotEmpty()
    }

    // ---------------- actions ----------------
    private const val SIG_OK = 0
    private const val SIG_STOP = 1
    private const val SIG_BREAK = 2

    fun runActions(ctx: Context, rule: JSONObject, data: JSONObject, fromIndex: Int) {
        val arr = rule.optJSONArray("actions") ?: return
        execRange(ctx, rule, data, fromIndex, arr.length(), 0)
    }

    /**
     * v2.9 interpreter: supports loop_start/loop_end blocks, if_var/else/
     * end_if blocks and an optional per-action "cond" constraint. A "wait"
     * at top level parks the rule (existing scheduleWait behaviour); inside
     * a loop it sleeps the (background) thread instead. A wait resumed after
     * parking continues flat from that index (the loop is not re-entered).
     */
    private fun execRange(ctx: Context, rule: JSONObject, data: JSONObject,
                          start: Int, end: Int, depth: Int): Int {
        val arr = rule.optJSONArray("actions") ?: return SIG_OK
        var i = start
        while (i < end) {
            val a = arr.optJSONObject(i)
            if (a == null) { i++; continue }
            val type = a.optString("type")
            val param = fill(a.optString("param"), data)
            when (type) {
                "wait" -> {
                    val mins = param.toLongOrNull() ?: 1
                    if (depth == 0) {
                        scheduleWait(ctx, rule, i + 1,
                            System.currentTimeMillis() + mins * 60_000, data)
                        return SIG_STOP
                    }
                    try { Thread.sleep(mins * 60_000) } catch (_: Exception) { }
                    i++
                }
                "stop" -> return SIG_STOP
                "break" -> return if (depth > 0) SIG_BREAK else SIG_STOP
                "loop_start" -> {
                    val endIdx = findLoopEnd(arr, i, end)
                    val bodyEnd = if (endIdx >= 0) endIdx else end
                    val count = loopCount(ctx, param)
                    var stopped = false
                    var n = 0
                    while (n < count && !stopped) {
                        val sig = execRange(ctx, rule, data, i + 1, bodyEnd, depth + 1)
                        if (sig == SIG_STOP) stopped = true
                        n++
                    }
                    if (stopped) return SIG_STOP
                    i = bodyEnd + 1
                }
                "loop_end" -> i++
                "if_var" -> {
                    if (evalVar(ctx, param)) i++
                    else {
                        val (elseIdx, endIdx) = findElseEnd(arr, i, end)
                        when {
                            elseIdx >= 0 -> i = elseIdx + 1
                            endIdx >= 0 -> i = endIdx + 1
                            depth > 0 -> return SIG_BREAK
                            else -> return SIG_STOP
                        }
                    }
                }
                "else" -> {
                    val (_, endIdx) = findElseEnd(arr, i, end)
                    i = if (endIdx >= 0) endIdx + 1 else end
                }
                "end_if" -> i++
                else -> {
                    val cond = a.optJSONObject("cond")
                    if (cond == null || constraintOk(ctx, cond.optString("type"),
                            fill(cond.optString("param"), data)))
                        runAction(ctx, type, param, data, rule)
                    i++
                }
            }
        }
        return SIG_OK
    }

    private fun findLoopEnd(arr: org.json.JSONArray, start: Int, limit: Int): Int {
        var d = 1
        var j = start + 1
        while (j < limit) {
            val t = arr.optJSONObject(j)?.optString("type") ?: ""
            if (t == "loop_start") d++
            else if (t == "loop_end") { d--; if (d == 0) return j }
            j++
        }
        return -1
    }

    /** Returns (elseIndex, endIfIndex) for the if block opened at ifIdx. */
    private fun findElseEnd(arr: org.json.JSONArray, ifIdx: Int,
                            limit: Int): Pair<Int, Int> {
        var d = 0
        var elseIdx = -1
        var j = ifIdx + 1
        while (j < limit) {
            val t = arr.optJSONObject(j)?.optString("type") ?: ""
            when (t) {
                "if_var" -> d++
                "end_if" -> { if (d == 0) return Pair(elseIdx, j); d-- }
                "else" -> if (d == 0 && elseIdx < 0) elseIdx = j
            }
            j++
        }
        return Pair(elseIdx, -1)
    }

    private fun loopCount(ctx: Context, param: String): Int {
        val p = param.trim()
        val n = if (p.startsWith("var:"))
            getVar(ctx, p.substring(4).trim()).toIntOrNull() ?: 0
        else p.toIntOrNull() ?: 0
        return n.coerceIn(0, 200)
    }

    // ---- v2.9 constraint helpers ----
    private fun speedKmh(ctx: Context): Double {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager
            @Suppress("MissingPermission")
            val loc = try { lm.getLastKnownLocation(
                android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
                ?: try { lm.getLastKnownLocation(
                    android.location.LocationManager.NETWORK_PROVIDER) }
                    catch (e: Exception) { null }
            if (loc != null && loc.hasSpeed()) loc.speed * 3.6 else 0.0
        } catch (e: Exception) { 0.0 }
    }

    private fun calendarBusyNow(ctx: Context): Boolean {
        return try {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            val now = System.currentTimeMillis()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, now - 60_000)
            android.content.ContentUris.appendId(uri, now + 60_000)
            val cur = ctx.contentResolver.query(uri.build(),
                arrayOf(android.provider.CalendarContract.Instances.EVENT_ID),
                null, null, null)
            var busy = false
            cur?.use { if (it.moveToFirst()) busy = true }
            busy
        } catch (e: Exception) { false }
    }

    private fun appRecentlyUsed(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 15 * 60_000, now)
                .any { it.packageName == pkg && it.lastTimeUsed > now - 15 * 60_000 }
        } catch (e: Exception) { false }
    }

    private fun runAction(ctx: Context, type: String, param: String,
                          data: JSONObject, rule: JSONObject) {
        try {
            when (type) {
                "notify_peer" -> Relay.send(ctx, Relay.TYPE_INFO,
                    "🤖 " + currentRuleName(ctx), param)
                "local_notify" -> localNotify(ctx, param)
                "speak_local" -> speak(ctx, param)
                "speak_peer" -> Relay.sendCommand(ctx, "SPEAK", param)
                "command_peer" -> Relay.sendCommand(ctx, param.uppercase())
                "send_sms" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        val num = parts[0].trim()
                        val deliveredPi = try {
                            android.app.PendingIntent.getBroadcast(ctx,
                                (System.currentTimeMillis() % 100000).toInt(),
                                Intent("com.mthtamim.phonelink.SMS_DELIVERED")
                                    .putExtra("number", num),
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                    android.app.PendingIntent.FLAG_IMMUTABLE)
                        } catch (e: Exception) { null }
                        @Suppress("DEPRECATION")
                        android.telephony.SmsManager.getDefault()
                            .sendTextMessage(num, null,
                                parts[1].trim().take(600), null, deliveredPi)
                    }
                }
                "torch" -> {
                    val wantOn = param.trim().lowercase() != "off"
                    if (!FlashHelper.setTorch(ctx, wantOn))
                        log(ctx, "⚠ " + ruleNameHint + " (torch)")
                }
                "torch_on" -> if (!FlashHelper.setTorch(ctx, true))
                    log(ctx, "⚠ " + ruleNameHint + " (torch_on)")
                "torch_off" -> if (!FlashHelper.setTorch(ctx, false))
                    log(ctx, "⚠ " + ruleNameHint + " (torch_off)")
                "torch_toggle" -> if (!FlashHelper.toggle(ctx))
                    log(ctx, "⚠ " + ruleNameHint + " (torch_toggle)")
                "ringer" -> setRinger(ctx, param)
                "open_app" -> openApp(ctx, param)
                "http" -> httpCall(param)
                "write_file" -> writeFile(ctx, param)
                "set_var" -> {
                    val idx = param.indexOf('=')
                    if (idx > 0) setVar(ctx, param.substring(0, idx).trim(),
                        param.substring(idx + 1).trim())
                }
                "mode_set" -> Config.applyMode(ctx, param)
                "lost_on" -> LinkService.setLostMode(ctx, true)
                "lost_off" -> LinkService.setLostMode(ctx, false)
                "tap_text" -> A11yService.tapByText(param)
                // ---- v2.7 MacroDroid expansion actions ----
                "media_control" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val code = when (param.trim().lowercase()) {
                        "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, code, 0))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, code, 0))
                }
                "volume_set" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val parts = param.split(":")
                    val stream = when (parts.getOrNull(0)?.trim()?.lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "call" -> AudioManager.STREAM_VOICE_CALL
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val pct = (parts.lastOrNull()?.trim()?.toIntOrNull() ?: 50)
                        .coerceIn(0, 100)
                    val max = am.getStreamMaxVolume(stream)
                    am.setStreamVolume(stream, (max * pct / 100).coerceIn(0, max), 0)
                }
                "brightness_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        val pct = (param.toIntOrNull() ?: 50).coerceIn(0, 100)
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS,
                            (pct * 255 / 100).coerceIn(1, 255))
                    } else localNotify(ctx,
                        "উজ্জ্বলতা বদলাতে PhoneLink-কে 'সিস্টেম সেটিংস বদলানোর' অনুমতি দাও।")
                }
                "screen_timeout_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        val secs = (param.toIntOrNull() ?: 60).coerceIn(15, 3600)
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT, secs * 1000)
                    } else localNotify(ctx,
                        "স্ক্রিন টাইমআউট বদলাতে 'সিস্টেম সেটিংস বদলানোর' অনুমতি দাও।")
                }
                "auto_rotate_set" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        val cur = try {
                            android.provider.Settings.System.getInt(ctx.contentResolver,
                                android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                        } catch (e: Exception) { true }
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.ACCELEROMETER_ROTATION,
                            if (toggleTarget(param, cur)) 1 else 0)
                    } else localNotify(ctx,
                        "Auto-rotate বদলাতে PhoneLink-কে 'সিস্টেম সেটিংস বদলানোর' অনুমতি দাও।")
                }
                "bt_toggle" -> {
                    // v2.15.0: BluetoothAdapter.enable/disable is dead
                    // on Android 13+, but the shell can still do it.
                    // v2.15.1: a blank param flips the real state.
                    val cur = try { BtShare.adapter()?.isEnabled == true }
                        catch (e: Exception) { false }
                    val on = toggleTarget(param, cur)
                    if (!shOk("svc bluetooth " + (if (on) "enable" else "disable"))) {
                        try {
                            @Suppress("DEPRECATION")
                            val ok = if (on) BtShare.adapter()?.enable()
                                else BtShare.adapter()?.disable()
                            if (ok != true) localNotify(ctx,
                                "Bluetooth সরাসরি বদলানো যায়নি — নতুন Android-এ সীমাবদ্ধ (Shizuku থাকলে হয়ে যেত)।")
                        } catch (e: Exception) {
                            localNotify(ctx,
                                "Bluetooth সরাসরি বদলানো যায়নি — নতুন Android-এ সীমাবদ্ধ (Shizuku থাকলে হয়ে যেত)।")
                        }
                    }
                }
                "dnd_set" -> {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(when (param.trim().lowercase()) {
                            "priority" -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            "alarms" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
                            "none" -> android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                            else -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                        })
                    } else localNotify(ctx, "DND বদলাতে Do Not Disturb access দাও।")
                }
                "vibrate" -> {
                    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE)
                        as android.os.Vibrator
                    val parts = param.split(",").mapNotNull { it.trim().toLongOrNull() }
                    if (parts.size >= 2) v.vibrate(
                        android.os.VibrationEffect.createWaveform(parts.toLongArray(), -1))
                    else v.vibrate(android.os.VibrationEffect.createOneShot(
                        parts.firstOrNull() ?: 400,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                }
                "play_sound" -> {
                    val uri = if (param.isBlank())
                        android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_NOTIFICATION)
                        else Uri.parse(param)
                    val mp = android.media.MediaPlayer()
                    try {
                        mp.setDataSource(ctx, uri); mp.prepare(); mp.start()
                        mp.setOnCompletionListener { it.release() }
                    } catch (e: Exception) {
                        try { mp.release() } catch (_: Exception) { }
                    }
                }
                "record_audio" -> {
                    val secs = (param.toIntOrNull() ?: 30).coerceIn(5, 120)
                    if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val file = File(ctx.filesDir,
                            "auto_rec_${System.currentTimeMillis()}.m4a")
                        val rec = android.media.MediaRecorder()
                        try {
                            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                            rec.setOutputFile(file.absolutePath)
                            rec.prepare(); rec.start()
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed({
                                    try { rec.stop() } catch (_: Exception) { }
                                    try { rec.release() } catch (_: Exception) { }
                                    History.add(ctx, History.Item(Relay.TYPE_FILE,
                                        "🎙️ অটোমেশন রেকর্ডিং", file.absolutePath,
                                        "Automation", System.currentTimeMillis(),
                                        extra = file.absolutePath))
                                }, secs * 1000L)
                        } catch (e: Exception) {
                            try { rec.release() } catch (_: Exception) { }
                        }
                    }
                }
                "take_photo" -> {
                    SessionState.snapFront = param.equals("front", true)
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(
                            ctx, Intent(ctx, SnapService::class.java))
                    } catch (_: Exception) { }
                }
                "open_url" -> {
                    val url = if (param.startsWith("http")) param else "https://$param"
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "share_text" -> {
                    try {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, param)
                        ctx.startActivity(Intent.createChooser(send, null)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "kill_app" -> {
                    try {
                        (ctx.getSystemService(Context.ACTIVITY_SERVICE)
                            as android.app.ActivityManager)
                            .killBackgroundProcesses(param.trim())
                    } catch (_: Exception) { }
                }
                "answer_call" -> {
                    // v2.15.0: TelecomManager refuses non-default-dialer
                    // apps and the old catch swallowed it. The telecom
                    // shell command works through Shizuku.
                    var ok = shOk("cmd telecom accept-ringing-call")
                    if (!ok) {
                        ok = try {
                            (ctx.getSystemService(Context.TELECOM_SERVICE)
                                as android.telecom.TelecomManager).acceptRingingCall()
                            true
                        } catch (e: Exception) { false }
                    }
                    if (!ok) localNotify(ctx,
                        "কল ধরা যায়নি — PhoneLink ডিফল্ট ডায়ালার নয় (Shizuku থাকলে হয়ে যেত)।")
                }
                "end_call" -> {
                    var ok = shOk("cmd telecom end-call")
                    if (!ok) {
                        ok = try {
                            @Suppress("DEPRECATION")
                            (ctx.getSystemService(Context.TELECOM_SERVICE)
                                as android.telecom.TelecomManager).endCall()
                            true
                        } catch (e: Exception) { false }
                    }
                    if (!ok) localNotify(ctx,
                        "কল কাটা যায়নি — PhoneLink ডিফল্ট ডায়ালার নয় (Shizuku থাকলে হয়ে যেত)।")
                }
                "set_wallpaper" -> Thread {
                    try {
                        val bmp = if (param.startsWith("http")) {
                            val conn = URL(param).openConnection() as HttpURLConnection
                            conn.connectTimeout = 10000; conn.readTimeout = 10000
                            conn.inputStream.use {
                                android.graphics.BitmapFactory.decodeStream(it) }
                        } else android.graphics.BitmapFactory.decodeFile(param)
                        if (bmp != null)
                            android.app.WallpaperManager.getInstance(ctx).setBitmap(bmp)
                    } catch (_: Exception) { }
                }.start()
                "clear_notif" ->
                    if (param.isBlank() || param == "all") NotifListener.dismissAll()
                    else NotifListener.cancelPackage(param.trim())
                "global_key" -> A11yService.globalKey(param)
                "stopwatch_start" ->
                    prefs(ctx).edit().putLong("sw_${param.trim()}",
                        System.currentTimeMillis()).apply()
                "stopwatch_stop" ->
                    prefs(ctx).edit().remove("sw_${param.trim()}").apply()
                "rule_enable", "rule_disable", "rule_run" -> {
                    // v2.9: rule_run accepts "Name|key=value;key2=value2"
                    // and passes those pairs as the sub-rule's event data.
                    val namePart = param.substringBefore("|").trim()
                    val target = rules(ctx).firstOrNull {
                        it.optString("name").equals(namePart, true) }
                    if (target != null) {
                        when (type) {
                            "rule_enable" -> setEnabled(ctx, target.optString("id"), true)
                            "rule_disable" -> setEnabled(ctx, target.optString("id"), false)
                            else -> if (target.optString("id") != rule.optString("id") &&
                                constraintsPass(ctx, target)) {
                                val sub = JSONObject(data.toString())
                                param.substringAfter("|", "").split(";")
                                    .forEach { kv ->
                                        val eq = kv.indexOf('=')
                                        if (eq > 0) sub.put(kv.substring(0, eq).trim(),
                                            kv.substring(eq + 1).trim())
                                    }
                                ruleNameHint = target.optString("name")
                                runActions(ctx, target, sub, 0)
                            }
                        }
                    }
                }
                "inc_var" -> {
                    val parts = param.split(":")
                    val name = parts[0].trim()
                    val delta = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                    val cur = getVar(ctx, name).toIntOrNull() ?: 0
                    setVar(ctx, name, (cur + delta).toString())
                }
                "delete_file" -> {
                    try {
                        val f = File(param.trim())
                        val canon = f.canonicalPath
                        val allowed = listOfNotNull(ctx.filesDir,
                            ctx.getExternalFilesDir(null),
                            ctx.getExternalFilesDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS))
                        if (allowed.any { canon.startsWith(it.canonicalPath) }) f.delete()
                    } catch (_: Exception) { }
                }
                "lock_screen" -> AdminReceiver.lockNow(ctx)
                "wake_screen" -> {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE)
                        as android.os.PowerManager
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE, "phonelink:wake")
                    wl.acquire(10_000)
                }
                "send_intent" -> {
                    try { ctx.sendBroadcast(Intent(param.trim())) } catch (_: Exception) { }
                }
                // ---- v2.8 final round actions ----
                "gesture_tap" -> {
                    val parts = param.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size == 2) A11yService.tapAt(parts[0], parts[1])
                }
                "gesture_swipe" -> {
                    val parts = param.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size >= 4) A11yService.swipe(parts[0], parts[1],
                        parts[2], parts[3], (parts.getOrNull(4)?.toLong() ?: 300))
                }
                "read_screen" -> {
                    val txt = A11yService.readScreenText()
                    if (txt.isNotEmpty() && param.isNotBlank())
                        setVar(ctx, param.trim(), txt.take(2000))
                }
                "send_email" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) Thread {
                        val ok = Smtp.send(ctx, parts[0].trim(), parts[1].trim(), parts[2].trim())
                        if (!ok) localNotify(ctx,
                            "ইমেইল পাঠানো যায়নি — SMTP সেটিংস (সার্ভার ও রিলে) দেখো।")
                    }.start()
                }
                "calendar_event" -> {
                    val parts = param.split("|")
                    val title = parts.getOrNull(0)?.trim() ?: ""
                    val delay = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0
                    val dur = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 60
                    if (title.isNotEmpty()) insertCalendarEvent(ctx, title, delay, dur)
                }
                "set_alarm" -> {
                    val parts = param.split("|")
                    val hm = parts.getOrNull(0)?.trim()?.split(":")
                    val h = hm?.getOrNull(0)?.toIntOrNull()
                    val m = hm?.getOrNull(1)?.toIntOrNull()
                    if (h != null && m != null) {
                        try {
                            ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE,
                                    parts.getOrNull(1)?.trim() ?: "")
                                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    }
                }
                "set_timer" -> {
                    val secs = param.split("|").getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    if (secs > 0) {
                        try {
                            ctx.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE,
                                    param.split("|").getOrNull(1)?.trim() ?: "")
                                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    }
                }
                "file_copy", "file_move" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        val src = File(parts[0].trim()); val dst = File(parts[1].trim())
                        if (allowedPath(ctx, src) && allowedPath(ctx, dst) && src.exists()) {
                            try {
                                dst.parentFile?.mkdirs()
                                src.inputStream().use { input ->
                                    dst.outputStream().use { input.copyTo(it) } }
                                if (type == "file_move") src.delete()
                            } catch (_: Exception) { }
                        }
                    }
                }
                "file_zip" -> {
                    val parts = param.split("|")
                    if (parts.size == 2) {
                        val src = File(parts[0].trim()); val dst = File(parts[1].trim())
                        if (allowedPath(ctx, src) && allowedPath(ctx, dst) && src.exists()) {
                            Thread { zipPath(src, dst) }.start()
                        }
                    }
                }
                "dialog_choice" -> {
                    val parts = param.split("|")
                    if (parts.size >= 3) {
                        try {
                            val intent = Intent(ctx, PromptActivity::class.java)
                                .putExtra("var", parts[0].trim())
                                .putExtra("q", parts[1].trim())
                                .putStringArrayListExtra("opts",
                                    ArrayList(parts.drop(2).map { it.trim() }))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        } catch (_: Exception) { }
                    }
                }
                "keep_awake" -> setKeepAwake(ctx, param != "off")
                "brightness_auto" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        val cur = try {
                            android.provider.Settings.System.getInt(ctx.contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        } catch (e: Exception) { false }
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            if (toggleTarget(param, cur))
                                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            else android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    } else localNotify(ctx,
                        "Auto-উজ্জ্বলতা বদলাতে PhoneLink-কে 'সিস্টেম সেটিংস বদলানোর' অনুমতি দাও।")
                }
                "flash_pattern" -> Thread {
                    val pat = if (param.trim().lowercase() == "sos") "... --- ..." else param
                    for (ch in pat.take(200)) {
                        when (ch) {
                            '.' -> { FlashHelper.setTorch(ctx, true)
                                Thread.sleep(150); FlashHelper.setTorch(ctx, false)
                                Thread.sleep(120) }
                            '-' -> { FlashHelper.setTorch(ctx, true)
                                Thread.sleep(450); FlashHelper.setTorch(ctx, false)
                                Thread.sleep(120) }
                            ' ' -> Thread.sleep(300)
                            '/' -> Thread.sleep(700)
                        }
                    }
                    FlashHelper.setTorch(ctx, false)
                }.start()
                // ---- v2.10 Shizuku (root-class) actions ----
                "reboot_device" -> {
                    val cmd = when (param.trim().lowercase()) {
                        "off" -> "svc power shutdown"
                        "recovery" -> "svc power reboot recovery"
                        "bootloader" -> "svc power reboot bootloader"
                        else -> "svc power reboot"
                    }
                    shAction(ctx, cmd)
                }
                "airplane_toggle" -> {
                    val cur = try {
                        android.provider.Settings.Global.getInt(
                            ctx.contentResolver, "airplane_mode_on", 0) == 1
                    } catch (e: Exception) { false }
                    val on = toggleTarget(param, cur)
                    // v2.14: cmd connectivity is the way that still
                    // works on Android 11+; the old settings+broadcast
                    // trick is ignored by modern Android.
                    shAction(ctx, "cmd connectivity airplane-mode " +
                        (if (on) "enable" else "disable") +
                        "; settings put global airplane_mode_on " +
                        (if (on) "1" else "0"))
                }
                "mobile_data_toggle" -> {
                    val cur = try {
                        android.provider.Settings.Global.getInt(
                            ctx.contentResolver, "mobile_data", 0) == 1
                    } catch (e: Exception) { false }
                    shAction(ctx, if (toggleTarget(param, cur))
                        "svc data enable" else "svc data disable")
                }
                "wifi_toggle" -> {
                    val cur = try {
                        (ctx.getSystemService(Context.WIFI_SERVICE)
                            as android.net.wifi.WifiManager).isWifiEnabled
                    } catch (e: Exception) { false }
                    shAction(ctx, if (toggleTarget(param, cur))
                        "svc wifi enable" else "svc wifi disable")
                }
                "shell_screenshot" -> {
                    val path = param.trim().ifEmpty {
                        "/sdcard/Pictures/phonelink_" +
                            System.currentTimeMillis() + ".png" }
                    Thread {
                        if (!ShizukuHelper.granted()) {
                            localNotify(ctx, "Shizuku অনুমতি নেই।")
                            return@Thread
                        }
                        ShizukuHelper.run("screencap -p " + shq(path))
                        val err = ShizukuHelper.lastError
                        if (err != null) localNotify(ctx, "স্ক্রিনশট ব্যর্থ: " + err)
                        else localNotify(ctx, "স্ক্রিনশট: $path")
                    }.start()
                }
                "clear_app_data" -> {
                    val pkg = param.trim()
                    if (validPkg(pkg)) shAction(ctx, "pm clear " + shq(pkg))
                }
                "app_disable" -> {
                    val pkg = param.trim()
                    if (validPkg(pkg))
                        shAction(ctx, "pm disable-user --user 0 " + shq(pkg))
                }
                "app_enable" -> {
                    val pkg = param.trim()
                    if (validPkg(pkg)) shAction(ctx, "pm enable " + shq(pkg))
                }
                "force_stop" -> {
                    val pkg = param.trim()
                    if (validPkg(pkg)) shAction(ctx, "am force-stop " + shq(pkg))
                }
                "install_silent" -> {
                    val path = param.trim()
                    Thread {
                        if (!ShizukuHelper.granted()) {
                            localNotify(ctx, "Shizuku অনুমতি নেই।")
                            return@Thread
                        }
                        val out = ShizukuHelper.run("pm install -r " + shq(path))
                        localNotify(ctx, "ইনস্টল: " + out.take(200)
                            .ifEmpty { "কোনো ফলাফল নেই" })
                    }.start()
                }
                "perm_grant" -> {
                    val parts = param.split("|")
                    val pkg = parts.getOrNull(0)?.trim() ?: ""
                    val perm = parts.getOrNull(1)?.trim() ?: ""
                    if (validPkg(pkg) && validPkg(perm))
                        shAction(ctx, "pm grant " + shq(pkg) + " " + shq(perm))
                }
                "perm_revoke" -> {
                    val parts = param.split("|")
                    val pkg = parts.getOrNull(0)?.trim() ?: ""
                    val perm = parts.getOrNull(1)?.trim() ?: ""
                    if (validPkg(pkg) && validPkg(perm))
                        shAction(ctx, "pm revoke " + shq(pkg) + " " + shq(perm))
                }
                "appops_set" -> {
                    val parts = param.split("|")
                    val pkg = parts.getOrNull(0)?.trim() ?: ""
                    val op = parts.getOrNull(1)?.trim()?.uppercase() ?: ""
                    val mode = parts.getOrNull(2)?.trim()?.lowercase() ?: "allow"
                    if (validPkg(pkg) && op.matches(Regex("[A-Z0-9_]+")) &&
                        (mode == "allow" || mode == "ignore" || mode == "deny"))
                        shAction(ctx, "cmd appops set " + shq(pkg) + " " +
                            op + " " + mode)
                }
                "wm_density" -> {
                    val p = param.trim()
                    if (p == "reset") shAction(ctx, "wm density reset")
                    else p.toIntOrNull()?.let {
                        if (it in 120..600) shAction(ctx, "wm density $it") }
                }
                "wm_size" -> {
                    val p = param.trim()
                    if (p == "reset") shAction(ctx, "wm size reset")
                    else if (p.matches(Regex("\\d{3,4}x\\d{3,4}")))
                        shAction(ctx, "wm size $p")
                }
                "anim_scale" -> {
                    val v = param.trim().toFloatOrNull()
                    if (v != null && v in 0f..5f) shAction(ctx,
                        "settings put global window_animation_scale $v" +
                        "; settings put global transition_animation_scale $v" +
                        "; settings put global animator_duration_scale $v")
                }
                "stay_on" -> {
                    val p = param.trim().lowercase()
                    val v = when (p) {
                        "off" -> "false"; "usb" -> "usb"; "ac" -> "ac"
                        else -> "true"
                    }
                    shAction(ctx, "svc power stayon $v")
                }
                "set_time" -> {
                    val parts = param.trim().split(":")
                    val h = parts.getOrNull(0)?.toIntOrNull() ?: -1
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    if (h in 0..23 && m in 0..59) {
                        val cal = java.util.Calendar.getInstance()
                        cal.set(java.util.Calendar.HOUR_OF_DAY, h)
                        cal.set(java.util.Calendar.MINUTE, m)
                        cal.set(java.util.Calendar.SECOND, 0)
                        shAction(ctx, "settings put global auto_time 0" +
                            "; service call alarm 3 i64 " + cal.timeInMillis)
                    }
                }
                "set_locale" -> {
                    val loc = param.trim()
                    if (loc.matches(Regex("[A-Za-z-]+"))) {
                        shAction(ctx, "setprop persist.sys.locale " + shq(loc))
                        localNotify(ctx, "ভাষা সেট হয়েছে — কার্যকর হতে রিবুট লাগতে পারে।")
                    }
                }
                "shell_tap" -> {
                    val parts = param.split(",")
                    val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (x != null && y != null)
                        shAction(ctx, "input tap $x $y")
                }
                "shell_swipe" -> {
                    val n = param.split(",").map { it.trim().toIntOrNull() }
                    if (n.size >= 4 && n.all { it != null })
                        shAction(ctx, "input swipe ${n[0]} ${n[1]} ${n[2]} " +
                            "${n[3]} ${n.getOrNull(4) ?: 300}")
                }
                "shell_input" ->
                    shAction(ctx, "input text " + shq(param.replace(" ", "%s")))
                "shell_key" -> {
                    val code = param.trim().toIntOrNull() ?: when (
                        param.trim().lowercase()) {
                        "enter" -> 66; "back" -> 4; "home" -> 3
                        "del" -> 67; "tab" -> 61; "space" -> 62
                        else -> -1
                    }
                    if (code > 0) shAction(ctx, "input keyevent $code")
                }
                "shell_to_var" -> {
                    val idx = param.lastIndexOf('|')
                    if (idx > 0) {
                        val cmd = param.substring(0, idx)
                        val name = param.substring(idx + 1).trim()
                        Thread {
                            if (!ShizukuHelper.granted()) {
                                localNotify(ctx, "Shizuku অনুমতি নেই।")
                                return@Thread
                            }
                            val out = ShizukuHelper.run(cmd)
                            if (name.isNotEmpty())
                                setVar(ctx, name, out.take(4000))
                        }.start()
                    }
                }
                // ---- v2.9 actions ----
                "call_number" -> {
                    try {
                        val has = ctx.checkSelfPermission(
                            android.Manifest.permission.CALL_PHONE) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ctx.startActivity(Intent(
                            if (has) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                            android.net.Uri.parse("tel:" + param.trim()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "ussd_run" -> {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL,
                            android.net.Uri.parse("tel:" +
                                android.net.Uri.encode(param.trim())))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "whatsapp_msg" -> {
                    val parts = param.split("|", limit = 2)
                    val num = (parts.getOrNull(0) ?: "")
                        .filter { it.isDigit() || it == '+' }.removePrefix("+")
                    val text = parts.getOrNull(1)?.trim() ?: ""
                    try {
                        val url = "https://wa.me/" + num + if (text.isNotEmpty())
                            "?text=" + android.net.Uri.encode(text) else ""
                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "telegram_msg" -> {
                    val user = param.substringBefore("|").trim().removePrefix("@")
                    if (user.isNotEmpty()) try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/$user"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                "notif_reply" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2)
                        NotifListener.replyByPackage(ctx, parts[0].trim(), parts[1].trim())
                }
                "rich_notify" -> richNotify(ctx, param)
                "type_text" -> A11yService.typeText(param)
                "font_scale" -> {
                    if (android.provider.Settings.System.canWrite(ctx)) {
                        val v = (param.toFloatOrNull() ?: 1.0f).coerceIn(0.7f, 1.5f)
                        android.provider.Settings.System.putFloat(ctx.contentResolver,
                            android.provider.Settings.System.FONT_SCALE, v)
                    } else localNotify(ctx,
                        "ফন্ট বদলাতে PhoneLink-কে 'সিস্টেম সেটিংস বদলানোর' অনুমতি দাও।")
                }
                "night_light" -> {
                    // v2.15.0: the Secure write needs WRITE_SECURE_
                    // SETTINGS; the Shizuku shell has it.
                    // v2.15.1: a blank param flips the stored state.
                    val cur = try {
                        android.provider.Settings.Secure.getInt(
                            ctx.contentResolver, "night_display_activated", 0) == 1
                    } catch (e: Exception) { false }
                    val on = toggleTarget(param, cur)
                    var ok = shOk("settings put secure night_display_activated " +
                        (if (on) "1" else "0"))
                    if (!ok) {
                        try { ok = android.provider.Settings.Secure.putInt(
                            ctx.contentResolver, "night_display_activated",
                            if (on) 1 else 0) } catch (_: Exception) { }
                    }
                    if (!ok) localNotify(ctx,
                        "নাইট লাইট এই ডিভাইসে সরাসরি বদলানো যায়নি (Shizuku থাকলে হয়ে যেত)।")
                }
                "torch_level" -> FlashHelper.setTorchLevel(ctx,
                    (param.toIntOrNull() ?: 3).coerceIn(0, 5))
                "ftp_upload" -> Thread {
                    if (!Ftp.transfer(ctx, param, true))
                        localNotify(ctx, "FTP আপলোড ব্যর্থ হয়েছে।")
                }.start()
                "ftp_download" -> Thread {
                    if (!Ftp.transfer(ctx, param, false))
                        localNotify(ctx, "FTP ডাউনলোড ব্যর্থ হয়েছে।")
                }.start()
                "image_resize" -> Thread { imageResize(ctx, param) }.start()
                "file_encrypt" -> fileCrypt(param, true)
                "file_decrypt" -> fileCrypt(param, false)
                "json_get" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        val raw = parts[1].trim()
                        val src = if (raw.startsWith("{") || raw.startsWith("["))
                            raw else getVar(ctx, raw)
                        jsonPath(src, parts[2].trim())
                            ?.let { setVar(ctx, parts[0].trim(), it) }
                    }
                }
                "http_to_var" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) Thread {
                        try {
                            val conn = java.net.URL(parts[0].trim())
                                .openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 8000; conn.readTimeout = 8000
                            val text = conn.inputStream.bufferedReader()
                                .readText().take(4000)
                            conn.disconnect()
                            setVar(ctx, parts[1].trim(), text)
                        } catch (_: Exception) { }
                    }.start()
                }
                "regex_op" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) {
                        val name = parts[0].trim()
                        val cur = getVar(ctx, name)
                        try {
                            val rx = Regex(parts[1])
                            if (parts[2] == "@match") {
                                val m = rx.find(cur)
                                setVar(ctx, name, m?.groupValues?.getOrNull(1)
                                    ?: m?.value ?: "")
                            } else setVar(ctx, name, rx.replace(cur, parts[2]))
                        } catch (_: Exception) { }
                    }
                }
                "date_calc" -> {
                    val parts = param.split("|", limit = 2)
                    if (parts.size == 2) {
                        val cal = java.util.Calendar.getInstance()
                        val m = Regex("^([+-])(\\d+)([mhd])$")
                            .find(parts[1].trim())
                        if (m != null) {
                            val amt = m.groupValues[2].toInt() *
                                if (m.groupValues[1] == "-") -1 else 1
                            when (m.groupValues[3]) {
                                "m" -> cal.add(java.util.Calendar.MINUTE, amt)
                                "h" -> cal.add(java.util.Calendar.HOUR_OF_DAY, amt)
                                else -> cal.add(java.util.Calendar.DAY_OF_YEAR, amt)
                            }
                        }
                        setVar(ctx, parts[0].trim(),
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                                java.util.Locale.US).format(cal.time))
                    }
                }
                "shell_cmd" -> Thread {
                    val out = ShizukuHelper.run(param)
                    val err = ShizukuHelper.lastError
                    localNotify(ctx, when {
                        err != null -> "Shell ব্যর্থ: " + err
                        out.isBlank() -> "Shell: কোনো আউটপুট নেই"
                        else -> out.take(300)
                    })
                }.start()
                "screen_pin" -> try {
                    ctx.startActivity(Intent(ctx, PinActivity::class.java)
                        .putExtra("pin", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                "unpin" -> try {
                    ctx.startActivity(Intent(ctx, PinActivity::class.java)
                        .putExtra("pin", false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                "private_dns" -> {
                    // v2.15.0: same secure-settings wall; route through
                    // the Shizuku shell when it is available.
                    val mode = param.trim().lowercase()
                    var ok = when {
                        mode == "off" ->
                            shOk("settings put global private_dns_mode off")
                        mode == "auto" || mode == "opportunistic" ->
                            shOk("settings put global private_dns_mode opportunistic")
                        mode.isNotEmpty() ->
                            shOk("settings put global private_dns_mode hostname") &&
                            shOk("settings put global private_dns_specifier " + mode)
                        else -> false
                    }
                    if (!ok) {
                        try {
                            ok = when {
                                mode == "off" -> android.provider.Settings.Global
                                    .putString(ctx.contentResolver, "private_dns_mode", "off")
                                mode == "auto" || mode == "opportunistic" ->
                                    android.provider.Settings.Global.putString(
                                        ctx.contentResolver, "private_dns_mode", "opportunistic")
                                mode.isNotEmpty() -> {
                                    val a = android.provider.Settings.Global.putString(
                                        ctx.contentResolver, "private_dns_mode", "hostname")
                                    if (a) android.provider.Settings.Global.putString(
                                        ctx.contentResolver, "private_dns_specifier", mode)
                                    else false
                                }
                                else -> false
                            }
                        } catch (_: Exception) { }
                    }
                    if (!ok) localNotify(ctx,
                        "Private DNS বদলাতে WRITE_SECURE_SETTINGS অনুমতি লাগবে (adb দিয়ে একবার দিলেই হবে)।")
                }
                "install_app" -> {
                    try {
                        val f = File(param.trim())
                        if (f.exists() && allowedPath(ctx, f)) {
                            val uri = androidx.core.content.FileProvider
                                .getUriForFile(ctx, ctx.packageName + ".files", f)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri,
                                    "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }
                    } catch (_: Exception) { }
                }
                "uninstall_app" -> try {
                    ctx.startActivity(Intent(Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + param.trim()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                "aod_toggle" -> {
                    // v2.15.0: the old code wrote a non-standard key
                    // ("aod_mode") without the secure-settings permission,
                    // so it could never work. The AOSP key is
                    // doze_always_on, written through the Shizuku shell.
                    // v2.15.1: a blank param flips the stored state.
                    val cur = try {
                        android.provider.Settings.Secure.getInt(
                            ctx.contentResolver, "doze_always_on", 0) == 1
                    } catch (e: Exception) { false }
                    val ok = shOk("settings put secure doze_always_on " +
                        (if (toggleTarget(param, cur)) "1" else "0"))
                    if (!ok) localNotify(ctx,
                        "Always-on display এই ডিভাইসে সরাসরি বদলানো যায়নি (Shizuku থাকলে হয়ে যেত)।")
                }
                "sqlite_query" -> {
                    val parts = param.split("|", limit = 3)
                    if (parts.size == 3) try {
                        val f = File(parts[0].trim())
                        if (f.exists() && allowedPath(ctx, f)) {
                            val db = android.database.sqlite.SQLiteDatabase
                                .openDatabase(f.absolutePath, null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                            db.use { d ->
                                d.rawQuery(parts[1], null)?.use { cur ->
                                    if (cur.moveToFirst() && cur.columnCount > 0)
                                        setVar(ctx, parts[2].trim(),
                                            cur.getString(0) ?: "")
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
                "toast_local" -> android.os.Handler(android.os.Looper.getMainLooper())
                    .post {
                        android.widget.Toast.makeText(ctx, param,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
            }
        } catch (e: Exception) { /* keep the chain alive */ }
    }

    // ---- v2.10 Shizuku helpers ----
    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    private fun validPkg(v: String): Boolean =
        v.matches(Regex("[A-Za-z0-9_.]+"))

    /** Fire-and-forget Shizuku command; warns locally when not granted. */
    private fun shAction(ctx: Context, cmd: String) {
        Thread {
            if (!ShizukuHelper.granted()) {
                localNotify(ctx, "Shizuku চলছে না বা অনুমতি নেই — " +
                    "Relay প্যানেলের Shizuku বোতাম থেকে ঠিক করো।")
                return@Thread
            }
            ShizukuHelper.run(cmd)
            ShizukuHelper.lastError?.let {
                localNotify(ctx, "Shell ব্যর্থ: " + it)
            }
        }.start()
    }

    /** v2.9: notification with up to two buttons; a tap fires the
     *  "notify_button" automation event with the label as its title. */
    private fun richNotify(ctx: Context, param: String) {
        val parts = param.split("|")
        val title = (parts.getOrNull(0)?.trim() ?: "").ifEmpty { "PhoneLink" }
        val body = parts.getOrNull(1)?.trim() ?: ""
        val nid = (System.currentTimeMillis() % 100000).toInt() + 7000
        val open = android.app.PendingIntent.getActivity(ctx, nid,
            Intent(ctx, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)
        val b = androidx.core.app.NotificationCompat.Builder(ctx, LinkService.CHANNEL_INBOX)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true)
        for (idx in 2..3) {
            val label = parts.getOrNull(idx)?.trim() ?: ""
            if (label.isEmpty()) continue
            val pi = android.app.PendingIntent.getBroadcast(ctx, nid * 10 + idx,
                Intent(ctx, NotifyButtonReceiver::class.java)
                    .putExtra("label", label).putExtra("nid", nid),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, label, pi)
        }
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager).notify(nid, b.build())
        } catch (_: Exception) { }
    }

    /** v2.9: walks "a.b.0.c" through JSON text; returns leaf as string. */
    private fun jsonPath(src: String, path: String): String? {
        return try {
            var cur: Any = if (src.trimStart().startsWith("["))
                org.json.JSONArray(src) else JSONObject(src)
            for (seg in path.split(".")) {
                cur = when (cur) {
                    is JSONObject -> (cur as JSONObject).get(seg)
                    is org.json.JSONArray -> (cur as org.json.JSONArray)
                        .get(seg.toInt())
                    else -> return null
                }
            }
            if (cur is JSONObject || cur is org.json.JSONArray) cur.toString()
            else cur.toString()
        } catch (e: Exception) { null }
    }

    /** v2.9: param "src|dst|widthPx" — scales an image, keeps format by ext. */
    private fun imageResize(ctx: Context, param: String) {
        try {
            val parts = param.split("|")
            if (parts.size < 3) return
            val src = File(parts[0].trim()); val dst = File(parts[1].trim())
            val w = parts[2].trim().toIntOrNull() ?: return
            if (!src.exists() || !allowedPath(ctx, src) || !allowedPath(ctx, dst)) return
            val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return
            val scale = w.toFloat() / bmp.width
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val out = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            dst.parentFile?.mkdirs()
            val fmt = if (dst.name.lowercase().endsWith(".png"))
                android.graphics.Bitmap.CompressFormat.PNG
                else android.graphics.Bitmap.CompressFormat.JPEG
            dst.outputStream().use { out.compress(fmt, 90, it) }
        } catch (_: Exception) { }
    }

    /** v2.9: param "src|dst|password" — file bytes ride the BackupCrypto
     *  envelope (Base64 inside), so any file type round-trips. */
    private fun fileCrypt(param: String, encrypt: Boolean) {
        try {
            val parts = param.split("|", limit = 3)
            if (parts.size < 3) return
            val src = File(parts[0].trim()); val dst = File(parts[1].trim())
            val pass = parts[2]
            if (!src.exists()) return
            dst.parentFile?.mkdirs()
            if (encrypt) {
                val b64 = android.util.Base64.encodeToString(
                    src.readBytes(), android.util.Base64.NO_WRAP)
                dst.writeText(BackupCrypto.encrypt(b64, pass))
            } else {
                val plain = BackupCrypto.decrypt(src.readText(), pass) ?: return
                dst.writeBytes(android.util.Base64.decode(plain,
                    android.util.Base64.NO_WRAP))
            }
        } catch (_: Exception) { }
    }

    private var ruleNameHint: String = ""
    private fun currentRuleName(ctx: Context): String = ruleNameHint

    private fun fill(template: String, data: JSONObject): String {
        var out = template
        for (key in listOf("title", "body", "app", "from", "battery", "kind")) {
            out = out.replace("{$key}", data.optString(key))
        }
        return out
    }

    private fun scheduleWait(ctx: Context, rule: JSONObject, nextIdx: Int, resumeAt: Long, data: JSONObject) {
        val arr = try { JSONArray(prefs(ctx).getString("waits", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("rule", rule).put("idx", nextIdx)
            .put("at", resumeAt).put("data", data))
        prefs(ctx).edit().putString("waits", arr.toString()).apply()
    }

    // ---------------- v2.9 polled edges ----------------
    /** Wireless-charging, keyguard-lock and local-IP edges, plus the
     *  periodic Bluetooth discovery the bt_near/bt_far triggers need. */
    private fun pollEdges(ctx: Context) {
        try {
            val wp = prefs(ctx)
            val bi = ctx.registerReceiver(null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = bi?.getIntExtra(
                android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val wireless = plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
            // v2.15.1: charging on/off as a heartbeat edge too — the
            // manifest receiver and the service's dynamic receiver are
            // the live paths, this is the third, independent net for
            // the one trigger family Tamim reports still not firing.
            val chargingNow = plugged != 0
            if (wp.contains("edge_charging")) {
                val prevC = wp.getBoolean("edge_charging", false)
                if (chargingNow && !prevC) onEvent(ctx, "charging_on", JSONObject())
                if (!chargingNow && prevC) onEvent(ctx, "charging_off", JSONObject())
                if (chargingNow != prevC)
                    wp.edit().putBoolean("edge_charging", chargingNow).apply()
            } else wp.edit().putBoolean("edge_charging", chargingNow).apply()
            if (wp.contains("edge_wireless")) {
                val prev = wp.getBoolean("edge_wireless", false)
                if (wireless && !prev) onEvent(ctx, "wireless_charging", JSONObject())
                if (wireless != prev)
                    wp.edit().putBoolean("edge_wireless", wireless).apply()
            } else wp.edit().putBoolean("edge_wireless", wireless).apply()
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE)
                as android.app.KeyguardManager
            val locked = km.isKeyguardLocked
            if (wp.contains("edge_locked")) {
                val prev = wp.getBoolean("edge_locked", false)
                if (locked && !prev) onEvent(ctx, "screen_locked", JSONObject())
                if (locked != prev) wp.edit().putBoolean("edge_locked", locked).apply()
            } else wp.edit().putBoolean("edge_locked", locked).apply()
            stateEdge(ctx, "poll_mdata", try {
                android.provider.Settings.Global.getInt(
                    ctx.contentResolver, "mobile_data", 0) == 1
            } catch (e: Exception) { false }, "mobile_data_on", "mobile_data_off")
            val ip = localIp()
            val prevIp = wp.getString("edge_ip", "") ?: ""
            if (prevIp.isNotEmpty() && ip.isNotEmpty() && ip != prevIp)
                onEvent(ctx, "ip_changed", JSONObject()
                    .put("title", ip).put("body", prevIp))
            if (ip.isNotEmpty() && ip != prevIp)
                wp.edit().putString("edge_ip", ip).apply()
            val needsBt = rules(ctx).any { r -> r.optBoolean("enabled", true) &&
                ruleTriggers(r).any { it.optString("type") == "bt_near" ||
                    it.optString("type") == "bt_far" } }
            if (needsBt) {
                @Suppress("MissingPermission")
                try { BtShare.adapter()?.takeIf { !it.isDiscovering }
                    ?.startDiscovery() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun localIp(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address)
                        return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) { }
        return ""
    }

    // ---------------- tick: time & polled triggers ----------------

    // ---- v2.11.3: exact alarms for time_at rules ----
    // The heartbeat tick can be ~25 min late; time rules deserve the
    // system alarm clock instead. The tick path stays as a safety net
    // and both paths share the same once-a-day marking.
    fun scheduleTimeRules(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE)
                as android.app.AlarmManager
            val wanted = mutableSetOf<String>()
            for (rule in rules(ctx).filter { it.optBoolean("enabled", true) }) {
                val times = ruleTriggers(rule)
                    .filter { it.optString("type") == "time_at" }
                    .map { parseHm(it.optString("param")) }
                if (times.isEmpty()) continue
                val id = rule.optString("id")
                if (id.isEmpty()) continue
                wanted.add(id)
                val next = nextTimeMillis(times)
                val pi = timeRulePi(ctx, id)
                val exactOk = android.os.Build.VERSION.SDK_INT < 31 ||
                    am.canScheduleExactAlarms()
                try {
                    if (exactOk) am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, next, pi)
                    else am.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, next, pi)
                } catch (e: Exception) {
                    try { am.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, next, pi) }
                    catch (_: Exception) { }
                }
            }
            val prev = prefs(ctx).getStringSet("time_alarm_ids", emptySet())
                ?: emptySet()
            for (old in prev - wanted) {
                try { am.cancel(timeRulePi(ctx, old)) } catch (_: Exception) { }
            }
            prefs(ctx).edit().putStringSet("time_alarm_ids", wanted).apply()
        } catch (_: Exception) { }
    }

    private fun timeRulePi(ctx: Context, id: String): android.app.PendingIntent {
        val intent = Intent(ctx, TimeRuleReceiver::class.java)
            .putExtra("ruleId", id)
        return android.app.PendingIntent.getBroadcast(ctx, id.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    private fun nextTimeMillis(timesMin: List<Int>): Long {
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        val sorted = timesMin.sorted()
        val target = sorted.firstOrNull { it > nowMin } ?: sorted.first()
        val at = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, target / 60)
            set(java.util.Calendar.MINUTE, target % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (target <= nowMin) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return at.timeInMillis
    }

    /** Called by TimeRuleReceiver when a time rule's alarm rings. */
    fun fireTimeRule(ctx: Context, id: String) {
        try {
            if (!Config.automationOn(ctx)) return
            val rule = rules(ctx).firstOrNull { it.optString("id") == id }
                ?: return
            if (!rule.optBoolean("enabled", true)) return
            // v2.14.1: the once-a-day latch is per rule AND per time —
            // a rule set for 08:00 and 22:00 must be able to fire at both.
            val dueParams = ruleTriggers(rule).filter { trig ->
                trig.optString("type") == "time_at" &&
                    minutesNow() >= parseHm(trig.optString("param")) &&
                    minutesNow() - parseHm(trig.optString("param")) < 65 &&
                    prefs(ctx).getString(
                        "day_${id}_${trig.optString("param")}", "") != dayKey()
            }
            if (dueParams.isNotEmpty() && constraintsPass(ctx, rule)) {
                val ed = prefs(ctx).edit()
                for (trig in dueParams)
                    ed.putString("day_${id}_${trig.optString("param")}", dayKey())
                ed.apply()
                markFired(ctx, id, System.currentTimeMillis())
                ruleNameHint = rule.optString("name")
                log(ctx, "▶ " + rule.optString("name") + " (time_at)")
                runActions(ctx, rule, JSONObject(), 0)
            }
        } catch (_: Exception) { }
        scheduleTimeRules(ctx)
    }

    fun tick(ctx: Context) {
        if (!Config.automationOn(ctx)) return
        Thread {
            try {
                resumeWaits(ctx)
                val now = System.currentTimeMillis()
                val list = rules(ctx).filter { it.optBoolean("enabled", true) }
                for (rule in list) {
                    for (trig in ruleTriggers(rule)) {
                    val type = trig.optString("type")
                    val param = trig.optString("param")
                    val should = when (type) {
                        "time_at" -> {
                            val today = dayKey()
                            val last = prefs(ctx).getString("day_${rule.optString("id")}_$param", "")
                            minutesNow() >= parseHm(param) && last != today &&
                                minutesNow() - parseHm(param) < 65
                        }
                        "time_interval" -> {
                            val mins = param.toLongOrNull() ?: 0
                            mins > 0 && now - prefs(ctx).getLong("fired_${rule.optString("id")}", 0) >= mins * 60_000
                        }
                        "sunrise", "sunset" -> sunTriggerDue(ctx, type, rule.optString("id"))
                        "battery_below", "battery_above" -> batteryCrossing(ctx, rule, type, param)
                        "steps_goal" -> stepsDue(ctx, rule, param)
                        "peer_offline" -> {
                            val seen = Config.lastSenderSeen(ctx)
                            val mins = param.toLongOrNull() ?: 90
                            seen > 0 && now - seen > mins * 60_000 &&
                                now - prefs(ctx).getLong("fired_${rule.optString("id")}", 0) > mins * 60_000
                        }
                        "weather" -> weatherDue(ctx, param)
                        "battery_full" -> batteryCrossing(ctx, rule, "battery_above", "100")
                        "battery_temp_above" -> tempCrossing(ctx, rule, param)
                        "date_at" -> dateDue(ctx, rule, param)
                        "stopwatch" -> stopwatchDue(ctx, rule, param)
                        "peer_battery_below" -> peerBatteryCrossing(ctx, rule, param)
                        "weather_forecast" -> forecastDue(ctx, rule, param)
                        else -> false
                    }
                    if (should && constraintsPass(ctx, rule)) {
                        if (type == "time_at") {
                            prefs(ctx).edit().putString(
                                "day_${rule.optString("id")}_$param", dayKey()).apply()
                        } else if (type == "sunrise" || type == "sunset" ||
                            type == "weather_forecast") {
                            prefs(ctx).edit().putString("day_${rule.optString("id")}", dayKey()).apply()
                        }
                        markFired(ctx, rule.optString("id"), now)
                        ruleNameHint = rule.optString("name")
                        log(ctx, "▶ " + rule.optString("name") + " (" + type + ")")
                        runActions(ctx, rule, JSONObject(), 0)
                        break
                    }
                    }
                }
                pollEdges(ctx)
                pollWifi(ctx)
                pollAppUsage(ctx, list)
                pollStates(ctx)
                pollCalendar(ctx)
            } catch (e: Exception) { }
        }.start()
    }

    /** Fires wifi_connected / wifi_disconnected when the SSID changes. */
    private fun pollWifi(ctx: Context) {
        try {
            val ssid = currentSsid(ctx)
            val prev = prefs(ctx).getString("last_ssid", "") ?: ""
            if (ssid != prev) {
                prefs(ctx).edit().putString("last_ssid", ssid).apply()
                if (prev.isNotEmpty() && ssid.isEmpty()) {
                    onEvent(ctx, "wifi_disconnected", JSONObject())
                } else if (ssid.isNotEmpty()) {
                    onEvent(ctx, "wifi_connected",
                        JSONObject().put("ssid", ssid).put("title", ssid))
                }
            }
        } catch (e: Exception) { }
    }

    private fun resumeWaits(ctx: Context) {
        val raw = prefs(ctx).getString("waits", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { return }
        if (arr.length() == 0) return
        val keep = JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optLong("at") <= now) {
                val rule = w.optJSONObject("rule") ?: continue
                val data = w.optJSONObject("data") ?: JSONObject()
                ruleNameHint = rule.optString("name")
                runActions(ctx, rule, data, w.optInt("idx", 0))
            } else keep.put(w)
        }
        prefs(ctx).edit().putString("waits", keep.toString()).apply()
    }

    private fun batteryCrossing(ctx: Context, rule: JSONObject, type: String, param: String): Boolean {
        val level = batteryPct(ctx)
        val target = param.toIntOrNull() ?: return false
        val key = "arm_${rule.optString("id")}"
        val armed = prefs(ctx).getBoolean(key, true)
        val hit = if (type == "battery_below") level <= target else level >= target
        if (hit && armed) {
            prefs(ctx).edit().putBoolean(key, false).apply()
            return true
        }
        if (!hit) prefs(ctx).edit().putBoolean(key, true).apply()
        return false
    }

    private fun stepsDue(ctx: Context, rule: JSONObject, param: String): Boolean {
        val goal = param.toIntOrNull() ?: return false
        val today = dayKey()
        val key = "steps_day"
        if (prefs(ctx).getString(key, "") != today) {
            prefs(ctx).edit().putString(key, today).putInt("steps_base", -1).apply()
        }
        // Sensor value is maintained by LinkService's step listener via state prefs.
        val steps = ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            .getInt("steps_today", 0)
        return steps >= goal
    }

    private fun weatherDue(ctx: Context, param: String): Boolean {
        val cacheAt = prefs(ctx).getLong("wx_at", 0)
        val now = System.currentTimeMillis()
        if (now - cacheAt > 30 * 60_000) {
            var done = false
            val latch = java.util.concurrent.CountDownLatch(1)
            LocationHelper.fetchCoords(ctx, { lat, lng ->
                Thread {
                    try {
                        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,weather_code")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        val text = conn.inputStream.bufferedReader().readText()
                        val cur = JSONObject(text).optJSONObject("current")
                        if (cur != null) {
                            prefs(ctx).edit()
                                .putInt("wx_code", cur.optInt("weather_code", -1))
                                .putFloat("wx_temp", cur.optDouble("temperature_2m", 0.0).toFloat())
                                .putLong("wx_at", System.currentTimeMillis()).apply()
                        }
                    } catch (e: Exception) { }
                    latch.countDown()
                }.start()
            }, { latch.countDown() })
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            done = true
            if (!done) return false
        }
        val code = prefs(ctx).getInt("wx_code", -1)
        if (code < 0) return false
        val temp = prefs(ctx).getFloat("wx_temp", 0f)
        return when (param.lowercase()) {
            "rain" -> code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
            "clear" -> code == 0 || code == 1
            "hot" -> temp >= 35f
            "cold" -> temp <= 10f
            else -> false
        }
    }

    private fun pollAppUsage(ctx: Context, list: List<JSONObject>) {
        val watched = list.filter {
            ruleTriggers(it).any { t ->
                t.optString("type") == "app_launched" ||
                    t.optString("type") == "app_closed" }
        }
        if (watched.isEmpty()) return
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 35 * 60_000, now) ?: return
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                val kind = when (ev.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> "app_launched"
                    UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> "app_closed"
                    else -> continue
                }
                fireAppEvent(ctx, kind, pkg)
            }
        } catch (e: Exception) { }
    }

    // ---------------- sunrise / sunset ----------------
    private fun sunTriggerDue(ctx: Context, type: String, ruleId: String): Boolean {
        val today = dayKey()
        if (prefs(ctx).getString("day_$ruleId", "") == today) return false
        var due = false
        val latch = java.util.concurrent.CountDownLatch(1)
        LocationHelper.fetchCoords(ctx, { lat, lng ->
            val mins = sunMinutes(lat, lng, type == "sunrise")
            if (mins != null) {
                val now = minutesNow()
                due = now >= mins && now - mins < 65
            }
            latch.countDown()
        }, { latch.countDown() })
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return due
    }

    /** NOAA approximation; returns local minutes-of-day or null (polar day/night). */
    private fun sunMinutes(lat: Double, lng: Double, sunrise: Boolean): Int? {
        val cal = Calendar.getInstance()
        val n = cal.get(Calendar.DAY_OF_YEAR)
        val lngHour = lng / 15.0
        val t = n + ((if (sunrise) 6 else 18) - lngHour) / 24.0
        val m = (0.9856 * t) - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360
        var ra = Math.toDegrees(kotlin.math.atan(0.91764 * tan(Math.toRadians(l))))
        ra = ((ra % 360) + 360) % 360
        ra += (floor(l / 90) * 90 - floor(ra / 90) * 90)
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(kotlin.math.asin(sinDec))
        val cosH = (cos(Math.toRadians(90.833)) -
            sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1 || cosH < -1) return null
        var h = if (sunrise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
        h /= 15.0
        val tt = h + ra - (0.06571 * t) - 6.622
        var ut = tt - lngHour
        ut = ((ut % 24) + 24) % 24
        val offsetMin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60000
        var local = floor(ut * 60).toInt() + offsetMin
        local = ((local % 1440) + 1440) % 1440
        return local
    }

    // ---------------- sensor watchers ----------------
    private var sensorMgr: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var lastFlipDown: Boolean? = null
    private var lastProxNear: Boolean? = null
    private var lastLightBand: Boolean? = null
    private var lastShakeAt = 0L
    private var lastOrient: String? = null
    private var lastStillAt = 0L
    private var lastPickUpAt = 0L

    @Synchronized
    // ---------------- v2.8: forecast / calendar / hotspot ----------------
    private fun forecastDue(ctx: Context, rule: JSONObject, param: String): Boolean {
        val cacheAt = prefs(ctx).getLong("wxf_at", 0)
        val now = System.currentTimeMillis()
        if (now - cacheAt > 60 * 60_000) {
            var done = false
            val latch = java.util.concurrent.CountDownLatch(1)
            LocationHelper.fetchCoords(ctx, { lat, lng ->
                Thread {
                    try {
                        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        val daily = JSONObject(conn.inputStream.bufferedReader().readText())
                            .optJSONObject("daily")
                        if (daily != null) {
                            prefs(ctx).edit()
                                .putInt("wxf_code", daily.optJSONArray("weather_code")?.optInt(0, -1) ?: -1)
                                .putFloat("wxf_max", daily.optJSONArray("temperature_2m_max")?.optDouble(0, 0.0)?.toFloat() ?: 0f)
                                .putFloat("wxf_min", daily.optJSONArray("temperature_2m_min")?.optDouble(0, 0.0)?.toFloat() ?: 0f)
                                .putLong("wxf_at", System.currentTimeMillis()).apply()
                        }
                    } catch (e: Exception) { }
                    latch.countDown()
                }.start()
            }, { latch.countDown() })
            try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
            done = true
            if (!done) return false
        }
        if (prefs(ctx).getLong("wxf_at", 0) == 0L) return false
        val code = prefs(ctx).getInt("wxf_code", -1)
        val tmax = prefs(ctx).getFloat("wxf_max", 0f)
        val tmin = prefs(ctx).getFloat("wxf_min", 0f)
        val p = param.trim().lowercase()
        return when {
            p == "rain" -> code in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
            p == "snow" -> code in setOf(71, 73, 75, 77, 85, 86)
            p == "clear" -> code in setOf(0, 1)
            p.startsWith("temp_above:") -> tmax >= (p.removePrefix("temp_above:").toFloatOrNull() ?: 999f)
            p.startsWith("temp_below:") -> tmin <= (p.removePrefix("temp_below:").toFloatOrNull() ?: -999f)
            else -> false
        }
    }

    private fun pollCalendar(ctx: Context) {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val hasRules = rules(ctx).any {
                it.optBoolean("enabled", true) && ruleTriggers(it).any { t ->
                    t.optString("type") == "calendar_start" ||
                        t.optString("type") == "calendar_end" }
            }
            if (!hasRules) return
            val now = System.currentTimeMillis()
            val fired = (prefs(ctx).getString("cal_fired", "") ?: "")
                .split(",").filter { it.isNotEmpty() }.toMutableSet()
            var changed = false
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, now - 15 * 60_000)
            android.content.ContentUris.appendId(uri, now + 16 * 60_000)
            val cur = ctx.contentResolver.query(uri.build(), arrayOf(
                android.provider.CalendarContract.Instances.EVENT_ID,
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.EVENT_LOCATION,
                android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
                null, null, null)
            cur?.use {
                while (it.moveToNext()) {
                    val eid = it.getLong(0)
                    val title = it.getString(1) ?: ""
                    val begin = it.getLong(2)
                    val end = it.getLong(3)
                    val loc = it.getString(4) ?: ""
                    val cal = it.getString(5) ?: ""
                    val data = JSONObject().put("title", title).put("body", loc).put("app", cal)
                    val startKey = "s_${eid}_$begin"
                    if (begin in (now - 90_000)..(now + 15 * 60_000) && startKey !in fired) {
                        fired.add(startKey); changed = true
                        onEvent(ctx, "calendar_start", data)
                    }
                    val endKey = "e_${eid}_$end"
                    if (end in (now - 90_000)..(now + 15 * 60_000) && endKey !in fired) {
                        fired.add(endKey); changed = true
                        onEvent(ctx, "calendar_end", data)
                    }
                }
            }
            if (changed) {
                val trimmed = fired.toList().takeLast(120).joinToString(",")
                prefs(ctx).edit().putString("cal_fired", trimmed).apply()
            }
        } catch (_: Exception) { }
    }

    // ---------------- v2.7: polled state triggers + crossings ----------------
    private fun batteryTempC(ctx: Context): Float {
        val bi = ctx.registerReceiver(null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tv = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tv > 0) tv / 10f else -1f
    }

    private fun tempCrossing(ctx: Context, rule: JSONObject, param: String): Boolean {
        val temp = batteryTempC(ctx); if (temp < 0) return false
        val target = param.toFloatOrNull() ?: return false
        val key = "arm_${rule.optString("id")}"
        val armed = prefs(ctx).getBoolean(key, true)
        val hit = temp >= target
        if (hit && armed) {
            prefs(ctx).edit().putBoolean(key, false).apply(); return true
        }
        if (!hit) prefs(ctx).edit().putBoolean(key, true).apply()
        return false
    }

    private fun dateDue(ctx: Context, rule: JSONObject, param: String): Boolean {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val target = try { fmt.parse(param.trim())?.time ?: return false }
            catch (e: Exception) { return false }
        val key = "date_${rule.optString("id")}"
        if (prefs(ctx).getString(key, "") == param) return false
        if (System.currentTimeMillis() >= target) {
            prefs(ctx).edit().putString(key, param).apply(); return true
        }
        return false
    }

    private fun stopwatchDue(ctx: Context, rule: JSONObject, param: String): Boolean {
        val parts = param.split(":")
        if (parts.size != 2) return false
        val name = parts[0].trim()
        val mins = parts[1].trim().toLongOrNull() ?: return false
        val start = prefs(ctx).getLong("sw_$name", 0)
        if (start == 0L) return false
        val key = "swf_${rule.optString("id")}"
        if (prefs(ctx).getLong(key, 0) == start) return false
        if (System.currentTimeMillis() - start >= mins * 60_000) {
            prefs(ctx).edit().putLong(key, start).apply(); return true
        }
        return false
    }

    private fun peerBatteryCrossing(ctx: Context, rule: JSONObject, param: String): Boolean {
        val pct = Peers.list(ctx).firstOrNull()?.battery ?: -1
        if (pct < 0) return false
        val target = param.toIntOrNull() ?: return false
        val key = "arm_${rule.optString("id")}"
        val armed = prefs(ctx).getBoolean(key, true)
        val hit = pct <= target
        if (hit && armed) {
            prefs(ctx).edit().putBoolean(key, false).apply(); return true
        }
        if (!hit) prefs(ctx).edit().putBoolean(key, true).apply()
        return false
    }

    fun musicActive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

    fun isVpnOn(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    fun isDarkMode(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun stateEdge(ctx: Context, key: String, now: Boolean,
                          onEv: String, offEv: String) {
        val p = prefs(ctx)
        if (!p.contains(key)) { p.edit().putBoolean(key, now).apply(); return }
        val prev = p.getBoolean(key, now)
        if (prev != now) {
            p.edit().putBoolean(key, now).apply()
            onEvent(ctx, if (now) onEv else offEv, JSONObject())
        }
    }

    private fun pollStates(ctx: Context) {
        try {
            stateEdge(ctx, "poll_music", musicActive(ctx), "music_started", "music_stopped")
            stateEdge(ctx, "poll_vpn", isVpnOn(ctx), "vpn_on", "vpn_off")
            stateEdge(ctx, "poll_dark", isDarkMode(ctx), "dark_on", "dark_off")
            val roam = try {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager).isNetworkRoaming
            } catch (e: Exception) { false }
            stateEdge(ctx, "poll_roam", roam, "roaming_on", "roaming_off")
            // Hotspot is a live broadcast edge since v2.15.0 (the
            // reflection poll it replaces never worked on new Android).
            pollSmsSent(ctx)
        } catch (_: Exception) { }
    }

    /** v2.15.0 backstop for the sms_sent trigger: the ContentObserver
     *  in LinkService is the live path, but on ROMs where it never
     *  calls back, the heartbeat still notices a new sent SMS. Shares
     *  the observer's last-id key, so the two paths never double-fire. */
    private fun pollSmsSent(ctx: Context) {
        try {
            val cur = ctx.contentResolver.query(
                android.provider.Telephony.Sms.Sent.CONTENT_URI,
                arrayOf("_id", "address", "body"), null, null, "_id DESC")
            cur?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val prefs = ctx.getSharedPreferences("phonelink_state",
                        Context.MODE_PRIVATE)
                    val last = prefs.getLong("sms_sent_last_id", -1)
                    if (last < 0) {
                        prefs.edit().putLong("sms_sent_last_id", id).apply()
                    } else if (id > last) {
                        prefs.edit().putLong("sms_sent_last_id", id).apply()
                        onEvent(ctx, "sms_sent", JSONObject()
                            .put("from", it.getString(1) ?: "")
                            .put("body", it.getString(2) ?: "")
                            .put("title", it.getString(1) ?: ""))
                    }
                }
            }
        } catch (_: Exception) { }
    }

    fun refreshWatchers(ctx: Context) {
        val app = ctx.applicationContext
        // Stop old listeners first.
        sensorListener?.let { l -> sensorMgr?.unregisterListener(l) }
        sensorListener = null
        if (!Config.automationOn(app)) return
        registerSysReceiver(app)
        registerSmsDelivered(app)
        val types = rules(app).filter { it.optBoolean("enabled", true) }
            .flatMap { r -> ruleTriggers(r).map { it.optString("type") } }.toSet()
        registerMediaObserver(app, types)
        registerOps(app, types)
        val needAccel = types.any { it == "shake" || it == "flip_down" || it == "flip_up" ||
            it == "orientation_changed" || it == "pick_up" }
        val needProx = types.any { it == "proximity_near" || it == "proximity_far" }
        val needLight = types.any { it == "light_below" || it == "light_above" }
        val needMagnet = types.any { it == "magnet_above" }
        val needPressure = types.any { it == "pressure_above" }
        if (!needAccel && !needProx && !needLight && !needMagnet && !needPressure) return
        val mgr = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorMgr = mgr
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                        val mag = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
                        val now = System.currentTimeMillis()
                        if (mag > 27 && now - lastShakeAt > 3000) {
                            lastShakeAt = now
                            onEvent(app, "shake", JSONObject())
                        }
                        val down = z < -8.5
                        val up = z > 8.5
                        if (down && lastFlipDown != true) {
                            lastFlipDown = true
                            onEvent(app, "flip_down", JSONObject())
                        } else if (up && lastFlipDown != false) {
                            if (lastFlipDown == true) onEvent(app, "flip_up", JSONObject())
                            lastFlipDown = false
                        }
                        // v2.9: orientation change + pick-up heuristic
                        val orient = when {
                            kotlin.math.abs(x) > kotlin.math.abs(y) + 2 &&
                                kotlin.math.abs(x) > 6 -> "landscape"
                            kotlin.math.abs(y) > kotlin.math.abs(x) + 2 &&
                                y > 6 -> "portrait"
                            else -> lastOrient
                        }
                        if (orient != null && orient != lastOrient) {
                            val first = lastOrient == null
                            lastOrient = orient
                            if (!first) onEvent(app, "orientation_changed",
                                JSONObject().put("kind", orient))
                        }
                        if (mag < 10.6) lastStillAt = now
                        if (mag > 16 && now - lastStillAt > 1500 &&
                            now - lastPickUpAt > 15_000) {
                            val pm = app.getSystemService(Context.POWER_SERVICE)
                                as android.os.PowerManager
                            if (!pm.isInteractive) {
                                lastPickUpAt = now
                                onEvent(app, "pick_up", JSONObject())
                            }
                        }
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        val near = e.values[0] < (e.sensor.maximumRange)
                        if (lastProxNear != near)
                            prefs(app).edit().putBoolean("last_prox_near", near).apply()
                        if (near && lastProxNear != true) {
                            lastProxNear = true
                            onEvent(app, "proximity_near", JSONObject())
                        } else if (!near && lastProxNear != false) {
                            if (lastProxNear == true) onEvent(app, "proximity_far", JSONObject())
                            lastProxNear = false
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val mag = kotlin.math.sqrt((e.values[0] * e.values[0] +
                            e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                        bandFire(app, "magnet_above", mag.toFloat(), "magband_")
                    }
                    Sensor.TYPE_PRESSURE -> {
                        bandFire(app, "pressure_above", e.values[0], "presband_")
                    }
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        val lastLux = prefs(app).getFloat("last_lux", -1f)
                        if (kotlin.math.abs(lastLux - lux) > 10)
                            prefs(app).edit().putFloat("last_lux", lux).apply()
                        for (rule in rules(app)) {
                            if (!rule.optBoolean("enabled", true)) continue
                            val trig = ruleTriggers(rule).firstOrNull {
                                val tt = it.optString("type")
                                tt == "light_below" || tt == "light_above" } ?: continue
                            val t = trig.optString("type")
                            val target = trig.optString("param").toFloatOrNull() ?: continue
                            val bandKey = "lightband_${rule.optString("id")}"
                            val wasIn = prefs(app).getBoolean(bandKey, false)
                            val inBand = if (t == "light_below") lux < target else lux > target
                            if (inBand && !wasIn) {
                                prefs(app).edit().putBoolean(bandKey, true).apply()
                                onEvent(app, t, JSONObject().put("body", lux.toInt().toString()))
                            } else if (!inBand && wasIn) {
                                prefs(app).edit().putBoolean(bandKey, false).apply()
                            }
                        }
                    }
                }
            }
        }
        sensorListener = listener
        if (needAccel) mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (needProx) mgr.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (needLight) mgr.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (needMagnet) mgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (needPressure) mgr.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            mgr.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /** Shared threshold-band firing for magnet/pressure triggers. */
    private fun bandFire(app: Context, type: String, value: Float, keyPrefix: String) {
        for (rule in rules(app)) {
            if (!rule.optBoolean("enabled", true)) continue
            val trig = ruleTriggers(rule).firstOrNull {
                it.optString("type") == type } ?: continue
            val target = trig.optString("param").toFloatOrNull() ?: continue
            val bandKey = keyPrefix + rule.optString("id")
            val wasIn = prefs(app).getBoolean(bandKey, false)
            val inBand = value > target
            if (inBand && !wasIn) {
                prefs(app).edit().putBoolean(bandKey, true).apply()
                onEvent(app, type, JSONObject().put("body", value.toInt().toString()))
            } else if (!inBand && wasIn) {
                prefs(app).edit().putBoolean(bandKey, false).apply()
            }
        }
    }

    // ---------------- v2.9 registrations ----------------
    @Volatile private var smsDeliveredReceiver: android.content.BroadcastReceiver? = null

    private fun registerSmsDelivered(app: Context) {
        if (smsDeliveredReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                onEvent(c.applicationContext, "sms_delivered", JSONObject()
                    .put("title", intent.getStringExtra("number") ?: ""))
            }
        }
        try {
            val f = android.content.IntentFilter("com.mthtamim.phonelink.SMS_DELIVERED")
            if (android.os.Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, f)
            }
            smsDeliveredReceiver = r
        } catch (_: Exception) { }
    }

    @Volatile private var mediaObserver: android.database.ContentObserver? = null

    private fun registerMediaObserver(app: Context, types: Set<String>) {
        if (mediaObserver != null) return
        if (!types.contains("screenshot_taken") && !types.contains("photo_taken") &&
            !types.contains("video_taken")) return
        val obs = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { checkLatestMedia(app) }
        }
        try {
            app.contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
            app.contentResolver.registerContentObserver(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
            mediaObserver = obs
            checkLatestMedia(app) // baseline only; fires on later changes
        } catch (_: Exception) { }
    }

    private fun checkLatestMedia(app: Context) {
        try {
            val p = prefs(app)
            app.contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH),
                null, null,
                android.provider.MediaStore.Images.Media._ID + " DESC")?.use { cur ->
                if (cur.moveToFirst()) {
                    val id = cur.getLong(0)
                    val name = (cur.getString(1) ?: "") + " " +
                        (cur.getString(2) ?: "")
                    val last = p.getLong("media_img_id", -1)
                    if (last >= 0 && id > last) {
                        if (name.contains("screenshot", true))
                            onEvent(app, "screenshot_taken",
                                JSONObject().put("title", name.trim()))
                        else onEvent(app, "photo_taken",
                            JSONObject().put("title", name.trim()))
                    }
                    if (id != last) p.edit().putLong("media_img_id", id).apply()
                }
            }
            app.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME),
                null, null,
                android.provider.MediaStore.Video.Media._ID + " DESC")?.use { cur ->
                if (cur.moveToFirst()) {
                    val id = cur.getLong(0)
                    val last = p.getLong("media_vid_id", -1)
                    if (last >= 0 && id > last)
                        onEvent(app, "video_taken",
                            JSONObject().put("title", cur.getString(1) ?: ""))
                    if (id != last) p.edit().putLong("media_vid_id", id).apply()
                }
            }
        } catch (_: Exception) { }
    }

    @Volatile private var opsRegistered = false

    /** Camera/microphone-in-use triggers via AppOps (Android 11+). */
    private fun registerOps(app: Context, types: Set<String>) {
        if (opsRegistered) return
        if (!types.contains("camera_used") && !types.contains("mic_used")) return
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val aom = app.getSystemService(Context.APP_OPS_SERVICE)
                as android.app.AppOpsManager
            val listener = android.app.AppOpsManager.OnOpActiveChangedListener {
                    op, _, packageName, active ->
                if (active) {
                    val ev = when (op) {
                        android.app.AppOpsManager.OPSTR_CAMERA -> "camera_used"
                        android.app.AppOpsManager.OPSTR_RECORD_AUDIO -> "mic_used"
                        else -> ""
                    }
                    if (ev.isNotEmpty()) onEvent(app, ev, JSONObject()
                        .put("pkg", packageName)
                        .put("app", appLabel(app, packageName))
                        .put("title", packageName))
                }
            }
            aom.startWatchingActive(arrayOf(
                android.app.AppOpsManager.OPSTR_CAMERA,
                android.app.AppOpsManager.OPSTR_RECORD_AUDIO),
                app.mainExecutor, listener)
            opsRegistered = true
        } catch (_: Exception) { }
    }

    // ---------------- v2.7: system broadcast triggers ----------------
    @Volatile private var sysReceiver: android.content.BroadcastReceiver? = null

    private fun registerSysReceiver(app: Context) {
        if (sysReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val ctx = c.applicationContext
                try {
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED -> if (!intent.getBooleanExtra(
                                Intent.EXTRA_REPLACING, false))
                            onEvent(ctx, "app_installed", JSONObject()
                                .put("pkg", intent.data?.schemeSpecificPart ?: ""))
                        Intent.ACTION_PACKAGE_REPLACED ->
                            onEvent(ctx, "app_updated", JSONObject()
                                .put("pkg", intent.data?.schemeSpecificPart ?: ""))
                        Intent.ACTION_PACKAGE_REMOVED -> if (!intent.getBooleanExtra(
                                Intent.EXTRA_REPLACING, false))
                            onEvent(ctx, "app_removed", JSONObject()
                                .put("pkg", intent.data?.schemeSpecificPart ?: ""))
                        Intent.ACTION_USER_PRESENT ->
                            onEvent(ctx, "screen_unlocked", JSONObject())
                        android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            @Suppress("DEPRECATION")
                            val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                                android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                            onEvent(ctx, "bt_connected", JSONObject()
                                .put("title", try { dev?.name ?: "" } catch (e: Exception) { "" }))
                        }
                        android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            @Suppress("DEPRECATION")
                            val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                                android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                            onEvent(ctx, "bt_disconnected", JSONObject()
                                .put("title", try { dev?.name ?: "" } catch (e: Exception) { "" }))
                        }
                        android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                                as android.app.NotificationManager
                            onEvent(ctx, if (nm.currentInterruptionFilter ==
                                android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                                "dnd_off" else "dnd_on", JSONObject())
                        }
                        Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                            onEvent(ctx, if (intent.getBooleanExtra("state", false))
                                "airplane_on" else "airplane_off", JSONObject())
                        Intent.ACTION_DEVICE_STORAGE_LOW ->
                            onEvent(ctx, "storage_low", JSONObject())
                        Intent.ACTION_TIMEZONE_CHANGED ->
                            onEvent(ctx, "timezone_changed", JSONObject())
                        Intent.ACTION_LOCALE_CHANGED ->
                            onEvent(ctx, "locale_changed", JSONObject())
                        "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                            // v2.15.0: live hotspot edge. The old
                            // reflection read of isWifiApEnabled silently
                            // returned false on modern Android, so the
                            // poll never saw the hotspot at all.
                            val st = intent.getIntExtra("wifi_state", -1)
                            if (st == 13) onEvent(ctx, "hotspot_on", JSONObject())
                            if (st == 11) onEvent(ctx, "hotspot_off", JSONObject())
                        }
                        android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                            val pm = ctx.getSystemService(Context.POWER_SERVICE)
                                as android.os.PowerManager
                            onEvent(ctx, if (pm.isPowerSaveMode)
                                "battery_saver_on" else "battery_saver_off", JSONObject())
                        }
                        "android.intent.action.SIM_STATE_CHANGED" ->
                            onEvent(ctx, "sim_changed", JSONObject())
                        android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val dev = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                                android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                            val name = try { dev?.name ?: "" }
                                catch (e: Exception) { "" }
                            val rssi = intent.getShortExtra(
                                android.bluetooth.BluetoothDevice.EXTRA_RSSI,
                                Short.MIN_VALUE).toInt()
                            if (name.isNotEmpty()) {
                                val d = JSONObject().put("title", name)
                                if (rssi >= -70) onEvent(ctx, "bt_near", d)
                                if (rssi in -200..-90) onEvent(ctx, "bt_far", d)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, f)
            }
            sysReceiver = r
        } catch (_: Exception) { }
    }

    // ---------------- v2.8 helpers ----------------
    private fun allowedPath(ctx: Context, f: File): Boolean {
        return try {
            val canon = f.canonicalPath
            listOfNotNull(ctx.filesDir, ctx.getExternalFilesDir(null),
                ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS))
                .any { canon.startsWith(it.canonicalPath) }
        } catch (_: Exception) { false }
    }

    private fun zipPath(src: File, dst: File) {
        try {
            dst.parentFile?.mkdirs()
            java.util.zip.ZipOutputStream(dst.outputStream().buffered()).use { zos ->
                fun add(f: File, base: String) {
                    val name = if (base.isEmpty()) f.name else "$base/${f.name}"
                    if (f.isDirectory) {
                        f.listFiles()?.forEach { add(it, name) }
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                add(src, "")
            }
        } catch (_: Exception) { }
    }

    private fun insertCalendarEvent(ctx: Context, title: String, delayMin: Long, durMin: Long) {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                localNotify(ctx, "ক্যালেন্ডার ইভেন্টের জন্য ক্যালেন্ডার অনুমতি দাও।")
                return
            }
            val calCur = ctx.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Calendars._ID),
                android.provider.CalendarContract.Calendars.VISIBLE + " = 1",
                null, null)
            var calId = -1L
            calCur?.use { if (it.moveToFirst()) calId = it.getLong(0) }
            if (calId < 0) return
            val start = System.currentTimeMillis() + delayMin * 60_000
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, start)
                put(android.provider.CalendarContract.Events.DTEND, start + durMin * 60_000)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE,
                    java.util.TimeZone.getDefault().id)
            }
            ctx.contentResolver.insert(
                android.provider.CalendarContract.Events.CONTENT_URI, values)
        } catch (_: Exception) { }
    }

    @Volatile private var keepAwakeLock: android.os.PowerManager.WakeLock? = null
    private fun setKeepAwake(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (keepAwakeLock?.isHeld == true) return
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ON_AFTER_RELEASE, "phonelink:keepawake")
                wl.acquire(10 * 3600_000L)
                keepAwakeLock = wl
            } else {
                if (keepAwakeLock?.isHeld == true) keepAwakeLock?.release()
                keepAwakeLock = null
            }
        } catch (_: Exception) { }
    }

    // ---------------- small helpers ----------------
    private fun minutesNow(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun parseHm(s: String): Int {
        val p = s.trim().split(":")
        if (p.size != 2) return 0
        return (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
    }

    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun batteryPct(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun isCharging(ctx: Context): Boolean {
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun currentSsid(ctx: Context): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        return wm.connectionInfo?.ssid?.trim('"') ?: ""
    }

    fun foregroundPkg(ctx: Context): String {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
        } catch (e: Exception) { "" }
    }

    fun appLabel(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    fun localNotify(ctx: Context, text: String) {
        val builder = NotificationCompat.Builder(ctx, LinkService.CHANNEL_INBOX)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PhoneLink 🤖")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()

    /** v2.14.1: TextToSpeech starts asynchronously — speaking before
     *  onInit drops the utterance silently, which is why "speak" rules
     *  worked in the foreground test but stayed mute when fired in the
     *  background. Queue until the engine is ready, then flush. */
    private fun speak(ctx: Context, text: String) {
        try {
            val engine = tts
            if (engine != null && ttsReady) {
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, "auto")
                return
            }
            synchronized(pendingSpeech) { pendingSpeech.add(text) }
            if (engine == null) {
                ttsReady = false
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        val queued: List<String>
                        synchronized(pendingSpeech) {
                            queued = pendingSpeech.toList()
                            pendingSpeech.clear()
                        }
                        for (q in queued)
                            tts?.speak(q, TextToSpeech.QUEUE_ADD, null, "auto")
                    } else {
                        // Dead engine: drop it so the next call retries.
                        tts = null
                        ttsReady = false
                        synchronized(pendingSpeech) { pendingSpeech.clear() }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /** v2.15.0: secure-settings and radio actions are impossible for a
     *  normal app on modern Android, but Shizuku's shell CAN do them.
     *  Returns true only when the command really ran clean. */
    /** v2.15.1: where a toggle-family action should end up. An
     *  explicit on/off param wins; a BLANK param (or the word
     *  "toggle") means flip — read the live state, do the opposite.
     *  Tamim asked for real toggles, not an on/off typing box. */
    private fun toggleTarget(param: String, current: Boolean): Boolean =
        when (param.trim().lowercase()) {
            "off", "0", "false" -> false
            "on", "1", "true" -> true
            else -> !current
        }

    private fun shOk(command: String): Boolean {
        if (!ShizukuHelper.granted()) return false
        ShizukuHelper.run(command)
        return ShizukuHelper.lastError == null
    }

    private fun setRinger(ctx: Context, mode: String) {
        val m = mode.trim().lowercase()
        // v2.15.0: RINGER_MODE_SILENT throws without Do Not Disturb
        // policy access on modern Android — the old code let the
        // exception die in a caller catch, so "silent" silently did
        // nothing while normal/vibrate worked. Say so instead.
        if (m == "silent") {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                localNotify(ctx, "সাইলেন্ট করতে PhoneLink-কে Do Not Disturb access দাও (সেটিংস → অ্যাপ → Special access)।")
                return
            }
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = when (m) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: Exception) {
            localNotify(ctx, "রিংগার মোড বদলানো যায়নি: " +
                (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg.trim()) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun httpCall(param: String) {
        Thread {
            try {
                val parts = param.split("|", limit = 3)
                val method = if (parts.size > 1) parts[0].trim().uppercase() else "GET"
                val urlStr = if (parts.size > 1) parts[1].trim() else parts[0].trim()
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = if (method == "POST") "POST" else "GET"
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                if (method == "POST" && parts.size > 2) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(parts[2].toByteArray(Charsets.UTF_8)) }
                }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { }
        }.start()
    }

    private fun writeFile(ctx: Context, param: String) {
        val parts = param.split("|", limit = 2)
        if (parts.size < 2) return
        val dir = File(ctx.filesDir, "automation").apply { mkdirs() }
        val name = parts[0].trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(dir, name).appendText(parts[1] + "\n")
    }
}
