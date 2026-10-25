package com.autotap.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * External automation API (Tasker / other apps): broadcasts
 *   com.autotap.app.api.START  (extras: "id" or "name")
 *   com.autotap.app.api.STOP
 * Disabled by default; the user turns it on in Settings.
 */
class ApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Store.apiEnabled(context)) return
        when (intent?.action) {
            ACTION_START -> {
                if (RunnerService.running) return
                val id = intent.getStringExtra("id")
                val name = intent.getStringExtra("name")
                val script = Store.getScripts(context).firstOrNull {
                    (id != null && it.id == id) || (name != null && it.name == name)
                } ?: return
                if (script.steps.isNotEmpty() && TapAccessibilityService.isEnabled()) {
                    RunnerService.start(context, script.id)
                }
            }
            ACTION_STOP -> RunnerService.sendCmd(context, RunnerService.CMD_STOP)
        }
    }

    companion object {
        const val ACTION_START = "com.autotap.app.api.START"
        const val ACTION_STOP = "com.autotap.app.api.STOP"
    }
}

/** Starts scripts when a chosen app's notification arrives. Needs the
 *  notification-access permission the user grants in system settings. */
class AutoTapNotificationListener : NotificationListenerService() {

    private val lastFired = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (RunnerService.running) return
        try {
            val pkg = sbn.packageName ?: return
            val extras = sbn.notification?.extras
            val text = listOf(
                extras?.getCharSequence("android.title")?.toString() ?: "",
                extras?.getCharSequence("android.text")?.toString() ?: ""
            ).joinToString(" ")
            val now = System.currentTimeMillis()
            for (script in Store.getScripts(this)) {
                if (script.triggerNotifPkg.isEmpty()) continue
                if (script.triggerNotifPkg != pkg) continue
                if (script.triggerNotifText.isNotEmpty() &&
                    !text.contains(script.triggerNotifText, ignoreCase = true)
                ) continue
                val last = lastFired[script.id] ?: 0L
                if (now - last < 15000) continue
                lastFired[script.id] = now
                if (script.steps.isNotEmpty() && TapAccessibilityService.isEnabled()) {
                    RunnerService.start(this, script.id)
                }
            }
        } catch (e: Exception) { /* listener must never crash */ }
    }
}
