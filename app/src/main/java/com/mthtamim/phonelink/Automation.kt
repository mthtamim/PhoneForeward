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
            "sms_received", "notif_received", "screen_text", "notif_cleared",
            "sms_sent", "call_outgoing", "call_ended" -> {
                val hay = (data.optString("title") + " " + data.optString("body") +
                    " " + data.optString("app")).lowercase()
                hay.contains(param.lowercase())
            }
            "app_launched", "app_closed", "app_installed", "app_removed" ->
                data.optString("pkg") == param
            "bt_connected", "bt_disconnected" ->
                data.optString("title").contains(param, ignoreCase = true)
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
                "stop" -> return
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
                        android.provider.Settings.System.putInt(ctx.contentResolver,
                            android.provider.Settings.System.ACCELEROMETER_ROTATION,
                            if (param == "off") 0 else 1)
                    }
                }
                "bt_toggle" -> {
                    try {
                        @Suppress("DEPRECATION")
                        val ok = if (param == "off") BtShare.adapter()?.disable()
                            else BtShare.adapter()?.enable()
                        if (ok != true) localNotify(ctx,
                            "Bluetooth সরাসরি বদলানো যায়নি — নতুন Android-এ সীমাবদ্ধ।")
                    } catch (e: Exception) {
                        localNotify(ctx,
                            "Bluetooth সরাসরি বদলানো যায়নি — নতুন Android-এ সীমাবদ্ধ।")
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
                    try {
                        (ctx.getSystemService(Context.TELECOM_SERVICE)
                            as android.telecom.TelecomManager).acceptRingingCall()
                    } catch (_: Exception) { }
                }
                "end_call" -> {
                    try {
                        @Suppress("DEPRECATION")
                        (ctx.getSystemService(Context.TELECOM_SERVICE)
                            as android.telecom.TelecomManager).endCall()
                    } catch (_: Exception) { }
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
                    val target = rules(ctx).firstOrNull {
                        it.optString("name").equals(param.trim(), true) }
                    if (target != null) {
                        when (type) {
                            "rule_enable" -> setEnabled(ctx, target.optString("id"), true)
                            "rule_disable" -> setEnabled(ctx, target.optString("id"), false)
                            else -> if (!target.optString("name")
                                    .equals(ruleNameHint, true) &&
                                constraintsPass(ctx, target)) {
                                ruleNameHint = target.optString("name")
                                runActions(ctx, target, data, 0)
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
                "toast_local" -> android.os.Handler(android.os.Looper.getMainLooper())
                    .post {
                        android.widget.Toast.makeText(ctx, param,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
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
                        "battery_full" -> batteryCrossing(ctx, rule, "battery_above", "100")
                        "battery_temp_above" -> tempCrossing(ctx, rule, param)
                        "date_at" -> dateDue(ctx, rule, param)
                        "stopwatch" -> stopwatchDue(ctx, rule, param)
                        "peer_battery_below" -> peerBatteryCrossing(ctx, rule, param)
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
                pollStates(ctx)
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
        } catch (_: Exception) { }
    }

    fun refreshWatchers(ctx: Context) {
        val app = ctx.applicationContext
        // Stop old listeners first.
        sensorListener?.let { l -> sensorMgr?.unregisterListener(l) }
        sensorListener = null
        if (!Config.automationOn(app)) return
        registerSysReceiver(app)
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
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        val lastLux = prefs(app).getFloat("last_lux", -1f)
                        if (kotlin.math.abs(lastLux - lux) > 10)
                            prefs(app).edit().putFloat("last_lux", lux).apply()
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
