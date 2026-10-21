package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/** Type catalogs shared by the list and the editor. */
object AutoCatalog {
    val triggers = listOf(
        "time_at", "time_interval", "sunrise", "sunset",
        "battery_below", "battery_above", "charging_on", "charging_off",
        "wifi_connected", "wifi_disconnected", "screen_on", "screen_off",
        "boot", "shake", "flip_down", "flip_up", "proximity_near", "proximity_far",
        "light_below", "light_above", "headphone_in", "headphone_out", "usb_connected",
        "app_launched", "app_closed", "sms_received", "notif_received",
        "call_incoming", "call_missed", "geofence_in", "geofence_out",
        "steps_goal", "activity", "weather", "nfc_tag", "clipboard",
        "screen_text", "peer_online", "peer_offline",
        // v2.7 MacroDroid expansion
        "battery_full", "battery_temp_above", "call_outgoing", "call_ended",
        "sms_sent", "notif_cleared", "app_installed", "app_removed",
        "screen_unlocked", "bt_connected", "bt_disconnected", "dnd_on", "dnd_off",
        "airplane_on", "airplane_off", "storage_low", "timezone_changed",
        "locale_changed", "peer_battery_below", "stopwatch", "date_at",
        "music_started", "music_stopped", "vpn_on", "vpn_off",
        "roaming_on", "roaming_off", "dark_on", "dark_off")
    val constraints = listOf(
        "time_range", "day_of_week", "battery_above", "battery_below", "charging",
        "wifi_ssid", "location_in", "screen", "peer_online", "foreground_app", "variable",
        // v2.7 MacroDroid expansion
        "music_playing", "call_active", "vpn", "roaming", "dark_mode", "dnd",
        "charging_type", "battery_temp_below", "battery_temp_above", "steps_today",
        "proximity_near", "light_below", "light_above", "airplane",
        "peer_battery_above", "bt_connected")
    val actions = listOf(
        "notify_peer", "local_notify", "speak_local", "speak_peer", "command_peer",
        "send_sms", "torch", "ringer", "open_app", "http", "write_file",
        "set_var", "if_var", "wait", "mode_set", "lost_on", "lost_off", "tap_text",
        // v2.7 MacroDroid expansion
        "media_control", "volume_set", "brightness_set", "screen_timeout_set",
        "auto_rotate_set", "bt_toggle", "dnd_set", "vibrate", "play_sound",
        "record_audio", "take_photo", "open_url", "share_text", "kill_app",
        "answer_call", "end_call", "set_wallpaper", "clear_notif", "global_key",
        "stopwatch_start", "stopwatch_stop", "rule_enable", "rule_disable",
        "rule_run", "inc_var", "delete_file", "lock_screen", "wake_screen",
        "send_intent", "toast_local", "stop")

    fun label(ctx: Context, kind: String, type: String): String {
        val resName = when (kind) {
            "t" -> "at_$type"; "c" -> "ac_$type"; else -> "aa_$type"
        }
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else type
    }

    fun hint(ctx: Context, kind: String, type: String): String {
        val resName = when (kind) {
            "t" -> "ath_$type"; "c" -> "ach_$type"; else -> "aah_$type"
        }
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else ""
    }
}

class AutomationActivity : LocalizedActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation)
        container = findViewById(R.id.autoContainer)
        findViewById<Button>(R.id.btnAddRule).setOnClickListener {
            startActivity(Intent(this, RuleEditorActivity::class.java))
        }
        findViewById<Button>(R.id.btnAutoBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val list = Automation.rules(this)
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.auto_empty)
                setPadding(8, 24, 8, 24)
            })
            return
        }
        for (rule in list) {
            val trig = rule.optJSONObject("trigger")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, 12) }
                setBackgroundResource(R.drawable.card_bg)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(this).apply {
                text = rule.optString("name").ifEmpty { getString(R.string.auto_title) }
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(Switch(this).apply {
                isChecked = rule.optBoolean("enabled", true)
                setOnCheckedChangeListener { _, on ->
                    Automation.setEnabled(this@AutomationActivity, rule.optString("id"), on)
                }
            })
            row.addView(head)
            row.addView(TextView(this).apply {
                text = "⚡ " + AutoCatalog.label(this@AutomationActivity, "t",
                    trig?.optString("type") ?: "") +
                    (trig?.optString("param")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                alpha = 0.75f
            })
            val nActions = rule.optJSONArray("actions")?.length() ?: 0
            row.addView(TextView(this).apply {
                text = getString(R.string.auto_actions_count, nActions)
                alpha = 0.75f
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this).apply {
                text = getString(R.string.btn_edit)
                setOnClickListener {
                    startActivity(Intent(this@AutomationActivity, RuleEditorActivity::class.java)
                        .putExtra("rule_id", rule.optString("id")))
                }
            })
            buttons.addView(Button(this).apply {
                text = getString(R.string.btn_delete)
                setOnClickListener {
                    Automation.delete(this@AutomationActivity, rule.optString("id"))
                    render()
                }
            })
            buttons.addView(Button(this).apply {
                text = getString(R.string.auto_test)
                setOnClickListener {
                    val data = JSONObject()
                        .put("title", rule.optString("name")).put("body", "test")
                    Automation.runActions(this@AutomationActivity, rule, data, 0)
                }
            })
            row.addView(buttons)
            container.addView(row)
        }
    }
}
