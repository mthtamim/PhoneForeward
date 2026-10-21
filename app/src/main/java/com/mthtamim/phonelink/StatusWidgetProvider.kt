package com.mthtamim.phonelink

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import java.util.Locale

/** Home-screen widget: the other phone's name, battery and last-seen time. */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(context))
    }

    companion object {
        fun refresh(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx)
                val component = ComponentName(ctx, StatusWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids.isEmpty()) return
                val views = buildViews(ctx)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (_: Exception) { }
        }

        private fun localized(ctx: Context): Context {
            val lang = Config.language(ctx)
            if (lang == "system") return ctx
            val cfg = Configuration(ctx.resources.configuration)
            cfg.setLocale(Locale(lang))
            return ctx.createConfigurationContext(cfg)
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val lc = localized(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_layout)
            val name = Config.lastSenderName(ctx).ifEmpty { "—" }
            views.setTextViewText(R.id.wPeerName, name)
            val battery = Config.lastSenderBattery(ctx)
            views.setTextViewText(R.id.wPeerBattery,
                if (battery >= 0) lc.getString(R.string.widget_battery, battery)
                else lc.getString(R.string.widget_battery_unknown))
            val seen = Config.lastSenderSeen(ctx)
            views.setTextViewText(R.id.wPeerSeen, if (seen == 0L) lc.getString(R.string.sender_never_seen)
                else {
                    val mins = (System.currentTimeMillis() - seen) / 60000
                    val whenText = when {
                        mins < 1 -> lc.getString(R.string.time_just_now)
                        mins < 60 -> lc.getString(R.string.time_mins_ago, mins)
                        mins < 1440 -> lc.getString(R.string.time_hours_ago, mins / 60)
                        else -> lc.getString(R.string.time_days_ago, mins / 1440)
                    }
                    lc.getString(R.string.widget_last_seen, whenText)
                })
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            val ringPi = PendingIntent.getBroadcast(ctx, 11,
                Intent(ctx, WidgetActionReceiver::class.java).setAction("pl_widget_ring"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wBtnRing, ringPi)
            val locPi = PendingIntent.getBroadcast(ctx, 12,
                Intent(ctx, WidgetActionReceiver::class.java).setAction("pl_widget_loc"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wBtnLoc, locPi)
            return views
        }
    }
}
