package com.mthtamim.phonelink

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Shared catalog picker, mirroring PhoneLink's picker exactly
 * (RuleEditorActivity.showPicker + pickItems + groupAnchors):
 * dialog title + Cancel button, a filled rounded search box with
 * a leading search icon, a list at 50% of screen height, and a
 * GROUPED list — a category header row (12.5sp bold mint) before
 * each group of entries, entries label-only at 15sp onSurface
 * with 20dp/11dp padding, headers non-clickable. The search
 * matches the label or the type id (case-insensitive); while
 * searching, headers appear only for groups with matches.
 * The group model is PhoneLink's: an ordered anchor list per
 * kind maps boundary types to categories; walking the catalog
 * in order, the running category switches at each anchor (the
 * default before the first anchor is More).
 */
object FlowPicker {

    private sealed class PickItem {
        class Header(val text: String) : PickItem()
        class Entry(val type: String, val label: String) : PickItem()
    }

    private fun groupAnchors(kind: String): List<Pair<String, Int>> = when (kind) {
        "t" -> listOf(
            "time_at" to R.string.pg_time,
            "battery_below" to R.string.pg_battery,
            "wifi_connected" to R.string.pg_conn,
            "screen_on" to R.string.pg_device,
            "app_launched" to R.string.pg_apps,
            "sms_received" to R.string.pg_msg,
            "geofence_in" to R.string.pg_loc,
            "steps_goal" to R.string.pg_device,
            "weather" to R.string.pg_more,
            "nfc_tag" to R.string.pg_device,
            "peer_online" to R.string.pg_comm,
            "battery_full" to R.string.pg_battery,
            "call_outgoing" to R.string.pg_comm,
            "sms_sent" to R.string.pg_msg,
            "notif_cleared" to R.string.pg_msg,
            "app_installed" to R.string.pg_apps,
            "screen_unlocked" to R.string.pg_device,
            "bt_connected" to R.string.pg_conn,
            "dnd_on" to R.string.pg_state,
            "airplane_on" to R.string.pg_conn,
            "storage_low" to R.string.pg_device,
            "timezone_changed" to R.string.pg_system,
            "peer_battery_below" to R.string.pg_comm,
            "stopwatch" to R.string.pg_device,
            "date_at" to R.string.pg_time,
            "music_started" to R.string.pg_media,
            "vpn_on" to R.string.pg_conn,
            "roaming_on" to R.string.pg_conn,
            "dark_on" to R.string.pg_state,
            "volume_up" to R.string.pg_device,
            "qr_scanned" to R.string.pg_device,
            "webhook" to R.string.pg_conn,
            "weather_forecast" to R.string.pg_more,
            "calendar_start" to R.string.pg_apps,
            "magnet_above" to R.string.pg_device,
            "hotspot_on" to R.string.pg_conn,
            "rule_toggled" to R.string.pg_flow,
            "battery_saver_on" to R.string.pg_battery,
            "wireless_charging" to R.string.pg_battery,
            "screen_locked" to R.string.pg_device,
            "screenshot_taken" to R.string.pg_media,
            "app_updated" to R.string.pg_apps,
            "sms_delivered" to R.string.pg_msg,
            "orientation_changed" to R.string.pg_device,
            "pick_up" to R.string.pg_device,
            "bt_near" to R.string.pg_conn,
            "sim_changed" to R.string.pg_device,
            "ip_changed" to R.string.pg_conn,
            "camera_used" to R.string.pg_media,
            "split_screen" to R.string.pg_device,
            "notify_button" to R.string.pg_msg,
            "mobile_data_on" to R.string.pg_conn,
            "ui_click" to R.string.pg_device,
            "var_changed" to R.string.pg_flow,
            "gps_on" to R.string.pg_loc,
            "file_changed" to R.string.pg_files,
            "call_active" to R.string.pg_comm,
            "mqtt_message" to R.string.pg_conn,
            "applock_failed" to R.string.pg_apps,
            "study_guard_continuous" to R.string.pg_flow,
            "screen_on_continuous" to R.string.pg_device,
            "time_at_days" to R.string.pg_time,
            "fingerprint_gesture" to R.string.pg_device,
            // v1.4.0 anchors, in catalog-append order.
            "app_recent" to R.string.pg_apps,
            "device_doze" to R.string.pg_battery,
            "audio_device" to R.string.pg_media,
            "bt_enabled" to R.string.pg_conn,
            "alarm" to R.string.pg_time,
            "media_added" to R.string.pg_media,
            "storage_mounted" to R.string.pg_files,
            "location_at" to R.string.pg_loc,
            "mms_received" to R.string.pg_msg,
            "temp" to R.string.pg_device,
            "account_sync" to R.string.pg_system,
            "cell_signal" to R.string.pg_conn,
            "docked" to R.string.pg_device
        )
        "c" -> listOf(
            "time_range" to R.string.pg_time,
            "battery_above" to R.string.pg_battery,
            "wifi_ssid" to R.string.pg_conn,
            "location_in" to R.string.pg_loc,
            "screen" to R.string.pg_state,
            "peer_online" to R.string.pg_comm,
            "foreground_app" to R.string.pg_apps,
            "variable" to R.string.pg_flow,
            "music_playing" to R.string.pg_media,
            "call_active" to R.string.pg_comm,
            "vpn" to R.string.pg_conn,
            "dark_mode" to R.string.pg_state,
            "charging_type" to R.string.pg_battery,
            "steps_today" to R.string.pg_device,
            "proximity_near" to R.string.pg_device,
            "airplane" to R.string.pg_conn,
            "peer_battery_above" to R.string.pg_comm,
            "bt_connected" to R.string.pg_conn,
            "date_range" to R.string.pg_time,
            "signal_above" to R.string.pg_conn,
            "rule_enabled" to R.string.pg_flow,
            "keyguard_locked" to R.string.pg_state,
            "battery_saver" to R.string.pg_battery,
            "data_saver" to R.string.pg_conn,
            "speed_above" to R.string.pg_device,
            "calendar_busy" to R.string.pg_apps,
            "headset_mic" to R.string.pg_media,
            "process_running" to R.string.pg_apps,
            "mobile_data" to R.string.pg_conn,
            "notif_present" to R.string.pg_msg,
            "gps_state" to R.string.pg_loc,
            "hotspot_state" to R.string.pg_conn
        )
        else -> listOf(
            "notify_peer" to R.string.pg_notify,
            "speak_local" to R.string.pg_media,
            "command_peer" to R.string.pg_comm,
            "send_sms" to R.string.pg_comm,
            "torch" to R.string.pg_control,
            "open_app" to R.string.pg_apps,
            "http" to R.string.pg_files,
            "set_var" to R.string.pg_flow,
            "wait" to R.string.pg_flow,
            "mode_set" to R.string.pg_control,
            "lost_on" to R.string.pg_comm,
            "tap_text" to R.string.pg_device,
            "media_control" to R.string.pg_media,
            "bt_toggle" to R.string.pg_conn,
            "dnd_set" to R.string.pg_state,
            "vibrate" to R.string.pg_device,
            "play_sound" to R.string.pg_media,
            "take_photo" to R.string.pg_media,
            "open_url" to R.string.pg_apps,
            "share_text" to R.string.pg_comm,
            "kill_app" to R.string.pg_apps,
            "answer_call" to R.string.pg_comm,
            "set_wallpaper" to R.string.pg_device,
            "clear_notif" to R.string.pg_notify,
            "global_key" to R.string.pg_device,
            "stopwatch_start" to R.string.pg_device,
            "rule_enable" to R.string.pg_flow,
            "lock_screen" to R.string.pg_control,
            "send_intent" to R.string.pg_comm,
            "toast_local" to R.string.pg_notify,
            "stop" to R.string.pg_flow,
            "gesture_tap" to R.string.pg_device,
            "send_email" to R.string.pg_comm,
            "calendar_event" to R.string.pg_apps,
            "set_alarm" to R.string.pg_system,
            "file_copy" to R.string.pg_files,
            "dialog_choice" to R.string.pg_notify,
            "keep_awake" to R.string.pg_system,
            "brightness_auto" to R.string.pg_control,
            "call_number" to R.string.pg_comm,
            "whatsapp_msg" to R.string.pg_comm,
            "rich_notify" to R.string.pg_notify,
            "type_text" to R.string.pg_device,
            "night_light" to R.string.pg_control,
            "ftp_upload" to R.string.pg_files,
            "json_get" to R.string.pg_files,
            "loop_start" to R.string.pg_flow,
            "shell_cmd" to R.string.pg_system,
            "screen_pin" to R.string.pg_apps,
            "private_dns" to R.string.pg_conn,
            "install_app" to R.string.pg_apps,
            "sqlite_query" to R.string.pg_files,
            "reboot_device" to R.string.pg_system,
            "airplane_toggle" to R.string.pg_conn,
            "shell_screenshot" to R.string.pg_media,
            "clear_app_data" to R.string.pg_apps,
            "perm_grant" to R.string.pg_system,
            "wm_density" to R.string.pg_system,
            "set_time" to R.string.pg_system,
            "shell_tap" to R.string.pg_device,
            "hotspot_toggle" to R.string.pg_conn,
            "dark_theme" to R.string.pg_control,
            "battery_saver" to R.string.pg_battery,
            "gps_toggle" to R.string.pg_conn,
            "record_video" to R.string.pg_media,
            "qr_show" to R.string.pg_device,
            "read_file" to R.string.pg_files,
            "clipboard_set" to R.string.pg_device,
            "delete_var" to R.string.pg_flow,
            "mqtt_publish" to R.string.pg_conn,
            "lock_app" to R.string.pg_apps,
            "loop_count" to R.string.pg_flow,
            "fork" to R.string.pg_flow,
            "stop_flow" to R.string.pg_flow,
            "set_local" to R.string.pg_flow,
            "list_append" to R.string.pg_flow,
            "list_get" to R.string.pg_flow,
            "dict_get" to R.string.pg_flow,
            "text_replace" to R.string.pg_flow,
            "text_split" to R.string.pg_flow,
            "text_join" to R.string.pg_flow,
            "random_int" to R.string.pg_flow,
            "dialog_input" to R.string.pg_notify,
            "dialog_message" to R.string.pg_notify,
            "dialog_progress" to R.string.pg_notify,
            "date_picker" to R.string.pg_notify,
            "time_picker" to R.string.pg_notify,
            "file_list" to R.string.pg_files,
            "file_exists" to R.string.pg_files,
            "unzip" to R.string.pg_files,
            "clipboard_get" to R.string.pg_device,
            "bt_connect" to R.string.pg_conn,
            "wifi_connect" to R.string.pg_conn,
            "hotspot_set" to R.string.pg_conn,
            "battery_saver_set" to R.string.pg_battery,
            "data_saver_set" to R.string.pg_conn,
            "dark_set" to R.string.pg_control,
            "listen" to R.string.pg_media,
            "get_location" to R.string.pg_loc,
            "screenshot" to R.string.pg_media,
            "file_pick" to R.string.pg_files,
            "dir_pick" to R.string.pg_files,
            // v1.4.3.
            "qr_scan" to R.string.pg_media,
            "wol" to R.string.pg_conn,
            "ping" to R.string.pg_conn
        )
    }

