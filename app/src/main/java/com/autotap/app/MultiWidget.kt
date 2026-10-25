package com.autotap.app
import com.mthtamim.phonelink.R

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Multi-script widget: up to four favorite scripts as separate buttons on
 * the home screen. Tapping one starts it (or stops the running one).
 */
class MultiWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(ctx))
    }

    companion object {
        private val slots = intArrayOf(R.id.multi_slot1, R.id.multi_slot2, R.id.multi_slot3, R.id.multi_slot4)

        fun buildViews(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.autotap_widget_multi_layout)
            val favorites = Store.getScripts(ctx).filter { it.favorite }.take(4)
            for (i in slots.indices) {
                val script = favorites.getOrNull(i)
                if (script == null) {
                    views.setViewVisibility(slots[i], View.GONE)
                } else {
                    views.setViewVisibility(slots[i], View.VISIBLE)
                    views.setTextViewText(
                        slots[i],
                        if (RunnerService.running && RunnerService.activeScriptId == script.id) {
                            "■ " + script.name
                        } else {
                            "▶ " + script.name
                        }
                    )
                    val intent = Intent(ctx, MultiWidgetActionReceiver::class.java)
                        .setAction(MultiWidgetActionReceiver.ACTION_RUN)
                        .putExtra(MultiWidgetActionReceiver.EXTRA_ID, script.id)
                    val pi = PendingIntent.getBroadcast(
                        ctx, 9800 + i, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(slots[i], pi)
                }
            }
            return views
        }

        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MultiWidgetProvider::class.java))
            for (id in ids) manager.updateAppWidget(id, buildViews(ctx))
        }
    }
}

class MultiWidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return
        if (RunnerService.running && RunnerService.activeScriptId == id) {
            RunnerService.sendCmd(context, RunnerService.CMD_STOP)
        } else if (!RunnerService.running) {
            val script = Store.getScript(context, id)
            if (script != null && script.steps.isNotEmpty() && TapAccessibilityService.isEnabled()) {
                RunnerService.start(context, script.id)
            }
        }
        MultiWidgetProvider.refresh(context)
    }

    companion object {
        const val ACTION_RUN = "com.autotap.app.MULTI_WIDGET_RUN"
        const val EXTRA_ID = "script_id"
    }
}
