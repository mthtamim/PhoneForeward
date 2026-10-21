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
        "roaming_on", "roaming_off", "dark_on", "dark_off",
        // v2.8 final MacroDroid round
        "volume_up", "volume_down", "qr_scanned", "webhook", "weather_forecast",
        "calendar_start", "calendar_end", "magnet_above", "pressure_above",
        "hotspot_on", "hotspot_off", "rule_toggled",
        // v2.9 final round
        "battery_saver_on", "battery_saver_off", "wireless_charging",
        "screen_locked", "screenshot_taken", "photo_taken", "video_taken",
        "app_updated", "sms_delivered", "orientation_changed", "pick_up",
        "bt_near", "bt_far", "sim_changed", "ip_changed", "camera_used",
        "mic_used", "split_screen", "notify_button",
        // v2.10 Shizuku round
        "mobile_data_on", "mobile_data_off")
    val constraints = listOf(
        "time_range", "day_of_week", "battery_above", "battery_below", "charging",
        "wifi_ssid", "location_in", "screen", "peer_online", "foreground_app", "variable",
        // v2.7 MacroDroid expansion
        "music_playing", "call_active", "vpn", "roaming", "dark_mode", "dnd",
        "charging_type", "battery_temp_below", "battery_temp_above", "steps_today",
        "proximity_near", "light_below", "light_above", "airplane",
        "peer_battery_above", "bt_connected",
        // v2.8 final MacroDroid round
        "date_range", "day_of_month", "signal_above", "rule_enabled",
        // v2.9 final round
        "keyguard_locked", "battery_saver", "data_saver", "speed_above",
        "uptime_above", "calendar_busy", "headset_mic", "process_running",
        // v2.10 Shizuku round
        "mobile_data")
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
        "send_intent", "toast_local", "stop",
        // v2.8 final MacroDroid round
        "gesture_tap", "gesture_swipe", "read_screen", "send_email",
        "calendar_event", "set_alarm", "set_timer", "file_copy", "file_move",
        "file_zip", "dialog_choice", "keep_awake", "brightness_auto", "flash_pattern",
        // v2.9 final round
        "call_number", "ussd_run", "whatsapp_msg", "telegram_msg",
        "notif_reply", "rich_notify", "type_text", "font_scale",
        "night_light", "torch_level", "ftp_upload", "ftp_download",
        "image_resize", "file_encrypt", "file_decrypt", "json_get",
        "http_to_var", "regex_op", "date_calc", "loop_start", "loop_end",
        "break", "else", "end_if", "shell_cmd", "screen_pin", "unpin",
        "private_dns", "install_app", "uninstall_app", "aod_toggle",
        "sqlite_query",
        // v2.10 Shizuku (root-class) round
        "reboot_device", "airplane_toggle", "mobile_data_toggle",
        "wifi_toggle", "shell_screenshot", "clear_app_data", "app_disable",
        "app_enable", "force_stop", "install_silent", "perm_grant",
        "perm_revoke", "appops_set", "wm_density", "wm_size", "anim_scale",
        "stay_on", "set_time", "set_locale", "shell_tap", "shell_swipe",
        "shell_input", "shell_key", "shell_to_var")

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

    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra("code") ?: return@registerForActivityResult
        if (code.isNotEmpty()) Automation.onEvent(this, "qr_scanned",
            JSONObject().put("title", code).put("body", code))
    }

    private fun showLog() {
        val lines = Automation.recentLog(this)
        val tv = TextView(this).apply {
            text = if (lines.isEmpty()) getString(R.string.auto_log_empty)
                else lines.joinToString("\n")
            setPadding(48, 24, 48, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_log))
            .setView(scroll)
            .setNeutralButton(getString(R.string.auto_log_clear)) { _, _ ->
                Automation.clearLog(this)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showVars() {
        val names = Automation.varNames(this)
        if (names.isEmpty()) {
            android.widget.Toast.makeText(this,
                getString(R.string.auto_vars_empty), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = names.map { "$it = ${Automation.getVar(this, it)}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_vars))
            .setItems(labels) { _, which ->
                val name = names[which]
                val input = android.widget.EditText(this).apply {
                    setText(Automation.getVar(this@AutomationActivity, name))
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(name)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Automation.setVar(this, name, input.text.toString())
                    }
                    .setNegativeButton(getString(R.string.auto_var_delete)) { _, _ ->
                        Automation.deleteVar(this, name)
                    }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** v2.9: pick one of the rotating rule-set backups to restore. */
    private fun showBackups() {
        val backs = Automation.backups(this)
        if (backs.isEmpty()) {
            android.widget.Toast.makeText(this,
                getString(R.string.auto_backup_none),
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US)
        val labels = backs.map { (t, json) ->
            val n = try { org.json.JSONArray(json).length() }
                catch (e: Exception) { 0 }
            fmt.format(java.util.Date(t)) + " — " + n + " rules"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_backup_title))
            .setItems(labels) { _, which ->
                if (Automation.restoreBackup(this, backs[which].second)) {
                    android.widget.Toast.makeText(this,
                        getString(R.string.auto_backup_restored),
                        android.widget.Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImport() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.auto_import_hint)
            minLines = 4
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_import))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = Automation.importJson(this, input.text.toString())
                android.widget.Toast.makeText(this,
                    if (n >= 0) getString(R.string.auto_imported, n)
                    else getString(R.string.auto_import_failed),
                    android.widget.Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation)
        container = findViewById(R.id.autoContainer)
        findViewById<Button>(R.id.btnAddRule).setOnClickListener {
            startActivity(Intent(this, RuleEditorActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnAutoBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAutoQr).setOnClickListener {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<Button>(R.id.btnAutoLog).setOnClickListener { showLog() }
        findViewById<Button>(R.id.btnAutoVars).setOnClickListener { showVars() }
        findViewById<Button>(R.id.btnAutoExport).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_TEXT, Automation.exportJson(this))
            startActivity(Intent.createChooser(send, getString(R.string.auto_export)))
        }
        findViewById<Button>(R.id.btnAutoImport).setOnClickListener { showImport() }
        findViewById<android.widget.Switch>(R.id.swRulesSync).apply {
            isChecked = Config.rulesSyncOn(this@AutomationActivity)
            setOnCheckedChangeListener { _, on ->
                Config.setRulesSyncOn(this@AutomationActivity, on)
            }
        }
        findViewById<Button>(R.id.btnAutoBackup).setOnClickListener { showBackups() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val list = Automation.rules(this)
            .sortedWith(compareBy({ it.optString("cat") }, { it.optString("name") }))
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.auto_empty)
                setPadding(8, 24, 8, 24)
            })
            return
        }
        var lastCat = "\u0000"
        for (rule in list) {
            val cat = rule.optString("cat")
            if (cat != lastCat) {
                lastCat = cat
                if (cat.isNotEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "📁 $cat"
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(4, 10, 4, 6)
                    })
                }
            }
            val trigList = Automation.ruleTriggers(rule)
            val trig = trigList.firstOrNull()
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
                    (if (trigList.size > 1) " +${trigList.size - 1}" else "") +
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
