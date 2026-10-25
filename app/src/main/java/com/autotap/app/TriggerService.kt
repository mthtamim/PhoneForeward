package com.autotap.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder

/**
 * Hosts the v5 environment triggers (WiFi / battery / headset / screen-on)
 * for scripts that configured them. Started from MainActivity and at boot;
 * best-effort - Android may kill it, reopening the app restarts it.
 * Every trigger is debounced and never fires while a script is running.
 */
class TriggerService : Service() {

    private val lastFired = mutableMapOf<String, Long>()
    private var lastBatteryLevel = -1
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceivers()
        registerWifiCallback()
        instance = this
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(headsetReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        try {
            networkCallback?.let {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun tryStart(script: Script, debounceMs: Long = 20000) {
        if (RunnerService.running) return
        if (script.steps.isEmpty()) return
        if (!TapAccessibilityService.isEnabled()) return
        val now = System.currentTimeMillis()
        val last = lastFired[script.id] ?: 0L
        if (now - last < debounceMs) return
        lastFired[script.id] = now
        RunnerService.start(this, script.id)
    }

    private fun scripts(): List<Script> = try { Store.getScripts(this) } catch (e: Exception) { emptyList() }

    // ---- battery ----
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val prev = lastBatteryLevel
            lastBatteryLevel = pct
            if (prev < 0) return
            for (s in scripts()) {
                val target = s.triggerBatteryLevel
                if (target < 0) continue
                val crossed = if (s.triggerBatteryBelow) {
                    prev > target && pct <= target
                } else {
                    prev < target && pct >= target
                }
                if (crossed) tryStart(s, 60000)
            }
        }
    }

    // ---- headset ----
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getIntExtra("state", 0) ?: 0
            if (state == 1) {
                for (s in scripts()) if (s.triggerHeadset) tryStart(s)
            }
        }
    }

    // ---- screen on / unlock ----
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            for (s in scripts()) if (s.triggerScreenOn) tryStart(s, 30000)
        }
    }

    private fun registerReceivers() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(headsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    // ---- WiFi ----
    private fun registerWifiCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ssid = currentSsid()
                    if (ssid.isEmpty()) return
                    for (s in scripts()) {
                        if (s.triggerWifi.isNotEmpty() && s.triggerWifi.equals(ssid, ignoreCase = true)) {
                            tryStart(s, 60000)
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) { /* WiFi trigger is best-effort */ }
    }

    private fun currentSsid(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val raw = info?.ssid ?: ""
            raw.trim('"').trim()
        } catch (e: Exception) { "" }
    }

    companion object {
        @Volatile
        var instance: TriggerService? = null
            private set

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, TriggerService::class.java))
            } catch (e: Exception) { /* background start may be restricted */ }
        }
    }
}
