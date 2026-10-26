package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** PhoneLink v2.24.0: rich_notify button presses are a
 *  trigger event (notify_button) as well as a log line.
 *  flow_run_flow runs one flow by id (used by flow exports /
 *  shortcuts that embed the id); there is no Flow widget or
 *  QS tile in this merge, so no widget_click event is fired. */
class FlowActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            if (intent?.action == "flow_notify_button") {
                val label = intent?.getStringExtra("label") ?: ""
                FlowStore.log(ctx, "notify button: " + label)
                val data = org.json.JSONObject()
                data.put("title", label)
                data.put("body", label)
                FlowEngine.onEvent(ctx, "notify_button", data)
            }
            if (intent?.action == "flow_run_flow") {
                val id = intent?.getStringExtra("flow_id") ?: ""
                // No widget_click event here: no Flow widget ships
                // in this merge, and widget_click is not in
                // FlowCatalog, so firing it could only confuse.
                val flow = if (id.isEmpty()) null else FlowStore.loadFlow(ctx, id)
                if (flow != null) {
                    FlowEngine.runFlow(ctx, flow)
                } else {
                    FlowStore.log(ctx, "flow not found: " + id)
                }
            }
        } catch (_: Exception) {
        }
    }
}
