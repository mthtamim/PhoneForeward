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
 * v2.18: one-tap home-screen widget that locks the screen through the
 * Accessibility service (GLOBAL_ACTION_LOCK_SCREEN, via the same
 * A11yService.globalKey route as the global_key automation action).
 * No configuration: a tap locks immediately. When the service is not
 * running, WidgetActionReceiver explains and opens Permissions.
 */
class LockA11yWidgetProvider : AppWidgetProvider() {

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
            views.setTextViewText(R.id.wLockSub, lc.getString(R.string.widget_lock_a11y_sub))
            val pi = PendingIntent.getBroadcast(ctx, 21,
                Intent(ctx, WidgetActionReceiver::class.java).setAction("pl_widget_lock_a11y"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wLockRoot, pi)
            return views
        }
    }
}
