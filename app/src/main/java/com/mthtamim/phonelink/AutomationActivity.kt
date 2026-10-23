package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.AppOpsManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

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
        "mobile_data_on", "mobile_data_off",
        // v2.16 MacroDroid shortlist round
        "ui_click", "var_changed", "gps_on", "gps_off", "file_changed",
        "call_active")
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
        "mobile_data",
        // v2.16 MacroDroid shortlist round
        "notif_present", "gps_state", "hotspot_state")
    val actions = listOf(
        "notify_peer", "local_notify", "speak_local", "speak_peer", "command_peer",
        "send_sms", "torch", "torch_on", "torch_off", "torch_toggle",
        "ringer", "open_app", "http", "write_file",
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
        "shell_input", "shell_key", "shell_to_var",
        // v2.16 MacroDroid shortlist round
        "hotspot_toggle", "dark_theme", "battery_saver", "gps_toggle",
        "nfc_toggle", "record_video", "qr_show", "read_file",
        "clipboard_set", "delete_var", "calc_var", "wait_until")

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
    private var healthBroken = -1

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

    private val importFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.readText().orEmpty()
                } catch (e: Exception) { "" }
                runOnUiThread { doImport(text) }
            }
        }
    }

    /** Shared by the paste box and the file picker (v2.14.3). */
    private fun doImport(raw: String) {
        val text = raw.trim()
        if (text.startsWith("{") && text.contains("full-backup")) {
            val ok = SettingsTransfer.import(this, text)
            android.widget.Toast.makeText(this,
                getString(if (ok) R.string.toast_cfg_imported
                    else R.string.auto_import_failed),
                android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val n = Automation.importJson(this, text)
            android.widget.Toast.makeText(this,
                if (n >= 0) getString(R.string.auto_imported, n)
                else getString(R.string.auto_import_failed),
                android.widget.Toast.LENGTH_SHORT).show()
        }
        renderHealth()
        render()
    }

    private fun showImport() {
        // v2.14.3: pasting a long JSON into a one-line box was nearly
        // unusable — the field is properly multi-line now, and a file
        // picker does the same import without any copy-paste at all.
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.auto_import_hint)
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_import))
            .setView(input)
            .setNeutralButton(getString(R.string.auto_import_file)) { _, _ ->
                importFileLauncher.launch(
                    arrayOf("application/json", "text/plain", "*/*"))
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                doImport(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation)
        container = findViewById(R.id.autoContainer)
        // v2.17: same five-tab bar as MainActivity; the other tabs
        // hand over to MainActivity with a tab extra.
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.autoBottomNav)
        nav.selectedItemId = R.id.navAutomation
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.navAutomation) {
                return@setOnItemSelectedListener true
            }
            val tab = when (item.itemId) {
                R.id.navInbox -> "inbox"
                R.id.navRemote -> "remote"
                R.id.navSettings -> "settings"
                else -> "home"
            }
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            true
        }
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
        findViewById<android.widget.CompoundButton>(R.id.swRulesSync).apply {
            isChecked = Config.rulesSyncOn(this@AutomationActivity)
            setOnCheckedChangeListener { _, on ->
                Config.setRulesSyncOn(this@AutomationActivity, on)
            }
        }
        findViewById<Button>(R.id.btnAutoBackup).setOnClickListener { showBackups() }
        val btnRest = findViewById<Button>(R.id.btnRestTime)
        btnRest.text = Automation.restSeconds(this).toString() + "s"
        btnRest.setOnClickListener {
            val et = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(Automation.restSeconds(this@AutomationActivity)
                    .toString())
                setSelection(text.length)
                hint = getString(R.string.auto_rest_hint)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.auto_rest_title))
                .setView(et)
                .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                    val sec = et.text.toString().toIntOrNull() ?: 10
                    Automation.setRestSeconds(this, sec)
                    btnRest.text = Automation.restSeconds(this)
                        .toString() + "s"
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHealth()
        render()
    }

    /** v2.14.1: trigger health — several trigger families silently do
     *  nothing while a system access is off. Show each one's live
     *  status. v2.17.4: a tap no longer opens a second grant path —
     *  every broken row deep-links to Permissions & Access, the one
     *  place all access is granted (inventory move #5/#6). */
    private fun renderHealth() {
        val box = findViewById<LinearLayout>(R.id.healthRows)
        box.removeAllViews()
        val toPermissions: () -> Unit = {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        val items = mutableListOf<Triple<Int, Boolean, (() -> Unit)?>>()
        items.add(Triple(R.string.auto_health_a11y, a11yEnabled(), toPermissions))
        items.add(Triple(R.string.auto_health_notif, PermChecks.notifAccess(this), toPermissions))
        items.add(Triple(R.string.auto_health_usage, usageAccess(), toPermissions))
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            items.add(Triple(R.string.auto_health_alarm, am.canScheduleExactAlarms(), toPermissions))
        }
        items.add(Triple(R.string.auto_health_shizuku, ShizukuHelper.granted(), toPermissions))
        // v2.15.0: sms_sent quietly dies without READ_SMS — visible here.
        items.add(Triple(R.string.auto_health_sms,
            checkSelfPermission(android.Manifest.permission.READ_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED, toPermissions))
        // v2.14.2: compact — the title line carries the summary, and
        // only the broken items get a row (the full five-row list ate
        // too much of the screen).
        val broken = items.filter { !it.second }
        healthBroken = broken.size
        findViewById<TextView>(R.id.tvHealthTitle).text =
            if (broken.isEmpty())
                getString(R.string.auto_health_all_ok)
            else
                getString(R.string.auto_health_broken, broken.size)
        for ((label, _, action) in broken) {
            box.addView(TextView(this).apply {
                text = "• " + getString(label) + getString(R.string.auto_health_fix)
                setPadding(0, 6, 0, 6)
                if (action != null) setOnClickListener { action() }
            })
        }
    }

    private fun a11yEnabled(): Boolean = try {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    } catch (_: Exception) { false }

    private fun usageAccess(): Boolean = try {
        val ao = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        else
            @Suppress("DEPRECATION")
            ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun updateAutoPill(total: Int, active: Int) {
        var text = getString(R.string.auto_pill_fmt, active, total)
        if (healthBroken > 0) {
            text += " • " + getString(R.string.auto_health_broken, healthBroken)
        }
        findViewById<TextView>(R.id.tvAutoPill).text = text
    }

    private fun render() {
        container.removeAllViews()
        val list = Automation.rules(this)
            .sortedWith(compareBy({ it.optString("cat") }, { it.optString("name") }))
        updateAutoPill(list.size,
            list.count { it.optBoolean("enabled", true) })
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
                        text = cat
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
            // v2.17.2: IF / THEN inset boxes, like the preview cards
            val acts = rule.optJSONArray("actions")
            val nActions = acts?.length() ?: 0
            val firstAct = acts?.optJSONObject(0)?.optString("type") ?: ""
            val flow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 10, 0, 0) }
            }
            fun flowBox(prefixRes: Int, body: String) = TextView(this).apply {
                text = getString(prefixRes) + " " + body
                textSize = 13.5f
                setBackgroundResource(R.drawable.bg_inner_box)
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            flow.addView(flowBox(R.string.auto_if_label,
                AutoCatalog.label(this@AutomationActivity, "t",
                    trig?.optString("type") ?: "") +
                    (if (trigList.size > 1) " +${trigList.size - 1}" else "") +
                    (trig?.optString("param")?.takeIf { it.isNotBlank() }
                        ?.let { " — $it" } ?: "")))
            flow.addView(TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@AutomationActivity, R.color.pl_mint))
                setPadding(12, 0, 12, 0)
            })
            flow.addView(flowBox(R.string.auto_then_label,
                AutoCatalog.label(this@AutomationActivity, "a", firstAct) +
                    (if (nActions > 1) " +${nActions - 1}" else "")))
            row.addView(flow)
            row.addView(TextView(this).apply {
                text = getString(R.string.auto_actions_count, nActions)
                alpha = 0.7f
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(2, 8, 0, 0) }
            })
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 8, 0, 0) }
            }
            // v2.17.3: compact pill buttons in the new system
            // (14dp radius, ~36dp tall) instead of the old bg_pill.
            fun pillButton(labelRes: Int, mint: Boolean) = Button(this).apply {
                text = getString(labelRes)
                textSize = 12.5f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setBackgroundResource(
                    if (mint) R.drawable.bg_action_pill
                    else R.drawable.bg_action_ghost)
                setPadding(40, 30, 40, 30)
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@AutomationActivity,
                    if (mint) R.color.pl_mint else R.color.md_onSurface))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(8, 0, 0, 0) }
            }
            buttons.addView(pillButton(R.string.btn_edit, false).apply {
                setOnClickListener {
                    startActivity(Intent(this@AutomationActivity, RuleEditorActivity::class.java)
                        .putExtra("rule_id", rule.optString("id")))
                }
            })
            buttons.addView(pillButton(R.string.btn_delete, false).apply {
                setOnClickListener {
                    Automation.delete(this@AutomationActivity, rule.optString("id"))
                    render()
                }
            })
            buttons.addView(pillButton(R.string.auto_test, true).apply {
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
