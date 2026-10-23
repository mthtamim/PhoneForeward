package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** v2.7: home-screen widget quick actions — ring the peer / ask its location.
 *  v2.18: the two screen-lock widgets route here too (a11y / admin). */
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
            "pl_widget_lock_a11y" -> {
                // Same instance route as the global_key automation action.
                val locked = try { A11yService.globalKey("lock_screen") }
                    catch (_: Exception) { false }
                if (!locked) {
                    Toast.makeText(ctx, ctx.getString(R.string.widget_lock_need_a11y),
                        Toast.LENGTH_LONG).show()
                    openPermissions(ctx)
                }
            }
            "pl_widget_lock_admin" -> {
                // Same shared helper as the lock_screen automation action.
                val locked = try { AdminReceiver.lockNow(ctx) }
                    catch (_: Exception) { false }
                if (!locked) {
                    Toast.makeText(ctx, ctx.getString(R.string.widget_lock_need_admin),
                        Toast.LENGTH_LONG).show()
                    openPermissions(ctx)
                }
            }
        }
    }

    /** Grants live in exactly one home: the Permissions screen. */
    private fun openPermissions(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }
}
