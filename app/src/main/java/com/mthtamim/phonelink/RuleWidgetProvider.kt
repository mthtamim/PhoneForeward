package com.mthtamim.phonelink

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject

/** v2.8: a home-screen button that runs one chosen automation rule. */
class RuleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_RUN) {
            val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
            val ruleId = ruleIdFor(context, widgetId)
            val rule = Automation.rules(context)
                .firstOrNull { it.optString("id") == ruleId } ?: return
            Automation.runActions(context.applicationContext, rule, JSONObject(), 0)
        }
    }

    companion object {
        const val ACTION_RUN = "pl_rule_widget_run"
        const val EXTRA_WIDGET_ID = "widget_id"

        fun ruleIdFor(ctx: Context, widgetId: Int): String =
            ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                .getString("rulewidget_$widgetId", "") ?: ""

        fun setRuleFor(ctx: Context, widgetId: Int, ruleId: String) {
            ctx.getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                .edit().putString("rulewidget_$widgetId", ruleId).apply()
        }

        fun buildViews(ctx: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_rule_layout)
            val rule = Automation.rules(ctx)
                .firstOrNull { it.optString("id") == ruleIdFor(ctx, widgetId) }
            views.setTextViewText(R.id.wRuleName,
                rule?.optString("name")
                    ?: ctx.getString(R.string.widget_rule_unset))
            val intent = Intent(ctx, RuleWidgetProvider::class.java)
                .setAction(ACTION_RUN).putExtra(EXTRA_WIDGET_ID, widgetId)
            val pi = PendingIntent.getBroadcast(ctx, 1000 + widgetId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.wRuleRoot, pi)
            return views
        }
    }
}
