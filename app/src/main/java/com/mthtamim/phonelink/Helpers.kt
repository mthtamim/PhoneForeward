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

    private fun cameraId(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null }
    }

    fun setTorch(ctx: Context, on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cameraId(ctx) ?: return
            cm.setTorchMode(id, on)
        } catch (_: Exception) { }
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
}

object PhoneInfo {
    fun text(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("মডেল: ").append(Build.MODEL ?: "—")
        sb.append("\nAndroid: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024 * 1024)
            val free = stat.availableBytes / (1024 * 1024)
            sb.append("\nস্টোরেজ: মোট ${total} MB, খালি ${free} MB")
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
}
