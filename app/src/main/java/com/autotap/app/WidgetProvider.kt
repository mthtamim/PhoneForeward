package com.autotap.app
import com.mthtamim.phonelink.R

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: shows the selected script and toggles it with one
 * tap, without opening the app. Tap = start when idle, stop when running.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(ctx))
    }

    companion object {
        fun buildViews(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.autotap_widget_layout)
            val script = Store.selectedScript(ctx)
            views.setTextViewText(R.id.widget_title, "AutoTap - ${script?.name ?: "script নেই"}")
            views.setTextViewText(
                R.id.widget_action,
                if (RunnerService.running) "চলছে - থামাতে ট্যাপ করো" else "চালু করতে ট্যাপ করো"
            )
            val intent = Intent(ctx, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_TOGGLE)
            val pi = PendingIntent.getBroadcast(
                ctx, 9901, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }

        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            for (id in ids) manager.updateAppWidget(id, buildViews(ctx))
        }
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (RunnerService.running) {
            RunnerService.sendCmd(context, RunnerService.CMD_STOP)
        } else {
            val script = Store.selectedScript(context)
            if (script != null && script.steps.isNotEmpty() &&
                TapAccessibilityService.isEnabled()
            ) {
                RunnerService.start(context, script.id)
            } else {
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(open)
            }
        }
        WidgetProvider.refresh(context)
    }

    companion object {
        const val ACTION_TOGGLE = "com.autotap.app.WIDGET_TOGGLE"
    }
}
