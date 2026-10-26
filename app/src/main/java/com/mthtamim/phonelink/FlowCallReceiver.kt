package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

class FlowCallReceiver : BroadcastReceiver() {
    companion object {
        @Volatile
        private var wasRinging = false

        @Volatile
        private var wasOffhook = false
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    val num = intent?.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
                    val data = JSONObject()
                    data.put("title", num)
                    data.put("body", num)
                    FlowEngine.onEvent(ctx, "call_outgoing", data)
                }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                    val num = intent?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                    val data = JSONObject()
                    data.put("title", num)
                    data.put("body", num)
                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            wasRinging = true
                            FlowEngine.onEvent(ctx, "call_incoming", data)
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            wasOffhook = true
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            // v1.4.0: IDLE after RINGING without
                            // OFFHOOK is a missed call. call_ended
                            // keeps its old meaning (any call end).
                            // Caveat: process death between RINGING
                            // and IDLE loses the flags, so a missed
                            // call then reports only call_ended.
                            if (wasRinging && !wasOffhook) {
                                FlowEngine.onEvent(ctx, "call_missed", data)
                            }
                            wasRinging = false
                            wasOffhook = false
                            FlowEngine.onEvent(ctx, "call_ended", data)
                            // v1.5.0: redial sessions decide from
                            // the call log after every call end.
                            // PhoneLink v2.24.0: NO LabRedial hook here.
                            // Flow redial_control sessions are PhoneLink
                            // Redial sessions, driven by PhoneLink's own
                            // engine and receivers — never from Flow.
                        }
                        else -> {
                        }
                    }
                }
                else -> {
                }
            }
        } catch (_: Exception) {
        }
    }
}
