package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Tracks call state transitions and forwards:
 * incoming ringing, answered, missed, ended (with duration), outgoing.
 */
class CallReceiver : BroadcastReceiver() {

    private fun extraFor(ctx: Context, number: String?, ringing: Boolean = false): org.json.JSONObject {
        val e = org.json.JSONObject()
        if (!number.isNullOrBlank()) e.put("number", number)
        if (Config.isPriorityNumber(ctx, number)) e.put("priority", 1)
        if (ringing) e.put("ringing", 1)
        return e
    }

    private fun star(ctx: Context, number: String?): String =
        if (Config.isPriorityNumber(ctx, number)) "⭐ " else ""

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        // v2.20.1: call triggers are LOCAL automation — they used to sit
        // behind this sender gate, so on a Receiver phone no call rule
        // ever fired. Only the peer forward stays gated; the state
        // tracking, the Automation.onEvent calls and the user-enabled
        // missed-call auto-reply below now run on every role.
        val fwd = Config.isSender(ctx) && Config.fwdCalls(ctx)

        when (intent.action) {
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                outgoingNumber = number
                if (fwd) Relay.send(ctx, Relay.TYPE_CALL, "${star(ctx, number)}আউটগোয়িং কল",
                    Contacts.labelForNumber(ctx, number), extraFor(ctx, number))
                Automation.onEvent(ctx, "call_outgoing", org.json.JSONObject()
                    .put("title", Contacts.labelForNumber(ctx, number))
                    .put("from", number ?: ""))
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (lastState != state) {
                            incomingNumber = number ?: incomingNumber
                            callStart = System.currentTimeMillis()
                            answered = false
                            if (fwd) Relay.send(ctx, Relay.TYPE_CALL,
                                "${star(ctx, incomingNumber)}ইনকামিং কল বাজছে",
                                Contacts.labelForNumber(ctx, incomingNumber),
                                extraFor(ctx, incomingNumber, ringing = true))
                            Automation.onEvent(ctx, "call_incoming", org.json.JSONObject()
                                .put("title", Contacts.labelForNumber(ctx, incomingNumber))
                                .put("from", incomingNumber ?: ""))
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        if (lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                            answered = true
                            callStart = System.currentTimeMillis()
                        }
                        // v2.16: Call Active as a TRIGGER (it used to
                        // exist only as a constraint).
                        Automation.onEvent(ctx, "call_active", org.json.JSONObject()
                            .put("title", Contacts.labelForNumber(ctx,
                                incomingNumber ?: outgoingNumber))
                            .put("from", incomingNumber ?: outgoingNumber ?: ""))
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        if (lastState == TelephonyManager.EXTRA_STATE_RINGING && !answered) {
                            if (fwd) Relay.send(ctx, Relay.TYPE_CALL,
                                "${star(ctx, incomingNumber)}মিসড কল",
                                Contacts.labelForNumber(ctx, incomingNumber),
                                extraFor(ctx, incomingNumber))
                            Automation.onEvent(ctx, "call_missed", org.json.JSONObject()
                                .put("title", Contacts.labelForNumber(ctx, incomingNumber))
                                .put("from", incomingNumber ?: ""))
                            maybeAutoReply(ctx, incomingNumber)
                        } else if (lastState == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                            val secs = ((System.currentTimeMillis() - callStart) / 1000).coerceAtLeast(0)
                            val who = incomingNumber ?: outgoingNumber
                            if (fwd) Relay.send(ctx, Relay.TYPE_CALL, "${star(ctx, who)}কল শেষ",
                                "${Contacts.labelForNumber(ctx, who)} — সময়: ${secs / 60} মিনিট ${secs % 60} সেকেন্ড",
                                extraFor(ctx, who))
                            Automation.onEvent(ctx, "call_ended", org.json.JSONObject()
                                .put("title", Contacts.labelForNumber(ctx, who))
                                .put("from", who ?: ""))
                        }
                        incomingNumber = null
                        outgoingNumber = null
                        answered = false
                    }
                }
                lastState = state
            }
        }
    }

    /** One auto-reply SMS per caller per 10 minutes, when enabled. */
    private fun maybeAutoReply(ctx: Context, number: String?) {
        if (number.isNullOrBlank() || !Config.autoReplyOn(ctx)) return
        val text = Config.autoReplyText(ctx)
        if (text.isEmpty()) return
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val now = System.currentTimeMillis()
            synchronized(autoReplyLock) {
                val last = autoReplyAt[number] ?: 0L
                if (now - last < 10 * 60 * 1000L) return
                autoReplyAt[number] = now
            }
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault()
                .sendTextMessage(number, null, text.take(300), null, null)
        } catch (_: Exception) { }
    }

    companion object {
        private var lastState: String? = null
        private var incomingNumber: String? = null
        private var outgoingNumber: String? = null
        private var callStart: Long = 0L
        private var answered: Boolean = false
        private val autoReplyLock = Any()
        private val autoReplyAt = HashMap<String, Long>()
    }
}
