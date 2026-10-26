package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * v1.4.0 trigger hub: every Automate trigger that needs a live
 * source gets one here. One start mode in this merge:
 * FlowService (background) calls start() in onCreate and stop()
 * in onDestroy; the refcount keeps that pair idempotent. The
 * Automation screen does NOT start the hub: background dispatch
 * lives solely in FlowService while the Flows switch is on, and
 * manual Run-now needs no hub.
 *
 * Parts:
 * - SensorHub: SensorManager listeners for light, proximity,
 *   magnetic field, pressure, temperature, humidity, heart rate,
 *   hinge angle, steps, acceleration (shake/flip/pick_up/
 *   orientation), significant motion. Threshold types go through
 *   FlowEngine.sensorTick (per-flow threshold + edge); event types
 *   go straight to FlowEngine.onEvent.
 * - Dynamic receivers: airplane mode, headset plug, timezone,
 *   locale, SIM state, storage low, dock, USB state/attach.
 * - Observers: MediaStore (add/remove + photo/video/screenshot),
 *   Settings (system/secure/global per watched key), content URIs
 *   from rules.
 * - Armed-only components (started only while a rule uses them):
 *   MediaSession (media/headset buttons), SoundSampler (mic
 *   amplitude), LogcatWatcher (Shizuku logcat stream), Activity
 *   Recognition (Play Services), geofence proximity alerts.
 * - State readers used by FlowEngine tick branches.
 *
 * Nothing here auto-grants anything: sensors need no runtime
 * permission except heart rate (BODY_SENSORS, requested on the
 * Permissions screen); usage-stats, notification access and
 * Shizuku paths log clearly when their precondition is missing.
 */
object FlowTriggers {

    private var refCount = 0
    private var started = false

    @Volatile
    var lastForegroundApp = ""

    // ---------------- lifecycle ----------------

    @Synchronized
    fun start(ctx: Context) {
        refCount++
        if (started) {
            maybeArm(ctx)
            return
        }
        started = true
        try {
            sensorHub.start(ctx)
        } catch (_: Exception) {
        }
        try {
            registerDynamic(ctx)
        } catch (_: Exception) {
        }
        try {
            registerObservers(ctx)
        } catch (_: Exception) {
        }
        try {
            registerCameraCallbacks(ctx)
        } catch (_: Exception) {
        }
        try {
            registerNetworkCallback(ctx)
        } catch (_: Exception) {
        }
        try {
            registerTelephonyCallback(ctx)
        } catch (_: Exception) {
        }
        try {
            registerAccountSync(ctx)
        } catch (_: Exception) {
        }
        maybeArm(ctx)
    }

    @Synchronized
    fun stop(ctx: Context) {
        if (refCount > 0) {
            refCount--
        }
        if (refCount > 0 || !started) {
            return
        }
        started = false
        try {
            sensorHub.stop()
        } catch (_: Exception) {
        }
        try {
            unregisterDynamic(ctx)
        } catch (_: Exception) {
        }
        try {
            unregisterObservers(ctx)
        } catch (_: Exception) {
        }
        try {
            unregisterCameraCallbacks(ctx)
        } catch (_: Exception) {
        }
        try {
            unregisterNetworkCallback(ctx)
        } catch (_: Exception) {
        }
        try {
            unregisterTelephonyCallback(ctx)
        } catch (_: Exception) {
        }
        try {
            unregisterAccountSync(ctx)
        } catch (_: Exception) {
        }
        stopArmed(ctx)
    }

    /** Re-scan flow usage for armed-only components. Cheap and
     *  idempotent; called on start and from the 60s service tick. */
    fun maybeArm(ctx: Context) {
        try {
            val types = usedTriggerTypes(ctx)
            if (types.contains("media_button") || types.contains("headset_button")) {
                mediaSession.start(ctx)
            } else {
                mediaSession.stop()
            }
            if (types.contains("sound_level")) {
                soundSampler.start(ctx)
            } else {
                soundSampler.stop()
            }
            if (types.contains("log_await")) {
                logcatWatcher.start(ctx)
            } else {
                logcatWatcher.stop()
            }
            if (types.contains("activity_recognition")) {
                activityRec.start(ctx)
            } else {
                activityRec.stop(ctx)
            }
            geofenceSync(ctx)
            syncFileObservers(ctx)
        } catch (_: Exception) {
        }
    }

    /** FileObserver set for file_changed flow nodes (param =
     *  file or directory path). Re-synced on every maybeArm so
     *  flows added while running take effect without a restart. */
    private val fileObservers = HashMap<String, android.os.FileObserver>()

