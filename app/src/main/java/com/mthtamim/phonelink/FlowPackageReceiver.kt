package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class FlowPackageReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            val event = when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED -> "app_installed"
                Intent.ACTION_PACKAGE_REMOVED -> "app_removed"
                Intent.ACTION_PACKAGE_REPLACED -> "app_updated"
                else -> return
            }
            val data = JSONObject()
            data.put("pkg", pkg)
            FlowEngine.onEvent(ctx, event, data)
        } catch (_: Exception) {
        }
    }
}
