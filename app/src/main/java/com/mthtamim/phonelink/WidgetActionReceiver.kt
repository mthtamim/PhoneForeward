package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** v2.7: home-screen widget quick actions — ring the peer / ask its location. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            "pl_widget_ring" -> {
                Relay.sendCommand(ctx, "RING")
                Toast.makeText(ctx, ctx.getString(R.string.widget_ring_sent),
                    Toast.LENGTH_SHORT).show()
            }
            "pl_widget_loc" -> {
                Relay.sendCommand(ctx, "LOCATION")
                Toast.makeText(ctx, ctx.getString(R.string.widget_loc_sent),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
}