    @Suppress("DEPRECATION")
    private fun syncFileObservers(ctx: Context) {
        val wanted = HashSet<String>()
        for (trig in flowTriggerNodes(ctx)) {
            if (trig.optString("type") == "file_changed") {
                val p = trig.optString("param").trim()
                if (p.isNotEmpty()) {
                    wanted.add(p)
                }
            }
        }
        val it = fileObservers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key !in wanted) {
                try {
                    entry.value.stopWatching()
                } catch (_: Exception) {
                }
                it.remove()
            }
        }
        for (path in wanted) {
            if (path in fileObservers) {
                continue
            }
            try {
                val observer = object : android.os.FileObserver(
                    path,
                    android.os.FileObserver.MODIFY or
                        android.os.FileObserver.CREATE or
                        android.os.FileObserver.DELETE or
                        android.os.FileObserver.MOVED_FROM or
                        android.os.FileObserver.MOVED_TO
                ) {
                    override fun onEvent(event: Int, child: String?) {
                        val data = JSONObject()
                        data.put(
                            "title",
                            if (child != null) path + "/" + child else path
                        )
                        FlowEngine.onEvent(ctx.applicationContext, "file_changed", data)
                    }
                }
                observer.startWatching()
                fileObservers[path] = observer
            } catch (_: Exception) {
            }
        }
    }

    private fun stopFileObservers() {
        for (observer in fileObservers.values) {
            try {
                observer.stopWatching()
            } catch (_: Exception) {
            }
        }
        fileObservers.clear()
    }

    private fun stopArmed(ctx: Context) {
        try {
            mediaSession.stop()
        } catch (_: Exception) {
        }
        try {
            soundSampler.stop()
        } catch (_: Exception) {
        }
        try {
            logcatWatcher.stop()
        } catch (_: Exception) {
        }
        try {
            activityRec.stop(ctx)
        } catch (_: Exception) {
        }
        try {
            stopFileObservers()
        } catch (_: Exception) {
        }
    }

    /** Trigger nodes of every saved flow (kind "t"), in file
     *  order. Flow scans replace Lab's rule scans 1:1 — the Flow
     *  engine never reads PhoneLink rules, so a trigger that only
     *  exists on a rule must never arm a Flow component. */
    fun flowTriggerNodes(ctx: Context): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            for (flow in FlowStore.flows(ctx)) {
                val nodes = flow.optJSONArray("nodes") ?: continue
                for (k in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(k) ?: continue
                    if (n.optString("kind") == "t") {
                        out.add(n)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Trigger nodes of ENABLED flows only (logcat watcher parity:
     *  Lab armed log_await only for enabled rules). */
    fun enabledFlowTriggerNodes(ctx: Context): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            for (flow in FlowStore.flows(ctx)) {
                if (!flow.optBoolean("enabled", true)) {
                    continue
                }
                val nodes = flow.optJSONArray("nodes") ?: continue
                for (k in 0 until nodes.length()) {
                    val n = nodes.optJSONObject(k) ?: continue
                    if (n.optString("kind") == "t") {
                        out.add(n)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun usedTriggerTypes(ctx: Context): Set<String> {
        val out = HashSet<String>()
        try {
            for (trig in flowTriggerNodes(ctx)) {
                out.add(trig.optString("type"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ---------------- foreground-app edge (shared by Home + service) ----------------

    fun checkForegroundApp(ctx: Context) {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val list = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 60_000L, now
            ) ?: return
            var best: String? = null
            var bestTime = 0L
            for (s in list) {
                if (s.lastTimeUsed > bestTime) {
                    bestTime = s.lastTimeUsed
                    best = s.packageName
                }
            }
            val current = best ?: return
            if (lastForegroundApp.isNotEmpty() && lastForegroundApp != current) {
                val closed = JSONObject()
                closed.put("pkg", lastForegroundApp)
                FlowEngine.onEvent(ctx, "app_closed", closed)
                val opened = JSONObject()
                opened.put("pkg", current)
                FlowEngine.onEvent(ctx, "app_launched", opened)
            }
            lastForegroundApp = current
        } catch (_: Exception) {
        }
    }

    // ---------------- SensorHub ----------------

    private object sensorHub : SensorEventListener, TriggerEventListener() {
        private var mgr: SensorManager? = null
        private var ctxRef: Context? = null
        private var lastShakeMs = 0L
        private var faceSide = 0 // 1 = up, -1 = down, 0 = unknown
        private var lastFaceMs = 0L
        private var stillSince = 0L
        private var wasStill = false
        private var lastOrient = ""
        private var stepBaselineDay = ""
        private var stepBaseline = 0f
        private var stepFiredDay = ""
        private var lastTemp = Float.NaN
        private var lastHum = Float.NaN
        private var lastHinge = Float.NaN
        private var sigRegistered = false

        fun start(ctx: Context) {
            val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            mgr = m
            ctxRef = ctx.applicationContext
            val types = listOf(
                Sensor.TYPE_LIGHT,
                Sensor.TYPE_PROXIMITY,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_PRESSURE,
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                Sensor.TYPE_RELATIVE_HUMIDITY,
                Sensor.TYPE_HEART_RATE,
                Sensor.TYPE_HINGE_ANGLE,
                Sensor.TYPE_STEP_COUNTER,
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_SIGNIFICANT_MOTION
            )
            for (t in types) {
                val s = m.getDefaultSensor(t) ?: continue
                if (t == Sensor.TYPE_SIGNIFICANT_MOTION) {
                    try {
                        sigRegistered = m.requestTriggerSensor(this, s)
                    } catch (_: Exception) {
                        sigRegistered = false
                    }
                } else {
                    try {
                        m.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun stop() {
            try {
                mgr?.unregisterListener(this)
            } catch (_: Exception) {
            }
            try {
                val s = mgr?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
                if (s != null) {
                    mgr?.cancelTriggerSensor(this, s)
                }
            } catch (_: Exception) {
            }
            sigRegistered = false
            mgr = null
            ctxRef = null
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        override fun onSensorChanged(event: SensorEvent) {
            val ctx = ctxRef ?: return
            try {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT ->
                        FlowEngine.sensorTick(ctx, "light", event.values[0])
                    Sensor.TYPE_PROXIMITY ->
                        FlowEngine.sensorTick(ctx, "proximity", event.values[0])
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val mag = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                        )
                        FlowEngine.sensorTick(ctx, "magnetic", mag)
                    }
                    Sensor.TYPE_PRESSURE ->
                        FlowEngine.sensorTick(ctx, "pressure", event.values[0])
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        val v = event.values[0]
                        if (lastTemp.isNaN() || kotlin.math.abs(v - lastTemp) >= 0.5f) {
                            lastTemp = v
                            FlowEngine.sensorTick(ctx, "temp", v)
                        }
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        val v = event.values[0]
                        if (lastHum.isNaN() || kotlin.math.abs(v - lastHum) >= 1f) {
                            lastHum = v
                            FlowEngine.sensorTick(ctx, "humidity", v)
                        }
                    }
                    Sensor.TYPE_HEART_RATE ->
                        FlowEngine.sensorTick(ctx, "heart", event.values[0])
                    Sensor.TYPE_HINGE_ANGLE -> {
                        val v = event.values[0]
                        if (lastHinge.isNaN() || kotlin.math.abs(v - lastHinge) >= 5f) {
                            lastHinge = v
                            FlowEngine.sensorTick(ctx, "hinge", v)
                        }
                    }
                    Sensor.TYPE_STEP_COUNTER -> onSteps(ctx, event.values[0])
                    Sensor.TYPE_ACCELEROMETER -> onAccel(ctx, event.values)
                    Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(ctx, event.values)
                    else -> {
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Trigger sensors never deliver via onSensorChanged: significant
        // motion arrives here, fires once, and is re-armed for the next one.
        override fun onTrigger(event: TriggerEvent) {
            val ctx = ctxRef ?: return
            try {
                FlowEngine.onEvent(ctx, "sig_motion", JSONObject())
                val s = mgr?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
                if (s != null) {
                    mgr?.requestTriggerSensor(this, s)
                }
            } catch (_: Exception) {
            }
        }

        private fun onSteps(ctx: Context, total: Float) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            if (stepBaselineDay != day) {
                stepBaselineDay = day
                stepBaseline = total
                stepFiredDay = ""
            }
            val today = total - stepBaseline
            if (today < 0) {
                stepBaseline = total
                return
            }
            if (stepFiredDay == day) {
                return
            }
            for (flow in FlowStore.flows(ctx)) {
                if (!flow.optBoolean("enabled", true)) {
                    continue
                }
                val fNodes = flow.optJSONArray("nodes") ?: continue
                for (k in 0 until fNodes.length()) {
                    val trig = fNodes.optJSONObject(k) ?: continue
                    if (trig.optString("kind") != "t") {
                        continue
                    }
                    if (trig.optString("type") != "steps_goal") {
                        continue
                    }
                    val goal = trig.optString("param").toFloatOrNull() ?: continue
                    if (today >= goal) {
                        stepFiredDay = day
                        val data = JSONObject()
                        data.put("steps", today.toInt().toString())
                        FlowEngine.onEvent(ctx, "steps_goal", data)
                        return
                    }
                }
            }
        }

        private fun onAccel(ctx: Context, v: FloatArray) {
            val now = System.currentTimeMillis()
            val x = v[0]
            val y = v[1]
            val z = v[2]
            // Flip: face-up (z strongly positive) <-> face-down.
            val side = when {
                z > 7f -> 1
                z < -7f -> -1
                else -> 0
            }
            if (side != 0 && side != faceSide && now - lastFaceMs > 500L) {
                if (faceSide != 0) {
                    val data = JSONObject()
                    if (side == -1) {
                        FlowEngine.onEvent(ctx, "flip_down", data)
                    } else {
                        FlowEngine.onEvent(ctx, "flip_up", data)
                    }
                }
                faceSide = side
                lastFaceMs = now
            }
            // Orientation from the dominant gravity axis.
            val ax = kotlin.math.abs(x)
            val ay = kotlin.math.abs(y)
            val az = kotlin.math.abs(z)
            val kind = when {
                ay >= ax && ay >= az -> "portrait"
                ax >= ay && ax >= az -> "landscape"
                else -> "flat"
            }
            if (kind != lastOrient) {
                lastOrient = kind
                val data = JSONObject()
                data.put("kind", kind)
                FlowEngine.onEvent(ctx, "orientation_changed", data)
            }
            // Stillness tracking feeds pick_up (see onLinear).
            val lin = sqrt(x * x + y * y + (z - 9.8f) * (z - 9.8f))
            if (lin < 0.6f) {
                if (!wasStill) {
                    wasStill = true
                    stillSince = now
                }
            } else {
                wasStill = false
            }
        }

        private fun onLinear(ctx: Context, v: FloatArray) {
            val now = System.currentTimeMillis()
            val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            // Shake: sharp linear spike, 1s cooldown.
            if (mag > 15f && now - lastShakeMs > 1000L) {
                lastShakeMs = now
                FlowEngine.onEvent(ctx, "shake", JSONObject())
            }
            // Pick up: still for 2s, then a clear movement spike.
            if (wasStill && now - stillSince > 2000L && mag > 3f) {
                wasStill = false
                FlowEngine.onEvent(ctx, "pick_up", JSONObject())
            }
            FlowEngine.sensorTick(ctx, "accel", mag)
        }
    }

    // ---------------- dynamic receivers ----------------

    private var dynamicReceiver: android.content.BroadcastReceiver? = null
    private var dynamicCtx: Context? = null

    private fun registerDynamic(ctx: Context) {
        val appCtx = ctx.applicationContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                try {
                    handleDynamic(c, intent)
                } catch (_: Exception) {
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        filter.addAction(Intent.ACTION_HEADSET_PLUG)
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        filter.addAction(Intent.ACTION_LOCALE_CHANGED)
        filter.addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
        filter.addAction(Intent.ACTION_DOCK_EVENT)
        filter.addAction("android.hardware.usb.action.USB_STATE")
        filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
        try {
            appCtx.registerReceiver(receiver, filter)
            dynamicReceiver = receiver
            dynamicCtx = appCtx
        } catch (_: Exception) {
        }
        // Clipboard changes (best-effort: on Android 10+ a background
        // listener may see the change but not the text).
        try {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                try {
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    val data = JSONObject()
                    data.put("title", text)
                    data.put("body", text)
                    FlowEngine.onEvent(appCtx, "clipboard", data)
                } catch (_: Exception) {
                }
            }
            cm.addPrimaryClipChangedListener(listener)
            clipboardListener = listener
            clipboardMgr = cm
        } catch (_: Exception) {
        }
    }

    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardMgr: android.content.ClipboardManager? = null

    private fun unregisterDynamic(ctx: Context) {
        try {
            val r = dynamicReceiver
            val c = dynamicCtx ?: ctx.applicationContext
            if (r != null) {
                c.unregisterReceiver(r)
            }
        } catch (_: Exception) {
        }
        dynamicReceiver = null
        dynamicCtx = null
        try {
            val listener = clipboardListener
            if (listener != null) {
                clipboardMgr?.removePrimaryClipChangedListener(listener)
            }
        } catch (_: Exception) {
        }
        clipboardListener = null
        clipboardMgr = null
    }

    private fun handleDynamic(ctx: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val on = intent.getBooleanExtra("state", false)
                FlowEngine.onEvent(ctx, if (on) "airplane_on" else "airplane_off", JSONObject())
            }
            Intent.ACTION_HEADSET_PLUG -> {
                val plugged = intent.getIntExtra("state", 0) == 1
                FlowEngine.onEvent(ctx, if (plugged) "headphone_in" else "headphone_out", JSONObject())
            }
            Intent.ACTION_TIMEZONE_CHANGED ->
                FlowEngine.onEvent(ctx, "timezone_changed", JSONObject())
            Intent.ACTION_LOCALE_CHANGED ->
                FlowEngine.onEvent(ctx, "locale_changed", JSONObject())
            "android.telephony.action.SIM_CARD_STATE_CHANGED" -> {
                FlowEngine.onEvent(ctx, "sim_changed", JSONObject())
                try {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    val state = tm.simState
                    val data = JSONObject()
                    data.put("state", simStateName(state))
                    FlowEngine.onEvent(ctx, "sim_state", data)
                    if (state == android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED) {
                        FlowEngine.onEvent(ctx, "sim_locked", JSONObject())
                    }
                } catch (_: Exception) {
                }
            }
            Intent.ACTION_DEVICE_STORAGE_LOW ->
                FlowEngine.onEvent(ctx, "storage_low", JSONObject())
            Intent.ACTION_DOCK_EVENT -> {
                val docked = intent.getIntExtra(
                    Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED
                ) != Intent.EXTRA_DOCK_STATE_UNDOCKED
                val data = JSONObject()
                data.put("docked", docked.toString())
                FlowEngine.onEvent(ctx, "docked", data)
            }
            "android.hardware.usb.action.USB_STATE" -> {
                val connected = intent.getBooleanExtra("connected", false)
                val configured = intent.getBooleanExtra("configured", false)
                if (connected) {
                    FlowEngine.onEvent(ctx, "usb_connected", JSONObject())
                }
                if (configured) {
                    FlowEngine.onEvent(ctx, "usb_configured", JSONObject())
                }
            }
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra(
                    "device"
                ) as? android.hardware.usb.UsbDevice
                val data = JSONObject()
                data.put("title", device?.deviceName ?: "")
                FlowEngine.onEvent(ctx, "usb_attached", data)
            }
            else -> {
            }
        }
    }

    fun simStateName(state: Int): String {
        return when (state) {
            android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
            android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
            android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
            android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
            android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
            android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
            android.telephony.TelephonyManager.SIM_STATE_PERM_DISABLED -> "perm_disabled"
            android.telephony.TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "io_error"
            android.telephony.TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
            else -> "unknown"
        }
    }

    // ---------------- observers ----------------

    private val mediaObservers = ArrayList<android.database.ContentObserver>()
    private var settingsObserver: android.database.ContentObserver? = null
    private val contentObservers = ArrayList<android.database.ContentObserver>()
    private val mediaCounts = HashMap<String, Int>()

    private fun registerObservers(ctx: Context) {
        val appCtx = ctx.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val collections = listOf(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image",
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video",
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio"
        )
        for ((uri, kind) in collections) {
            mediaCounts[kind] = mediaCount(appCtx, uri)
            val observer = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    onMediaChanged(appCtx, uri, kind)
                }
            }
            try {
                appCtx.contentResolver.registerContentObserver(uri, true, observer)
                mediaObservers.add(observer)
            } catch (_: Exception) {
            }
        }
        val settingsObs = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onSettingsChanged(appCtx)
            }
        }
        try {
            val cr = appCtx.contentResolver
            cr.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI, true, settingsObs
            )
            cr.registerContentObserver(
                android.provider.Settings.Secure.CONTENT_URI, true, settingsObs
            )
            cr.registerContentObserver(
                android.provider.Settings.Global.CONTENT_URI, true, settingsObs
            )
            settingsObserver = settingsObs
        } catch (_: Exception) {
        }
        // Content URIs watched by flows (content_changed trigger).
        val uris = HashSet<String>()
        for (trig in flowTriggerNodes(ctx)) {
            if (trig.optString("type") == "content_changed") {
                val u = trig.optString("param").trim()
                if (u.isNotEmpty()) {
                    uris.add(u)
                }
            }
        }
        for (u in uris) {
            try {
                val uri = android.net.Uri.parse(u)
                val observer = object : android.database.ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        val data = JSONObject()
                        data.put("uri", u)
                        FlowEngine.onEvent(appCtx, "content_changed", data)
                    }
                }
                appCtx.contentResolver.registerContentObserver(uri, true, observer)
                contentObservers.add(observer)
            } catch (_: Exception) {
            }
        }
        // Broadcast actions watched by flows (broadcast_receive trigger).
        val actions = HashSet<String>()
        for (trig in flowTriggerNodes(ctx)) {
            if (trig.optString("type") == "broadcast_receive") {
                val a = trig.optString("param").trim()
                if (a.isNotEmpty()) {
                    actions.add(a)
                }
            }
        }
        if (actions.isNotEmpty()) {
            val filter = IntentFilter()
            for (a in actions) {
                try {
                    filter.addAction(a)
                } catch (_: Exception) {
                }
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent?) {
                    val data = JSONObject()
                    data.put("action", intent?.action ?: "")
                    FlowEngine.onEvent(c, "broadcast_receive", data)
                }
            }
            try {
                appCtx.registerReceiver(receiver, filter)
                broadcastReceivers.add(receiver)
                broadcastCtx = appCtx
            } catch (_: Exception) {
            }
        }
    }

    private val broadcastReceivers = ArrayList<android.content.BroadcastReceiver>()
    private var broadcastCtx: Context? = null

    private fun unregisterObservers(ctx: Context) {
        val appCtx = ctx.applicationContext
        try {
            for (o in mediaObservers) {
                appCtx.contentResolver.unregisterContentObserver(o)
            }
        } catch (_: Exception) {
        }
        mediaObservers.clear()
        try {
            val o = settingsObserver
            if (o != null) {
                appCtx.contentResolver.unregisterContentObserver(o)
            }
        } catch (_: Exception) {
        }
        settingsObserver = null
        try {
            for (o in contentObservers) {
                appCtx.contentResolver.unregisterContentObserver(o)
            }
        } catch (_: Exception) {
        }
        contentObservers.clear()
        try {
            val c = broadcastCtx ?: appCtx
            for (r in broadcastReceivers) {
                c.unregisterReceiver(r)
            }
        } catch (_: Exception) {
        }
        broadcastReceivers.clear()
        broadcastCtx = null
    }

    private fun mediaCount(ctx: Context, uri: android.net.Uri): Int {
        return try {
            val cursor = ctx.contentResolver.query(uri, arrayOf("_id"), null, null, null)
            val n = cursor?.count ?: 0
            try {
                cursor?.close()
            } catch (_: Exception) {
            }
            n
        } catch (_: Exception) {
            -1
        }
    }

    private fun onMediaChanged(ctx: Context, uri: android.net.Uri, kind: String) {
        try {
            val now = mediaCount(ctx, uri)
            val prev = mediaCounts[kind] ?: -1
            if (prev < 0) {
                mediaCounts[kind] = now
                return
            }
            if (now > prev) {
                mediaCounts[kind] = now
                val data = JSONObject()
                data.put("kind", kind)
                val latest = latestMedia(ctx, uri)
                if (latest.isNotEmpty()) {
                    data.put("title", latest)
                }
                FlowEngine.onEvent(ctx, "media_added", data)
                if (kind == "image") {
                    FlowEngine.onEvent(ctx, "photo_taken", data)
                    if (latest.contains("screenshot", ignoreCase = true)) {
                        FlowEngine.onEvent(ctx, "screenshot_taken", data)
                    }
                }
                if (kind == "video") {
                    FlowEngine.onEvent(ctx, "video_taken", data)
                }
            } else if (now < prev) {
                mediaCounts[kind] = now
                val data = JSONObject()
                data.put("kind", kind)
                FlowEngine.onEvent(ctx, "media_removed", data)
            }
        } catch (_: Exception) {
        }
    }

    private fun latestMedia(ctx: Context, uri: android.net.Uri): String {
        return try {
            val cursor = ctx.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null, null,
                android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC"
            ) ?: return ""
            var out = ""
            try {
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: ""
                    val path = try {
                        cursor.getString(1) ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    out = path + name
                }
            } finally {
                try {
                    cursor.close()
                } catch (_: Exception) {
                }
            }
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun onSettingsChanged(ctx: Context) {
        try {
            val keys = HashSet<String>()
            for (trig in flowTriggerNodes(ctx)) {
                if (trig.optString("type") == "settings_changed") {
                    val p = trig.optString("param").trim()
                    if (p.isNotEmpty()) {
                        keys.add(p)
                    }
                }
            }
            val cr = ctx.contentResolver
            for (key in keys) {
                val table: String
                val name: String
                if (key.contains(":")) {
                    table = key.substringBefore(":").trim().lowercase()
                    name = key.substringAfter(":").trim()
                } else {
                    table = ""
                    name = key
                }
                val value = when (table) {
                    "secure" -> android.provider.Settings.Secure.getString(cr, name)
                    "global" -> android.provider.Settings.Global.getString(cr, name)
                    else -> try {
                        android.provider.Settings.System.getString(cr, name)
                    } catch (_: Exception) {
                        android.provider.Settings.Secure.getString(cr, name)
                    }
                } ?: ""
                val storeKey = "setting_" + key
                val prev = FlowStore.stateStr(ctx, storeKey)
                if (prev.isNotEmpty() && prev != value) {
                    val data = JSONObject()
                    data.put("key", key)
                    data.put("value", value)
                    FlowEngine.onEvent(ctx, "settings_changed", data)
                }
                FlowStore.setStateStr(ctx, storeKey, value)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- camera callbacks ----------------

    private var cameraMgr: android.hardware.camera2.CameraManager? = null
    private val cameraCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            val ctx = camCtx ?: return
            val data = JSONObject()
            data.put("id", cameraId)
            data.put("available", "true")
            FlowEngine.onEvent(ctx, "camera_available", data)
            val used = JSONObject()
            used.put("in_use", "false")
            FlowEngine.onEvent(ctx, "camera_used", used)
        }

        override fun onCameraUnavailable(cameraId: String) {
            val ctx = camCtx ?: return
            val data = JSONObject()
            data.put("id", cameraId)
            data.put("available", "false")
            FlowEngine.onEvent(ctx, "camera_available", data)
            val used = JSONObject()
            used.put("in_use", "true")
            FlowEngine.onEvent(ctx, "camera_used", used)
        }
    }
    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val ctx = camCtx ?: return
            val data = JSONObject()
            data.put("on", enabled.toString())
            FlowEngine.onEvent(ctx, "torch_state", data)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
        }
    }
    private var camCtx: Context? = null

    private fun registerCameraCallbacks(ctx: Context) {
        val appCtx = ctx.applicationContext
        val mgr = appCtx.getSystemService(Context.CAMERA_SERVICE)
            as android.hardware.camera2.CameraManager
        cameraMgr = mgr
        camCtx = appCtx
        val handler = Handler(Looper.getMainLooper())
        try {
            mgr.registerAvailabilityCallback(cameraCallback, handler)
        } catch (_: Exception) {
        }
        try {
            mgr.registerTorchCallback(torchCallback, handler)
        } catch (_: Exception) {
        }
    }

    private fun unregisterCameraCallbacks(ctx: Context) {
        try {
            cameraMgr?.unregisterAvailabilityCallback(cameraCallback)
        } catch (_: Exception) {
        }
        try {
            cameraMgr?.unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
        cameraMgr = null
        camCtx = null
    }

    // ---------------- network callback ----------------

    private var connMgr: android.net.ConnectivityManager? = null
    private var lastNetTransport = ""
    private var lastIp = ""
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            val ctx = netCtx ?: return
            val caps = try {
                connMgr?.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
            val transport = transportName(caps)
            lastNetTransport = transport
            val data = JSONObject()
            data.put("transport", transport)
            FlowEngine.onEvent(ctx, "network_connected", data)
        }

        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            props: android.net.LinkProperties
        ) {
            val ctx = netCtx ?: return
            val ip = try {
                props.linkAddresses.joinToString(",") { it.address.hostAddress ?: "" }
            } catch (_: Exception) {
                ""
            }
            if (ip.isNotEmpty() && ip != lastIp) {
                lastIp = ip
                val data = JSONObject()
                data.put("ip", ip)
                FlowEngine.onEvent(ctx, "ip_changed", data)
            }
        }
    }
    private var netCtx: Context? = null

    fun transportName(caps: android.net.NetworkCapabilities?): String {
        if (caps == null) {
            return ""
        }
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun registerNetworkCallback(ctx: Context) {
        val appCtx = ctx.applicationContext
        val mgr = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        connMgr = mgr
        netCtx = appCtx
        try {
            mgr.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback(ctx: Context) {
        try {
            connMgr?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        connMgr = null
        netCtx = null
    }

    // ---------------- telephony callback (signal strength) ----------------

    private var telephonyMgr: android.telephony.TelephonyManager? = null
    private var telephonyListener: Any? = null

    private fun registerTelephonyCallback(ctx: Context) {
        val appCtx = ctx.applicationContext
        val tm = appCtx.getSystemService(Context.TELEPHONY_SERVICE)
            as android.telephony.TelephonyManager
        telephonyMgr = tm
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val callback = object : android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(
                        signalStrength: android.telephony.SignalStrength
                    ) {
                        val data = JSONObject()
                        data.put("level", signalStrength.level.toString())
                        try {
                            val strengths = signalStrength.cellSignalStrengths
                            if (strengths.isNotEmpty()) {
                                data.put("dbm", strengths[0].dbm.toString())
                            }
                        } catch (_: Exception) {
                        }
                        FlowEngine.onEvent(appCtx, "cell_signal", data)
                    }
                }
                tm.registerTelephonyCallback(
                    appCtx.mainExecutor, callback
                )
                telephonyListener = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    @Suppress("DEPRECATION")
                    override fun onSignalStrengthsChanged(
                        signalStrength: android.telephony.SignalStrength?
                    ) {
                        if (signalStrength == null) {
                            return
                        }
                        val data = JSONObject()
                        data.put("level", signalStrength.level.toString())
                        FlowEngine.onEvent(appCtx, "cell_signal", data)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(
                    listener,
                    android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
                telephonyListener = listener
            }
        } catch (_: Exception) {
        }
    }

    private fun unregisterTelephonyCallback(ctx: Context) {
        try {
            val tm = telephonyMgr
            val listener = telephonyListener
            if (tm != null && listener != null) {
                if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    listener is android.telephony.TelephonyCallback
                ) {
                    tm.unregisterTelephonyCallback(listener)
                } else if (listener is android.telephony.PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    tm.listen(
                        listener,
                        android.telephony.PhoneStateListener.LISTEN_NONE
                    )
                }
            }
        } catch (_: Exception) {
        }
        telephonyMgr = null
        telephonyListener = null
    }

    // ---------------- account sync listener ----------------

    private var syncHandle: Any? = null

    private fun registerAccountSync(ctx: Context) {
        try {
            val handle = android.content.ContentResolver.addStatusChangeListener(
                android.content.ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                object : android.content.SyncStatusObserver {
                    override fun onStatusChanged(which: Int) {
                        FlowEngine.onEvent(
                            ctx.applicationContext, "account_sync", JSONObject()
                        )
                    }
                }
            )
            syncHandle = handle
        } catch (_: Exception) {
        }
    }

    private fun unregisterAccountSync(ctx: Context) {
        try {
            val handle = syncHandle
            if (handle is Any) {
                android.content.ContentResolver.removeStatusChangeListener(handle)
            }
        } catch (_: Exception) {
        }
        syncHandle = null
    }

    // ---------------- armed: MediaSession ----------------

    private object mediaSession {
        private var session: android.media.session.MediaSession? = null
        private var running = false

        fun start(ctx: Context) {
            if (running) {
                return
            }
            try {
                val appCtx = ctx.applicationContext
                val session = android.media.session.MediaSession(
                    appCtx, "phonelink-flow-media"
                )
                session.setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onMediaButtonEvent(
                        mediaButtonIntent: Intent
                    ): Boolean {
                        try {
                            @Suppress("DEPRECATION")
                            val event = mediaButtonIntent.getParcelableExtra(
                                Intent.EXTRA_KEY_EVENT
                            ) as? android.view.KeyEvent
                                ?: return super.onMediaButtonEvent(mediaButtonIntent)
                            if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                                return true
                            }
                            val data = JSONObject()
                            data.put(
                                "key",
                                android.view.KeyEvent.keyCodeToString(event.keyCode)
                            )
                            FlowEngine.onEvent(appCtx, "media_button", data)
                            try {
                                val am = appCtx.getSystemService(Context.AUDIO_SERVICE)
                                    as android.media.AudioManager
                                if (am.isWiredHeadsetOn) {
                                    FlowEngine.onEvent(appCtx, "headset_button", data)
                                }
                            } catch (_: Exception) {
                            }
                            return true
                        } catch (_: Exception) {
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                    }
                })
                session.isActive = true
                this.session = session
                running = true
            } catch (_: Exception) {
            }
        }

        fun stop() {
            if (!running) {
                return
            }
            running = false
            try {
                session?.isActive = false
                session?.release()
            } catch (_: Exception) {
            }
            session = null
        }
    }

    // ---------------- armed: mic amplitude sampler ----------------

    private object soundSampler {
        private var recorder: android.media.MediaRecorder? = null
        private var running = false
        private val handler = Handler(Looper.getMainLooper())
        private var poll: Runnable? = null
        private var ctxRef: Context? = null

        fun start(ctx: Context) {
            if (running) {
                return
            }
            try {
                val appCtx = ctx.applicationContext
                if (!PermChecks.has(appCtx, android.Manifest.permission.RECORD_AUDIO)) {
                    FlowStore.log(appCtx, "sound_level needs RECORD_AUDIO")
                    return
                }
                val file = java.io.File(appCtx.cacheDir, "flow_sound.3gp")
                val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.media.MediaRecorder(appCtx)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(
                    android.media.MediaRecorder.OutputFormat.THREE_GPP
                )
                recorder.setAudioEncoder(
                    android.media.MediaRecorder.AudioEncoder.AMR_NB
                )
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                this.recorder = recorder
                ctxRef = appCtx
                running = true
                val runnable = object : Runnable {
                    override fun run() {
                        try {
                            val amp = recorder.maxAmplitude.toFloat()
                            val c = ctxRef
                            if (c != null) {
                                FlowEngine.sensorTick(c, "sound", amp)
                            }
                        } catch (_: Exception) {
                        }
                        if (running) {
                            handler.postDelayed(this, 500L)
                        }
                    }
                }
                poll = runnable
                handler.post(runnable)
            } catch (e: Exception) {
                FlowStore.log(
                    ctx, "sound sampler failed: " + (e.message ?: e.javaClass.simpleName)
                )
                stop()
            }
        }

        fun stop() {
            running = false
            try {
                poll?.let { handler.removeCallbacks(it) }
            } catch (_: Exception) {
            }
            poll = null
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            recorder = null
            ctxRef = null
        }
    }

    // ---------------- armed: Shizuku logcat watcher ----------------

    private object logcatWatcher {
        private var thread: Thread? = null
        private var running = false

        fun start(ctx: Context) {
            if (running) {
                return
            }
            if (!ShizukuHelper.granted()) {
                FlowStore.log(ctx, ctx.getString(R.string.flow_shizuku_needed))
                return
            }
            running = true
            val appCtx = ctx.applicationContext
            val worker = Thread {
                try {
                    val proc = ShizukuHelper.stream("logcat -v brief") ?: return@Thread
                    val reader = proc.inputStream.bufferedReader()
                    var line: String?
                    while (running) {
                        line = try {
                            reader.readLine()
                        } catch (_: Exception) {
                            null
                        } ?: break
                        val text = line ?: break
                        for (trig in enabledFlowTriggerNodes(appCtx)) {
                            if (trig.optString("type") != "log_await") {
                                continue
                            }
                                val pattern = trig.optString("param")
                                val hit = try {
                                    if (pattern.isNotEmpty()) {
                                        Regex(pattern).containsMatchIn(text)
                                    } else {
                                        false
                                    }
                                } catch (_: Exception) {
                                    text.contains(pattern)
                                }
                                if (hit) {
                                    val data = JSONObject()
                                    data.put("line", text)
                                    FlowEngine.onEvent(appCtx, "log_await", data)
                                }
                        }
                    }
                    try {
                        proc.destroy()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
            thread = worker
            worker.start()
        }

        fun stop() {
            running = false
            try {
                thread?.interrupt()
            } catch (_: Exception) {
            }
            thread = null
        }
    }

    // ---------------- armed: Play Services activity recognition ----------------

    private object activityRec {
        private var running = false

        fun start(ctx: Context) {
            if (running) {
                return
            }
            try {
                val availability = com.google.android.gms.common.GoogleApiAvailability
                    .getInstance()
                    .isGooglePlayServicesAvailable(ctx)
                if (availability != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    FlowStore.log(ctx, "activity recognition needs Play Services")
                    return
                }
                val intent = Intent(ctx.applicationContext, FlowSystemReceiver::class.java)
                intent.action = "flow_activity"
                val pending = android.app.PendingIntent.getBroadcast(
                    ctx.applicationContext, 7001, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val client = com.google.android.gms.location.ActivityRecognition
                    .getClient(ctx)
                client.requestActivityUpdates(30_000L, pending)
                running = true
            } catch (e: Exception) {
                FlowStore.log(
                    ctx,
                    "activity recognition failed: " + (e.message ?: e.javaClass.simpleName)
                )
            }
        }

        fun stop(ctx: Context) {
            if (!running) {
                return
            }
            running = false
            try {
                val intent = Intent(ctx.applicationContext, FlowSystemReceiver::class.java)
                intent.action = "flow_activity"
                val pending = android.app.PendingIntent.getBroadcast(
                    ctx.applicationContext, 7001, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
                com.google.android.gms.location.ActivityRecognition
                    .getClient(ctx)
                    .removeActivityUpdates(pending)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- armed: geofence proximity alerts ----------------

    private fun geofenceSync(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager
            val seen = HashSet<String>()
            for (trig in flowTriggerNodes(ctx)) {
                    val type = trig.optString("type")
                    if (type != "geofence_in" && type != "geofence_out") {
                        continue
                    }
                    val parts = trig.optString("param").split("|")
                    if (parts.size != 3) {
                        continue
                    }
                    val lat = parts[0].toDoubleOrNull() ?: continue
                    val lon = parts[1].toDoubleOrNull() ?: continue
                    val radius = parts[2].toFloatOrNull() ?: continue
                    val key = lat.toString() + "|" + lon.toString() + "|" +
                        radius.toString()
                    if (seen.contains(key)) {
                        continue
                    }
                    seen.add(key)
                    val intent = Intent(ctx.applicationContext, FlowSystemReceiver::class.java)
                    intent.action = "flow_proximity"
                    intent.putExtra("lat", lat)
                    intent.putExtra("lon", lon)
                    val code = key.hashCode()
                    val pending = android.app.PendingIntent.getBroadcast(
                        ctx.applicationContext, code, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    try {
                        lm.addProximityAlert(lat, lon, radius, -1L, pending)
                    } catch (e: SecurityException) {
                        FlowStore.log(ctx, "geofence needs location permission")
                    } catch (_: Exception) {
                    }
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- state readers for tick branches ----------------

    fun audioManager(ctx: Context): android.media.AudioManager? {
        return try {
            ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        } catch (_: Exception) {
            null
        }
    }

    fun wifiManager(ctx: Context): android.net.wifi.WifiManager? {
        return try {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
        } catch (_: Exception) {
            null
        }
    }

    fun activeTransport(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return ""
            transportName(caps)
        } catch (_: Exception) {
            ""
        }
    }

    fun tetheredIfaces(ctx: Context): List<String> {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            // getTetheredIfaces is a hidden API: reach it via reflection so
            // the code compiles against the public SDK.
            val m = cm.javaClass.getMethod("getTetheredIfaces")
            @Suppress("UNCHECKED_CAST")
            (m.invoke(cm) as? Array<String>)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hotspotClientCount(ctx: Context): Int {
        if (!ShizukuHelper.granted()) {
            return -1
        }
        return try {
            val out = ShizukuHelper.run("cat /proc/net/arp")
            var count = 0
            for (line in out.lines()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00" &&
                    parts[0] != "IP"
                ) {
                    count++
                }
            }
            count
        } catch (_: Exception) {
            -1
        }
    }

    fun notifImportanceFor(ctx: Context, pkg: String): Int {
        return try {
            val ranking = FlowNotifListener.rankingFor(pkg) ?: return -1
            ranking.importance
        } catch (_: Exception) {
            -1
        }
    }

    /** One calendar instance: id (string), begin/end millis, title. */
    data class CalEvent(val id: String, val begin: Long, val end: Long, val title: String)

    fun calendarEvents(
        ctx: Context,
        titlePart: String
    ): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        try {
            if (!PermChecks.has(ctx, android.Manifest.permission.READ_CALENDAR)) {
                return out
            }
            val now = System.currentTimeMillis()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI
            val startUri = android.net.Uri.parse(
                uri.toString() + "/" + now + "/" + (now + 7L * 24L * 60L * 60L * 1000L)
            )
            val projection = arrayOf(
                android.provider.CalendarContract.Instances.EVENT_ID,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.TITLE
            )
            val cursor = ctx.contentResolver.query(
                startUri, projection, null, null,
                android.provider.CalendarContract.Instances.BEGIN + " ASC"
            ) ?: return out
            try {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(3) ?: ""
                    if (titlePart.isNotEmpty() &&
                        !title.contains(titlePart, ignoreCase = true)
                    ) {
                        continue
                    }
                    out.add(
                        CalEvent(
                            cursor.getLong(0).toString(),
                            cursor.getLong(1), cursor.getLong(2), title
                        )
                    )
                }
            } finally {
                try {
                    cursor.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
