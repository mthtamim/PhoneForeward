package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/**
 * PhoneLink v2.24.0 (Lab merge) background service: hosts Flow
 * trigger evaluation while the app is closed, so flows fire
 * without the Automation screen open. Started by the Flows tab
 * service switch (and re-armed on boot, only if the switch was
 * left ON). When the switch is OFF the service does not run at
 * all — no silent background work. The 60-second tick and the
 * dynamic screen/wifi receivers live ONLY here; the Automation
 * screen never starts the trigger hub, and manual Run-now needs
 * no hub. Foreground notification id 101 (LinkService uses 1,
 * SnapService 2, ScreenShareService 42).
 */
class FlowService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                // v1.4.3 heartbeat: stamp every tick so Home can
                // tell a live service from a killed one.
                FlowStore.setServiceLastTick(this@FlowService, System.currentTimeMillis())
                FlowEngine.tickForeground(this@FlowService)
                FlowTriggers.checkForegroundApp(this@FlowService)
                FlowTriggers.maybeArm(this@FlowService)
            } catch (_: Exception) {
            }
            if (running) {
                handler.postDelayed(this, 60_000L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON ->
                    FlowEngine.onEvent(ctx, "screen_on", JSONObject())
                Intent.ACTION_SCREEN_OFF ->
                    FlowEngine.onEvent(ctx, "screen_off", JSONObject())
                Intent.ACTION_USER_PRESENT ->
                    FlowEngine.onEvent(ctx, "screen_unlocked", JSONObject())
                else -> {
                }
            }
        }
    }

    private val wifiReceiver = FlowWifiReceiver()

    override fun onCreate() {
        super.onCreate()
        startForeground(101, buildNotification())
        val screenFilter = IntentFilter()
        screenFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF)
        screenFilter.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenReceiver, screenFilter)
        val wifiFilter = IntentFilter()
        wifiFilter.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
        wifiFilter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(wifiReceiver, wifiFilter)
        // v1.4.0: the trigger hub (sensors, observers, callbacks)
        // runs while the service runs.
        try {
            FlowTriggers.start(this)
        } catch (_: Exception) {
        }
        running = true
        FlowStore.setServiceLastTick(this, System.currentTimeMillis())
        handler.post(tickRunnable)
        FlowStore.log(this, getString(R.string.flow_service_started))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent: re-starting an already-running service just
        // re-enters here; the loop and receivers live in onCreate.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
        try {
            FlowTriggers.stop(this)
        } catch (_: Exception) {
        }
        // v1.4.3: clearing the stamp is the "not running" signal;
        // a stale stamp (no tick for a while) means the same.
        FlowStore.setServiceLastTick(this, 0L)
        FlowStore.log(this, getString(R.string.flow_service_stopped))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                FlowEngine.NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notif_channel_desc)
            nm.createNotificationChannel(channel)
        }
        val openIntent = Intent(this, AutomationActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, FlowEngine.NOTIF_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle(getString(R.string.flow_service_title))
        builder.setContentText(getString(R.string.flow_service_text))
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        builder.setContentIntent(pending)
        builder.setOngoing(true)
        return builder.build()
    }
}
