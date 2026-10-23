package com.mthtamim.phonelink

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import java.util.Locale

/**
 * v2.18: one-tap home-screen widget that locks the screen through
 * Device Admin (AdminReceiver.lockNow — the same shared helper as the
 * lock_screen automation action). No configuration: a tap locks
 * immediately. When Device Admin is not active, WidgetActionReceiver
 * explains and opens Permissions.
 */
class LockAdminWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(context))
    }

    companion object {
        private fun localized(ctx: Context): Context {
            val lang = Config.language(ctx)
            if (lang == "system") return ctx
            val cfg = Configuration(ctx.resources.configuration)
            cfg.setLocale(Locale(lang))
            return ctx.createConfigurationContext(cfg)
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val lc = localized(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_lock_layout)
            views.setTextViewText(R.id.wLockTitle, lc.getString(R.string.widget_lock_title))
            views.setTextViewText(R.id.wLockSub, lc.getString(R.string.widget_lock_admin_sub))
            val pi = PendingIntent.getBroadcast(ctx, 22,
                Intent(ctx, WidgetActionReceiver::class.java).setAction("pl_widget_lock_admin"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wLockRoot, pi)
            return views
        }
    }
}
