package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v2.9: buttons attached by the rich_notify action. A tap dismisses the
 * notification and fires the "notify_button" automation event with the
 * button label as its title, so rules can chain off it.
 */
class NotifyButtonReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: return
        val nid = intent.getIntExtra("nid", 0)
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager).cancel(nid)
        } catch (_: Exception) { }
        Automation.onEvent(ctx.applicationContext, "notify_button",
            JSONObject().put("title", label).put("body", label))
    }
}
