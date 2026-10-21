package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val ctx = context.applicationContext
        if (!Config.isSender(ctx) || !Config.fwdSms(ctx)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress ?: "অজানা"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return
        if (Config.containsBlockedWord(ctx, body)) return

        val who = Contacts.labelForNumber(ctx, sender)
        val code = Otp.find(body)
        if (code.isNotEmpty()) {
            val extra = org.json.JSONObject().put("code", code)
            Relay.send(ctx, Relay.TYPE_SMS, "🔑 SMS — $who", body, extra)
        } else {
            Relay.send(ctx, Relay.TYPE_SMS, "SMS — $who", body)
        }
    }
}
