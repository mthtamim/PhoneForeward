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
        prefs(ctx).edit().putString("rules", arr.toString()).apply()
        refreshWatchers(ctx)
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
        list.firstOrNull { it.optString("id") == id }?.put("enabled", on)
        saveAll(ctx, list)
    }

    // ---------------- variables ----------------
    fun getVar(ctx: Context, name: String): String =
        prefs(ctx).getString("var_$name", "") ?: ""

    fun setVar(ctx: Context, name: String, value: String) {
        prefs(ctx).edit().putString("var_$name", value).apply()
    }

    // ---------------- event entry ----------------
    fun onEvent(ctx: Context, event: String, data: JSONObject = JSONObject()) {
        if (!Config.automationOn(ctx)) return
        Thread {
            try {
                val now = System.currentTimeMillis()
                for (rule in rules(ctx)) {
                    if (!rule.optBoolean("enabled", true)) continue
                    val trig = rule.optJSONObject("trigger") ?: continue
                    if (trig.optString("type") != event) continue
                    if (!triggerParamMatches(ctx, event, trig.optString("param"), data)) continue
                    if (debounced(ctx, rule.optString("id"), now)) continue
                    if (!constraintsPass(ctx, rule)) continue
                    markFired(ctx, rule.optString("id"), now)
                    ruleNameHint = rule.optString("name")
                    runActions(ctx, rule, data, 0)
                }
            } catch (e: Exception) { /* automation must never crash the host */ }
        }.start()
    }

    private fun debounced(ctx: Context, ruleId: String, now: Long): Boolean {
        val last = prefs(ctx).getLong("fired_$ruleId", 0L)
        return now - last < 20_000
    }

    private fun markFired(ctx: Context, ruleId: String, now: Long) {
        prefs(ctx).edit().putLong("fired_$ruleId", now).apply()
    }

    private fun triggerParamMatches(ctx: Context, event: String, param: String, data: JSONObject): Boolean {
        if (param.isBlank()) return true
        return when (event) {
            "sms_received", "notif_received", "screen_text" -> {
                val hay = (data.optString("title") + " " + data.optString("body") +
                    " " + data.optString("app")).lowercase()
                hay.contains(param.lowercase())
            }
            "app_launched", "app_closed" -> data.optString("pkg") == param
            "wifi_connected" -> data.optString("ssid").equals(param, true)
            "activity" -> data.optString("kind") == param
            else -> true
        }
    }

    // ---------------- constraints ----------------
    fun constraintsPass(ctx: Context, rule: JSONObject): Boolean {
        val arr = rule.optJSONArray("constraints") ?: return true
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (!constraintOk(ctx, c.optString("type"), c.optString("param"))) return false
        }
        return true
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
    fun runActions(ctx: Context, rule: JSONObject, data: JSONObject, fromIndex: Int) {
        val arr = rule.optJSONArray("actions") ?: return
        var i = fromIndex
        while (i < arr.length()) {
            val a = arr.optJSONObject(i)
            if (a == null) { i++; continue }
            val type = a.optString("type")
            val param = fill(a.optString("param"), data)
            when (type) {
                "wait" -> {
                    val mins = param.toLongOrNull() ?: 1
                    scheduleWait(ctx, rule, i + 1, System.currentTimeMillis() + mins * 60_000, data)
                    return
                }
                "if_var" -> if (!evalVar(ctx, param)) return
                else -> runAction(ctx, type, param, data)
            }
            i++
        }
    }

    private fun runAction(ctx: Context, type: String, param: String, data: JSONObject) {
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
                        @Suppress("DEPRECATION")
                        android.telephony.SmsManager.getDefault()
                            .sendTextMessage(parts[0].trim(), null, parts[1].trim().take(600), null, null)
                    }
                }
                "torch" -> FlashHelper.setTorch(ctx, param != "off")
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
            }
        } catch (e: Exception) { /* keep the chain alive */ }
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

    // ---------------- tick: time & polled triggers ----------------
    fun tick(ctx: Context) {
        if (!Config.automationOn(ctx)) return
        Thread {
            try {
                resumeWaits(ctx)
                val now = System.currentTimeMillis()
                val list = rules(ctx).filter { it.optBoolean("enabled", true) }
                for (rule in list) {
                    val trig = rule.optJSONObject("trigger") ?: continue
                    val type = trig.optString("type")
                    val param = trig.optString("param")
                    val should = when (type) {
                        "time_at" -> {
                            val today = dayKey()
                            val last = prefs(ctx).getString("day_${rule.optString("id")}", "")
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
                        else -> false
                    }
                    if (should && constraintsPass(ctx, rule)) {
                        if (type == "time_at" || type == "sunrise" || type == "sunset") {
                            prefs(ctx).edit().putString("day_${rule.optString("id")}", dayKey()).apply()
                        }
                        markFired(ctx, rule.optString("id"), now)
                        ruleNameHint = rule.optString("name")
                        runActions(ctx, rule, JSONObject(), 0)
                    }
                }
                pollWifi(ctx)
                pollAppUsage(ctx, list)
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
            val t = it.optJSONObject("trigger")?.optString("type") ?: ""
            t == "app_launched" || t == "app_closed"
        }
        if (watched.isEmpty()) return
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 35 * 60_000, now) ?: return
            val ev = UsageEvents.Event()
            val seenState = prefs(ctx).getString("appstate", "") ?: ""
            val states = seenState.split(";").filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }.toMutableMap()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                val kind = when (ev.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> "app_launched"
                    UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> "app_closed"
                    else -> continue
                }
                if (states[pkg] == kind) continue
                states[pkg] = kind
                for (rule in watched) {
                    val trig = rule.optJSONObject("trigger") ?: continue
                    if (trig.optString("type") != kind) continue
                    if (trig.optString("param").isNotBlank() && trig.optString("param") != pkg) continue
                    val data = JSONObject().put("pkg", pkg)
                        .put("app", appLabel(ctx, pkg)).put("title", appLabel(ctx, pkg))
                    if (constraintsPass(ctx, rule)) {
                        ruleNameHint = rule.optString("name")
                        runActions(ctx, rule, data, 0)
                    }
                }
            }
            prefs(ctx).edit().putString("appstate",
                states.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
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

    @Synchronized
    fun refreshWatchers(ctx: Context) {
        val app = ctx.applicationContext
        // Stop old listeners first.
        sensorListener?.let { l -> sensorMgr?.unregisterListener(l) }
        sensorListener = null
        if (!Config.automationOn(app)) return
        val types = rules(app).filter { it.optBoolean("enabled", true) }
            .mapNotNull { it.optJSONObject("trigger")?.optString("type") }.toSet()
        val needAccel = types.any { it == "shake" || it == "flip_down" || it == "flip_up" }
        val needProx = types.any { it == "proximity_near" || it == "proximity_far" }
        val needLight = types.any { it == "light_below" || it == "light_above" }
        if (!needAccel && !needProx && !needLight) return
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
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        val near = e.values[0] < (e.sensor.maximumRange)
                        if (near && lastProxNear != true) {
                            lastProxNear = true
                            onEvent(app, "proximity_near", JSONObject())
                        } else if (!near && lastProxNear != false) {
                            if (lastProxNear == true) onEvent(app, "proximity_far", JSONObject())
                            lastProxNear = false
                        }
                    }
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        for (rule in rules(app)) {
                            if (!rule.optBoolean("enabled", true)) continue
                            val trig = rule.optJSONObject("trigger") ?: continue
                            val t = trig.optString("type")
                            if (t != "light_below" && t != "light_above") continue
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

    private fun localNotify(ctx: Context, text: String) {
        val builder = NotificationCompat.Builder(ctx, LinkService.CHANNEL_INBOX)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PhoneLink 🤖")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }

    private var tts: TextToSpeech? = null
    private fun speak(ctx: Context, text: String) {
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { }
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "auto")
    }

    private fun setRinger(ctx: Context, mode: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = when (mode.lowercase()) {
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
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
