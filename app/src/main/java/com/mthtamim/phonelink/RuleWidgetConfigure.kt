package com.mthtamim.phonelink

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

/** v2.8: pick which automation rule a RuleWidget button runs. */
class RuleWidgetConfigure : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val rules = Automation.rules(this)
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            rules.map { it.optString("name") })
        lv.setOnItemClickListener { _, _, pos, _ ->
            val rule = rules.getOrNull(pos) ?: return@setOnItemClickListener
            RuleWidgetProvider.setRuleFor(this, widgetId, rule.optString("id"))
            AppWidgetManager.getInstance(this).updateAppWidget(
                widgetId, RuleWidgetProvider.buildViews(this, widgetId))
            setResult(RESULT_OK, Intent()
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
        setContentView(lv)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) finish()
    }
}
