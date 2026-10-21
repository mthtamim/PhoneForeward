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

    fun start(ctx: Context) {
        stop()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
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
