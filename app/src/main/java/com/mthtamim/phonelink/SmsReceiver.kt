package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val ctx = context.applicationContext

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress ?: "অজানা"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return

        // Offline fallback: a trigger SMS controls this phone with no
        // internet at all, and the answer comes back as a plain SMS.
        if (Config.smsTriggerOn(ctx) && handleTrigger(ctx, sender, body)) return

        if (!Config.isSender(ctx) || !Config.fwdSms(ctx)) return
        if (Config.containsBlockedWord(ctx, body)) return

        val who = Contacts.labelForNumber(ctx, sender)
        val priority = Config.isPriorityNumber(ctx, sender)
        val alertHit = Config.containsAlertWord(ctx, body)
        val extra = JSONObject().put("number", sender)
        if (priority || alertHit) extra.put("priority", 1)
        val star = (if (alertHit) "🚨 " else "") + (if (priority) "⭐ " else "")
        val code = Otp.find(body)
        if (code.isNotEmpty()) {
            extra.put("code", code)
            Relay.send(ctx, Relay.TYPE_SMS, "$star🔑 SMS — $who", body, extra)
        } else {
            Relay.send(ctx, Relay.TYPE_SMS, "${star}SMS — $who", body, extra)
        }
        Automation.onEvent(ctx, "sms_received", JSONObject()
            .put("title", who).put("body", body).put("from", sender))
    }

    /**
     * Trigger words (start of the SMS): #ring, #loc / #location, #battery —
     * also accepted as "phonelink ring" etc. When a priority list exists,
     * only priority numbers may trigger; otherwise any sender can.
     * Returns true when the SMS was a trigger and has been handled.
     */
    private fun handleTrigger(ctx: Context, sender: String, body: String): Boolean {
        if (Config.priorityRaw(ctx).isNotBlank() && !Config.isPriorityNumber(ctx, sender)) {
            return false
        }
        val t = body.trim().lowercase()
        val cmd = when {
            t.startsWith("#ring") || t.startsWith("phonelink ring") -> "ring"
            t.startsWith("#loc") || t.startsWith("phonelink loc") -> "loc"
            t.startsWith("#battery") || t.startsWith("phonelink battery") -> "battery"
            else -> return false
        }
        when (cmd) {
            "ring" -> {
                RingHelper.start(ctx, maxAll = true)
                replySms(ctx, sender, "PhoneLink: রিং বাজানো হচ্ছে (৪৫ সেকেন্ড)।")
            }
            "battery" -> replySms(ctx, sender,
                "PhoneLink: ${SystemReceiver.batteryStatusText(ctx)}")
            "loc" -> LocationHelper.fetchCoords(ctx,
                { lat, lng -> replySms(ctx, sender,
                    "PhoneLink লোকেশন: https://maps.google.com/?q=$lat,$lng") },
                { replySms(ctx, sender, "PhoneLink: লোকেশন পাওয়া যায়নি (GPS/permission দেখো)।") })
        }
        return true
    }

    private fun replySms(ctx: Context, number: String, text: String) {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) return
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(number, null, text.take(155), null, null)
        } catch (_: Exception) { }
    }
}