    private fun typesFor(kind: String): List<String> = when (kind) {
        "t" -> FlowCatalog.triggers
        "c" -> FlowCatalog.constraints
        else -> FlowCatalog.actions
    }

    /** Full grouped item list for a kind, optionally filtered by a search query. */
    private fun pickItems(activity: Activity, kind: String, query: String): List<PickItem> {
        val anchors = groupAnchors(kind).toMap()
        val q = query.trim().lowercase()
        val out = mutableListOf<PickItem>()
        var groupRes = R.string.pg_more
        var pendingHeader: PickItem.Header? = null
        for (type in typesFor(kind)) {
            anchors[type]?.let { groupRes = it }
            val label = FlowCatalog.label(activity, kind, type)
            if (q.isNotEmpty() && !label.lowercase().contains(q) &&
                !type.lowercase().contains(q)
            ) {
                continue
            }
            val header = PickItem.Header(activity.getString(groupRes))
            if (pendingHeader == null || pendingHeader.text != header.text) {
                pendingHeader = header
                out.add(header)
            }
            out.add(PickItem.Entry(type, label))
        }
        return out
    }

    fun show(activity: Activity, kind: String, onPick: (String) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_picker, null)
        val etFilter = view.findViewById<EditText>(R.id.etFilter)
        val lv = view.findViewById<ListView>(R.id.lvItems)
        val params = lv.layoutParams
        params.height = (activity.resources.displayMetrics.heightPixels * 0.5).toInt()
        lv.layoutParams = params
        val adapter = PickAdapter(activity, pickItems(activity, kind, ""))
        lv.adapter = adapter
        val dialog = AlertDialog.Builder(activity)
            .setTitle(
                when (kind) {
                    "t" -> R.string.pick_trigger
                    "c" -> R.string.pick_condition
                    else -> R.string.pick_action
                }
            )
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                adapter.items = pickItems(activity, kind, s?.toString() ?: "")
                adapter.notifyDataSetChanged()
            }
        })
        lv.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.items[position]
            if (item is PickItem.Entry) {
                onPick(item.type)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private class PickAdapter(
        private val activity: Activity,
        var items: List<PickItem>
    ) : BaseAdapter() {

        override fun getCount(): Int {
            return items.size
        }

        override fun getItem(position: Int): Any {
            return items[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewTypeCount(): Int {
            return 2
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is PickItem.Header) 0 else 1
        }

        override fun isEnabled(position: Int): Boolean {
            return items[position] is PickItem.Entry
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val d = activity.resources.displayMetrics.density
            if (item is PickItem.Header) {
                val tv = convertView as? TextView ?: TextView(activity).apply {
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ThemeColors.primary(activity))
                    setPadding(
                        (20f * d).toInt(),
                        (14f * d).toInt(),
                        (20f * d).toInt(),
                        (4f * d).toInt()
                    )
                }
                tv.text = item.text
                return tv
            }
            val entry = item as PickItem.Entry
            val tv = convertView as? TextView ?: TextView(activity).apply {
                textSize = 15f
                setPadding(
                    (20f * d).toInt(),
                    (11f * d).toInt(),
                    (20f * d).toInt(),
                    (11f * d).toInt()
                )
                val out = TypedValue()
                activity.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurface, out, true
                )
                setTextColor(out.data)
            }
            tv.text = entry.label
            return tv
        }
    }
}
