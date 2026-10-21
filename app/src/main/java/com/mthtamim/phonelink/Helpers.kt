package com.mthtamim.phonelink

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object RingHelper {
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    fun start(ctx: Context, maxAll: Boolean = false) {
        stop()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            if (maxAll) {
                // Lost mode: defeat silent/vibrate as far as the OS allows.
                try {
                    am.setStreamVolume(AudioManager.STREAM_RING,
                        am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
                    am.setStreamVolume(AudioManager.STREAM_NOTIFICATION,
                        am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION), 0)
                } catch (_: Exception) { }
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
            stopRunnable = Runnable { stop() }
            handler.postDelayed(stopRunnable!!, 45_000)
        } catch (_: Exception) { }
    }

    fun stop() {
        try { ringtone?.stop() } catch (_: Exception) { }
        ringtone = null
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
    }

    fun isRinging(): Boolean = ringtone?.isPlaying == true
}

object FlashHelper {
    private val handler = Handler(Looper.getMainLooper())
    private var strobeRunnable: Runnable? = null
    private var strobeOn = false

    // v2.13: the system is the source of truth for torch state. A
    // TorchCallback keeps isOn() honest even when another app or the
    // system tile changed the torch, so OFF and TOGGLE can never act
    // on a stale guess.
    @Volatile private var torchState = false
    @Volatile private var callbackReady = false

    private fun ensureCallback(ctx: Context) {
        if (callbackReady) return
        try {
            val cm = ctx.applicationContext.getSystemService(
                Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            cm.registerTorchCallback(object :
                android.hardware.camera2.CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    torchState = enabled
                }
            }, handler)
            callbackReady = true
        } catch (_: Exception) { }
    }

    fun isOn(ctx: Context): Boolean {
        ensureCallback(ctx)
        return torchState
    }

    fun toggle(ctx: Context): Boolean = setTorch(ctx, !isOn(ctx))

    private fun cameraId(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null }
    }

    /** v2.9: torch with a 1..5 strength level (Android 13+); 0 = off. */
    fun setTorchLevel(ctx: Context, level: Int) {
        try {
            if (level <= 0) { setTorch(ctx, false); return }
            if (android.os.Build.VERSION.SDK_INT < 33) { setTorch(ctx, true); return }
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cameraId(ctx) ?: return
            val maxLevel = cm.getCameraCharacteristics(id).get(
                android.hardware.camera2.CameraCharacteristics
                    .FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            if (maxLevel <= 1) { cm.setTorchMode(id, true); return }
            val strength = (level * maxLevel / 5).coerceIn(1, maxLevel)
            cm.turnOnTorchWithStrengthLevel(id, strength)
        } catch (_: Exception) { setTorch(ctx, level > 0) }
    }

    /** Returns false when the torch could not be set (e.g. the camera
     *  stayed busy), so callers can surface the failure instead of
     *  failing silently. One retry covers a momentary camera grab. */
    fun setTorch(ctx: Context, on: Boolean): Boolean {
        ensureCallback(ctx)
        for (attempt in 0..1) {
            try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
                val id = cameraId(ctx) ?: return false
                cm.setTorchMode(id, on)
                torchState = on
                return true
            } catch (_: Exception) {
                if (attempt == 0) try { Thread.sleep(250) }
                    catch (_: Exception) { }
            }
        }
        return false
    }

    fun startStrobe(ctx: Context, seconds: Int) {
        stopStrobe()
        val appCtx = ctx.applicationContext
        val stopAt = System.currentTimeMillis() + seconds * 1000L
        strobeOn = false
        val r = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= stopAt) { stopStrobe(); return }
                strobeOn = !strobeOn
                setTorch(appCtx, strobeOn)
                handler.postDelayed(this, 400)
            }
        }
        strobeRunnable = r
        handler.post(r)
    }

    fun stopStrobe() {
        strobeRunnable?.let { handler.removeCallbacks(it) }
        strobeRunnable = null
        strobeOn = false
    }

    /** Blinks the SOS pattern (··· −−− ···) twice on a background thread. */
    fun blinkSos(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread {
            val unit = 220L
            fun dot() { setTorch(appCtx, true); Thread.sleep(unit); setTorch(appCtx, false); Thread.sleep(unit) }
            fun dash() { setTorch(appCtx, true); Thread.sleep(unit * 3); setTorch(appCtx, false); Thread.sleep(unit) }
            try {
                repeat(2) {
                    dot(); dot(); dot(); Thread.sleep(unit * 2)
                    dash(); dash(); dash(); Thread.sleep(unit * 2)
                    dot(); dot(); dot(); Thread.sleep(unit * 6)
                }
            } catch (_: Exception) { }
            setTorch(appCtx, false)
        }.start()
    }
}

