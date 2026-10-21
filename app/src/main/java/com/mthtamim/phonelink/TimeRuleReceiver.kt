package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v2.11.3: alarm-clock entry point for time_at automation rules, so a
 * "16:52" rule fires at 16:52 instead of at the next heartbeat tick.
 */
class TimeRuleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("ruleId") ?: return
        Automation.fireTimeRule(context.applicationContext, id)
    }
}
