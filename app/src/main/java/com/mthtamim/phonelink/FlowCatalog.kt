package com.mthtamim.phonelink

import android.content.Context

/**
 * Flow type catalogs for PhoneLink v2.24.0 (the Lab merge):
 * Lab v1.5.0 lists (186 triggers / 43 conditions / 178 actions)
 * MINUS the merge exclusions: qs_tile + widget_click triggers (no
 * Flow tile/widget ships in this merge) and lost_on + lost_off +
 * autotap_control actions (lost-tracking is not ported; the
 * AutoTap engine is not wired into Flow — it stays rule-side).
 * Result: 184 triggers / 43 conditions / 175 actions. Everything
 * else is Lab's catalog verbatim, so Lab flows import unchanged
 * except for the excluded types, which import reports as unmapped.
 */
object FlowCatalog {
    val triggers = listOf(
        "time_at", "time_interval", "sunrise", "sunset",
        "battery_below", "battery_above", "charging_on", "charging_off",
        "wifi_connected", "wifi_disconnected", "screen_on", "screen_off",
        "boot", "shake", "flip_down", "flip_up",
        "proximity_near", "proximity_far", "light_below", "light_above",
        "headphone_in", "headphone_out", "usb_connected", "app_launched",
        "app_closed", "sms_received", "notif_received", "call_incoming",
        "call_missed", "geofence_in", "geofence_out", "steps_goal",
        "activity", "weather", "nfc_tag", "clipboard",
        "screen_text", "peer_online", "peer_offline", "battery_full",
        "battery_temp_above", "call_outgoing", "call_ended", "sms_sent",
        "notif_cleared", "app_installed", "app_removed", "screen_unlocked",
        "bt_connected", "bt_disconnected", "dnd_on", "dnd_off",
        "airplane_on", "airplane_off", "storage_low", "timezone_changed",
        "locale_changed", "peer_battery_below", "stopwatch", "date_at",
        "music_started", "music_stopped", "vpn_on", "vpn_off",
        "roaming_on", "roaming_off", "dark_on", "dark_off",
        "volume_up", "volume_down", "qr_scanned", "webhook",
        "weather_forecast", "calendar_start", "calendar_end", "magnet_above",
        "pressure_above", "hotspot_on", "hotspot_off", "rule_toggled",
        "battery_saver_on", "battery_saver_off", "wireless_charging", "screen_locked",
        "screenshot_taken", "photo_taken", "video_taken", "app_updated",
        "sms_delivered", "orientation_changed", "pick_up", "bt_near",
        "bt_far", "sim_changed", "ip_changed", "camera_used",
        "mic_used", "split_screen", "notify_button", "mobile_data_on",
        "mobile_data_off", "ui_click", "var_changed", "gps_on",
        "gps_off", "file_changed", "call_active", "mqtt_message",
        "applock_failed", "study_guard_continuous", "study_guard_daily", "screen_on_continuous",
        "time_at_days",
        // v1.3.0: fingerprint-gesture trigger (FlowA11yService).
        "fingerprint_gesture",
        // v1.4.0: every remaining Automate trigger type (see README
        // for firing paths and best-effort limits).
        "app_recent", "app_notif_enabled", "app_notif_priority",
        "app_notif_visibility", "appop_mode", "broadcast_receive",
        "content_changed", "log_await", "notif_interacted",
        "device_doze", "device_interactive", "device_shutdown",
        "display_power_mode",
        "audio_device", "audio_recording", "audio_muted", "bt_active",
        "bt_sco", "camera_available", "torch_state", "mic_muted",
        "ringer_mode", "speakerphone", "sound_level",
        "bt_enabled", "bt_tethering", "data_default", "eth_tethering",
        "mobile_type", "network_connected", "network_type",
        "nfc_enabled", "data_saver_on", "data_saver_off",
        "usb_configured", "usb_attached", "usb_tethering",
        "wifi_enabled", "hotspot_clients", "wifi_rssi",
        "alarm", "calendar_query",
        "media_added", "media_removed", "storage_mounted",
        "location_at", "mms_received",
        "temp", "accel", "hinge_angle", "heart_rate",
        "activity_recognition", "humidity", "sig_motion",
        "account_sync", "settings_changed",
        "cell_signal", "cell_tower", "operator", "sim_locked", "sim_state",
        "docked", "dialog_interacted", "key_pressed", "media_button",
        "headset_button", "keyboard_visible", "toast_posted",
        "ui_focused", "wallpaper_changed",
    )
    val constraints = listOf(
        "time_range", "day_of_week", "battery_above", "battery_below",
        "charging", "wifi_ssid", "location_in", "screen",
        "peer_online", "foreground_app", "variable", "music_playing",
        "call_active", "vpn", "roaming", "dark_mode",
        "dnd", "charging_type", "battery_temp_below", "battery_temp_above",
        "steps_today", "proximity_near", "light_below", "light_above",
        "airplane", "peer_battery_above", "bt_connected", "date_range",
        "day_of_month", "signal_above", "rule_enabled", "keyguard_locked",
        "battery_saver", "data_saver", "speed_above", "uptime_above",
        "calendar_busy", "headset_mic", "process_running", "mobile_data",
        "notif_present", "gps_state", "hotspot_state",
    )
    val actions = listOf(
        "notify_peer", "local_notify", "speak_local", "speak_peer",
        "command_peer", "send_sms", "torch", "torch_on",
        "torch_off", "torch_toggle", "ringer", "open_app",
        "http", "write_file", "set_var", "if_var",
        "if_else", "wait", "mode_set", "tap_text", "media_control", "volume_set",
        "brightness_set", "screen_timeout_set", "auto_rotate_set", "bt_toggle",
        "dnd_set", "vibrate", "play_sound", "record_audio",
        "take_photo", "open_url", "share_text", "kill_app",
        "answer_call", "end_call", "set_wallpaper", "clear_notif",
        "global_key", "stopwatch_start", "stopwatch_stop", "rule_enable",
        "rule_disable", "rule_run", "inc_var", "delete_file",
        "lock_screen", "wake_screen", "send_intent", "toast_local",
        "stop", "gesture_tap", "gesture_swipe", "read_screen",
        "send_email", "calendar_event", "set_alarm", "set_timer",
        "file_copy", "file_move", "file_zip", "dialog_choice",
        "keep_awake", "brightness_auto", "flash_pattern", "call_number",
        "ussd_run", "whatsapp_msg", "telegram_msg", "notif_reply",
        "rich_notify", "type_text", "font_scale", "night_light",
        "torch_level", "ftp_upload", "ftp_download", "image_resize",
        "file_encrypt", "file_decrypt", "json_get", "http_to_var",
        "regex_op", "date_calc", "loop_start", "loop_end",
        "break", "else", "end_if", "shell_cmd",
        "screen_pin", "unpin", "private_dns", "install_app",
        "uninstall_app", "aod_toggle", "sqlite_query", "reboot_device",
        "airplane_toggle", "mobile_data_toggle", "wifi_toggle", "shell_screenshot",
        "clear_app_data", "app_disable", "app_enable", "force_stop",
        "install_silent", "perm_grant", "perm_revoke", "appops_set",
        "wm_density", "wm_size", "anim_scale", "stay_on",
        "set_time", "set_locale", "shell_tap", "shell_swipe",
        "shell_input", "shell_key", "shell_to_var", "hotspot_toggle",
        "dark_theme", "battery_saver", "gps_toggle", "nfc_toggle",
        "record_video", "qr_show", "read_file", "clipboard_set",
        "delete_var", "calc_var", "wait_until", "mqtt_publish",
        "lock_app", "unlock_app", "focuslock_control",
        "redial_control",
        // v1.2.0 flow-control blocks (Flow category in the picker).
        "loop_count", "for_each", "call_flow", "flow_stop",
        // v1.3.0: fork + fiber control, variables, dialogs, files,
        // connectivity set-states, voice/location/screenshot/pick.
        // v1.4.3: QR scan, Wake-on-LAN, ping.
        "fork", "stop_flow",
        "set_local", "list_append", "list_get", "dict_get",
        "text_replace", "text_split", "text_join", "random_int",
        "dialog_input", "dialog_message", "dialog_progress",
        "date_picker", "time_picker",
        "file_list", "file_exists", "unzip", "clipboard_get",
        "bt_connect", "wifi_connect", "hotspot_set",
        "battery_saver_set", "data_saver_set", "dark_set",
        "listen", "get_location", "screenshot",
        "file_pick", "dir_pick",
        // v1.4.3: QR/barcode scan, Wake-on-LAN, ping.
        "qr_scan", "wol", "ping",
    )

    fun label(ctx: Context, kind: String, type: String): String {
        val resName = when (kind) {
            "t" -> "at_" + type
            "c" -> "ac_" + type
            else -> "aa_" + type
        }
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else type
    }

    fun hint(ctx: Context, kind: String, type: String): String {
        val resName = when (kind) {
            "t" -> "ath_" + type
            "c" -> "ach_" + type
            else -> "aah_" + type
        }
        val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else ""
    }
}