object PhoneInfo {
    fun text(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("মডেল: ").append(Build.MODEL ?: "—")
        sb.append("\nAndroid: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val totalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
            val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
            sb.append(String.format("\nস্টোরেজ: মোট %.1f GB, খালি %.1f GB", totalGb, freeGb))
        } catch (_: Exception) { }
        try {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append(String.format("\nRAM: মোট %.1f GB, খালি %.1f GB",
                mi.totalMem / (1024.0 * 1024 * 1024), mi.availMem / (1024.0 * 1024 * 1024)))
        } catch (_: Exception) { }
        try {
            val bi = ctx.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temp = (bi?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            if (temp > 0) sb.append(String.format("\nব্যাটারি তাপমাত্রা: %.1f°C", temp / 10.0))
        } catch (_: Exception) { }
        try {
            val bi2 = ctx.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val volt = bi2?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (volt > 0) sb.append(String.format("\nব্যাটারি ভোল্টেজ: %.2f V", volt / 1000.0))
            val healthTxt = when (bi2?.getIntExtra(
                android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "ভালো"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "অতিরিক্ত গরম"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "নষ্ট"
                android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "অতিরিক্ত ভোল্টেজ"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "ঠান্ডা"
                else -> ""
            }
            if (healthTxt.isNotEmpty()) sb.append("\nব্যাটারি হেলথ: ").append(healthTxt)
        } catch (_: Exception) { }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "নেই"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                        as android.telephony.TelephonyManager
                    @Suppress("DEPRECATION")
                    val nt = try { tm.dataNetworkType } catch (e: Exception) { 0 }
                    when (nt) {
                        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "মোবাইল (5G)"
                        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "মোবাইল (4G)"
                        android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
                        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "মোবাইল (3G)"
                        0 -> "মোবাইল"
                        else -> "মোবাইল (2G)"
                    }
                }
                else -> "অন্যান্য"
            }
            sb.append("\nনেটওয়ার্ক: ").append(net)
        } catch (_: Exception) { }
        sb.append("\n").append(SystemReceiver.batteryStatusText(ctx))
        return sb.toString()
    }
}

/** Finds OTP/verification codes in message text (context words first). */
object Otp {
    private val contextual = listOf(
        Regex("(?i)(?:otp|code|pin|password|verification|verify|কোড|পিন|যাচাই)[^0-9]{0,24}([0-9]{4,8})"),
        Regex("([0-9]{4,8})[^0-9]{0,24}(?:otp|code|pin|কোড|পিন|যাচাই)")
    )
    private val standalone = Regex("\\b([0-9]{4,8})\\b")

    fun find(text: String): String {
        for (re in contextual) {
            val m = re.find(text)
            if (m != null) return m.groupValues[1]
        }
        // Fall back to a bare code only in short, code-like messages.
        if (text.length <= 200) {
            val m = standalone.find(text)
            if (m != null) return m.groupValues[1]
        }
        return ""
    }
}

object LocationHelper {
    /**
     * Fetches one location fix and reports it through Relay.
     * Uses the best last-known fix immediately if a fresh one is not
     * available within ~15 seconds — a slightly old fix beats silence.
     */
    fun fetchAndSend(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Relay.send(appCtx, Relay.TYPE_LOCATION, "লোকেশন পাওয়া যায়নি",
                "Sender ফোনে লোকেশন Permission দেওয়া নেই।")
            return
        }
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (_: Exception) { }
        }

        var sent = false
        fun sendLoc(loc: Location, note: String) {
            if (sent) return
            sent = true
            val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            Relay.send(appCtx, Relay.TYPE_LOCATION, "📍 ফোনের লোকেশন",
                "Lat: ${loc.latitude}\nLng: ${loc.longitude}\nAccuracy: ${loc.accuracy.toInt()}m\n$note\nম্যাপ: $link")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = Executors.newSingleThreadExecutor()
            var provider: String? = null
            for (p in providers) { try { if (lm.isProviderEnabled(p)) { provider = p; break } } catch (_: Exception) {} }
            if (provider != null) {
                try {
                    lm.getCurrentLocation(provider, null, executor) { loc ->
                        if (loc != null) sendLoc(loc, "লাইভ GPS ফিক্স")
                        else best?.let { sendLoc(it, "সর্বশেষ জানা লোকেশন (লাইভ ফিক্স পাওয়া যায়নি)") }
                            ?: Relay.send(appCtx, Relay.TYPE_LOCATION, "লোকেশন পাওয়া যায়নি",
                                "GPS বন্ধ বা সিগন্যাল নেই।")
                    }
                    // Fallback timer: if no fix in 18s, send best-known.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!sent) best?.let { sendLoc(it, "সর্বশেষ জানা লোকেশন") }
                    }, 18_000)
                    return
                } catch (_: Exception) { }
            }
        }
        best?.let { sendLoc(it, "সর্বশেষ জানা লোকেশন") }
            ?: Relay.send(appCtx, Relay.TYPE_LOCATION, "লোকেশন পাওয়া যায়নি",
                "GPS বন্ধ বা কোনো লোকেশন রেকর্ড নেই।")
    }

    /** Raw coordinates for the geofence: best last-known fix, or null. */
    fun fetchCoords(ctx: Context, cb: (Double, Double) -> Unit, onFail: () -> Unit) {
        val appCtx = ctx.applicationContext
        if (appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) { onFail(); return }
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER)) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || loc.time > best!!.time) best = loc
                } catch (_: Exception) { }
            }
            val b = best
            if (b != null) cb(b.latitude, b.longitude) else onFail()
        } catch (_: Exception) { onFail() }
    }
}
