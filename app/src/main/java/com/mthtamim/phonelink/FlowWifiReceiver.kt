package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import org.json.JSONObject

class FlowWifiReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"") ?: ""
            if (info != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                val data = JSONObject()
                data.put("ssid", ssid)
                FlowEngine.onEvent(ctx, "wifi_connected", data)
            } else {
                FlowEngine.onEvent(ctx, "wifi_disconnected", JSONObject())
            }
        } catch (_: Exception) {
        }
    }
}
