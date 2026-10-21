package com.mthtamim.phonelink

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.11: home-screen widget showing the newest inbox item and today's
 * count. Refreshed from the service whenever a new item lands.
 */
class InboxWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        fun build(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_inbox_layout)
            val items = History.all(ctx)
            val latest = items.firstOrNull()
            views.setTextViewText(R.id.wInTitle,
                latest?.title ?: ctx.getString(R.string.inbox_empty))
            views.setTextViewText(R.id.wInBody, latest?.body?.take(90) ?: "")
            val df = SimpleDateFormat("hh:mm a", Locale.getDefault())
            views.setTextViewText(R.id.wInMeta,
                ctx.getString(R.string.widget_inbox_meta,
                    History.todayCount(ctx),
                    latest?.let { df.format(Date(it.time)) } ?: "—"))
            val pi = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wInRoot, pi)
            return views
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(ctx, InboxWidgetProvider::class.java))
                for (id in ids) mgr.updateAppWidget(id, build(ctx))
            } catch (_: Exception) { }
        }
    }
}
