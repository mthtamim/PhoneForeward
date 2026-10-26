package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

class FlowSmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            for (m in msgs) {
                val data = JSONObject()
                data.put("title", m.originatingAddress ?: "")
                data.put("body", m.messageBody ?: "")
                data.put("app", "sms")
                FlowEngine.onEvent(ctx, "sms_received", data)
            }
        } catch (_: Exception) {
        }
    }
}
