package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class FlowBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // v1.2.0: re-arm the background service ONLY if the user
            // left the Flows switch ON. Never auto-start when left
            // OFF. Some devices block background service starts
            // after boot; opening the Automation screen's Flows tab
            // once re-asserts it via the service switch.
            if (FlowStore.flowServiceOn(ctx)) {
                try {
                    ctx.startForegroundService(Intent(ctx, FlowService::class.java))
                } catch (_: Exception) {
                }
            }
            FlowEngine.onEvent(ctx, "boot", JSONObject())
        }
    }
}
