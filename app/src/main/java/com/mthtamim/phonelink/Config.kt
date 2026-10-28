package com.mthtamim.phonelink

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import kotlin.random.Random

object Config {
    private const val PREFS = "phonelink_cfg"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- pairing ----------
    fun pairCode(ctx: Context): String {
        var code = prefs(ctx).getString("pair_code", "") ?: ""
        if (code.length < 8) {
            code = generateCode()
            prefs(ctx).edit().putString("pair_code", code).apply()
        }
        return code
    }

    fun setPairCode(ctx: Context, code: String) {
        prefs(ctx).edit().putString("pair_code", code.trim().uppercase()).apply()
    }

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString("device_name", android.os.Build.MODEL ?: "Phone") ?: "Phone"

    fun setDeviceName(ctx: Context, name: String) {
        prefs(ctx).edit().putString("device_name", name.trim()).apply()
    }

    fun serverUrl(ctx: Context): String =
        (prefs(ctx).getString("server_url", "https://ntfy.sh") ?: "https://ntfy.sh").trimEnd('/')

    fun setServerUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("server_url", url.trim().trimEnd('/')).apply()
    }

    /** Optional second relay, used automatically when the primary is unreachable. */
    fun backupServerUrl(ctx: Context): String =
        (prefs(ctx).getString("backup_server_url", "") ?: "").trimEnd('/')

    fun setBackupServerUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("backup_server_url", url.trim().trimEnd('/')).apply()
    }

    // ---------- modes ----------
    fun isSender(ctx: Context): Boolean = prefs(ctx).getBoolean("mode_sender", true)
    fun isReceiver(ctx: Context): Boolean = prefs(ctx).getBoolean("mode_receiver", false)
    fun setSender(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("mode_sender", v).apply() }
    fun setReceiver(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("mode_receiver", v).apply() }

    // ---------- feature toggles (sender side) ----------
    fun fwdSms(ctx: Context) = prefs(ctx).getBoolean("fwd_sms", true)
    fun fwdNotif(ctx: Context) = prefs(ctx).getBoolean("fwd_notif", true)
    fun fwdCalls(ctx: Context) = prefs(ctx).getBoolean("fwd_calls", true)
    fun fwdBattery(ctx: Context) = prefs(ctx).getBoolean("fwd_battery", true)
    fun fwdApps(ctx: Context) = prefs(ctx).getBoolean("fwd_apps", true)
    fun fwdSystem(ctx: Context) = prefs(ctx).getBoolean("fwd_system", true)
    fun fwdNetwork(ctx: Context) = prefs(ctx).getBoolean("fwd_network", true)
    fun fwdSecurity(ctx: Context) = prefs(ctx).getBoolean("fwd_security", true)
    fun allowLocation(ctx: Context) = prefs(ctx).getBoolean("allow_location", true)
    fun allowRing(ctx: Context) = prefs(ctx).getBoolean("allow_ring", true)
    fun allowFlash(ctx: Context) = prefs(ctx).getBoolean("allow_flash", true)
    fun allowSnap(ctx: Context) = prefs(ctx).getBoolean("allow_snap", false)
    fun allowSmsReply(ctx: Context) = prefs(ctx).getBoolean("allow_sms_reply", false)
    fun smsTriggerOn(ctx: Context) = prefs(ctx).getBoolean("sms_trigger", true)
    fun ttsCodes(ctx: Context) = prefs(ctx).getBoolean("tts_codes", false)

    // ---------- smart control (v2.1) ----------

    /** Quiet hours: receiver notifications stay silent in this window. */
    fun quietOn(ctx: Context) = prefs(ctx).getBoolean("quiet_on", false)
    fun quietStart(ctx: Context) = prefs(ctx).getInt("quiet_start", 23 * 60)
    fun quietEnd(ctx: Context) = prefs(ctx).getInt("quiet_end", 7 * 60)
    fun setQuietWindow(ctx: Context, startMin: Int, endMin: Int) {
        prefs(ctx).edit().putInt("quiet_start", startMin).putInt("quiet_end", endMin).apply()
    }

    fun isQuietNow(ctx: Context): Boolean {
        if (quietUntil(ctx) > System.currentTimeMillis()) return true
        if (!quietOn(ctx)) return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = quietStart(ctx); val e = quietEnd(ctx)
        return if (s <= e) now in s until e else (now >= s || now < e)
    }

    /** Notifications in digest mode are bundled and sent every N minutes (0 = off). */
    fun digestMinutes(ctx: Context) = prefs(ctx).getInt("digest_minutes", 60)
    fun setDigestMinutes(ctx: Context, m: Int) { prefs(ctx).edit().putInt("digest_minutes", m).apply() }

    /** Priority contacts (comma separated numbers): bypass quiet hours, urgent sound, ⭐ mark. */
    fun priorityRaw(ctx: Context): String = prefs(ctx).getString("priority_numbers", "") ?: ""
    fun setPriorityRaw(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("priority_numbers", raw.trim()).apply()
    }

    private fun digitsOf(s: String): String = s.filter { it.isDigit() }

    fun isPriorityNumber(ctx: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        val mine = digitsOf(number).takeLast(10)
        if (mine.isEmpty()) return false
        return priorityRaw(ctx).split(",", " ", ";")
            .map { digitsOf(it).takeLast(10) }
            .any { it.isNotEmpty() && it == mine }
    }

    /** Geofence: alert when the sender phone leaves/enters a saved area. */
    fun geoOn(ctx: Context) = prefs(ctx).getBoolean("geo_on", false)
    fun geoLat(ctx: Context) = prefs(ctx).getFloat("geo_lat", 0f).toDouble()
    fun geoLng(ctx: Context) = prefs(ctx).getFloat("geo_lng", 0f).toDouble()
    fun geoRadius(ctx: Context) = prefs(ctx).getInt("geo_radius", 200)
    fun setGeoRadius(ctx: Context, v: Int) { prefs(ctx).edit().putInt("geo_radius", v).apply() }

    /** Offline send-queue cap (v2.17.5): oldest queued events are
     *  dropped past this limit. Default 300 = the pre-v2.17.5 behavior. */
    fun queueLimit(ctx: Context) = prefs(ctx).getInt("queue_limit", 300)
    fun setQueueLimit(ctx: Context, v: Int) { prefs(ctx).edit().putInt("queue_limit", v).apply() }
    fun setGeoCenter(ctx: Context, lat: Double, lng: Double) {
        prefs(ctx).edit().putFloat("geo_lat", lat.toFloat()).putFloat("geo_lng", lng.toFloat()).apply()
    }
    fun geoIsSet(ctx: Context) = geoLat(ctx) != 0.0 || geoLng(ctx) != 0.0

    fun dailySummaryOn(ctx: Context) = prefs(ctx).getBoolean("daily_summary_on", false)

    /** Inbox auto-clean: keep only the last N days (0 = keep forever). Pinned items survive. */
    fun inboxKeepDays(ctx: Context) = prefs(ctx).getInt("inbox_keep_days", 0)
    fun setInboxKeepDays(ctx: Context, d: Int) { prefs(ctx).edit().putInt("inbox_keep_days", d).apply() }

    // ---------- v2.7 ----------
    /** SMS fallback: urgent alerts also go out as a plain SMS when the relay is unreachable. */
    fun smsFallbackOn(ctx: Context) = prefs(ctx).getBoolean("sms_fallback_on", false)
    fun smsFallbackNumber(ctx: Context) = prefs(ctx).getString("sms_fallback_number", "") ?: ""
    fun setSmsFallbackNumber(ctx: Context, v: String) {
        prefs(ctx).edit().putString("sms_fallback_number", v).apply()
    }
    /** Bluetooth offline share: keep the RFCOMM listener running. */
    fun btReceiveOn(ctx: Context) = prefs(ctx).getBoolean("bt_receive_on", false)

    // ---------- v2.8: SMTP (automation send_email) ----------
    fun smtpHost(ctx: Context) = prefs(ctx).getString("smtp_host", "") ?: ""
    fun smtpPort(ctx: Context) = prefs(ctx).getInt("smtp_port", 587)
    fun smtpUser(ctx: Context) = prefs(ctx).getString("smtp_user", "") ?: ""
    fun smtpPass(ctx: Context) = prefs(ctx).getString("smtp_pass", "") ?: ""
    fun smtpFrom(ctx: Context) = prefs(ctx).getString("smtp_from", "") ?: ""
    fun setSmtp(ctx: Context, host: String, port: Int, user: String, pass: String, from: String) {
        prefs(ctx).edit().putString("smtp_host", host).putInt("smtp_port", port)
            .putString("smtp_user", user).putString("smtp_pass", pass)
            .putString("smtp_from", from).apply()
    }

    // ---------- v2.2 ----------

    /** Alert words: any message containing one is treated as urgent. */
    fun alertWords(ctx: Context): List<String> =
        (prefs(ctx).getString("alert_words", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun alertWordsRaw(ctx: Context): String = prefs(ctx).getString("alert_words", "") ?: ""
    fun setAlertWordsRaw(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("alert_words", raw.trim()).apply()
    }
    fun containsAlertWord(ctx: Context, text: String): Boolean {
        val lower = text.lowercase()
        return alertWords(ctx).any { lower.contains(it.lowercase()) }
    }

    /** Missed-call auto-reply SMS (sender phone). */
    fun autoReplyOn(ctx: Context) = prefs(ctx).getBoolean("auto_reply_on", false)
    fun autoReplyText(ctx: Context): String =
        prefs(ctx).getString("auto_reply_text", "") ?: ""
    fun setAutoReplyText(ctx: Context, t: String) {
        prefs(ctx).edit().putString("auto_reply_text", t.trim()).apply()
    }

    /** Morning routine: one automatic status (battery + summary + location). */
    fun routineOn(ctx: Context) = prefs(ctx).getBoolean("routine_on", false)
    fun routineMinutes(ctx: Context) = prefs(ctx).getInt("routine_minutes", 8 * 60)
    fun setRoutineMinutes(ctx: Context, m: Int) { prefs(ctx).edit().putInt("routine_minutes", m).apply() }

    /** Send one last location when the battery is about to die. */
    fun lowLocOn(ctx: Context) = prefs(ctx).getBoolean("low_loc_on", true)
    fun ttsCalls(ctx: Context) = prefs(ctx).getBoolean("tts_calls", false)
    fun fwdPresence(ctx: Context) = prefs(ctx).getBoolean("fwd_presence", false)
    fun fwdMedia(ctx: Context) = prefs(ctx).getBoolean("fwd_media", false)

    /** Telegram mirror of forwarded messages (bot token + chat id). */
    fun tgToken(ctx: Context): String = prefs(ctx).getString("tg_token", "") ?: ""
    fun tgChat(ctx: Context): String = prefs(ctx).getString("tg_chat", "") ?: ""

    /** Telegram forum-topic (thread) ids per category; 0 = main chat. */
    fun tgThread(ctx: Context, key: String): Int =
        prefs(ctx).getInt("tg_thread_$key", 0)

    fun setTgThreads(ctx: Context, sms: Int, notif: Int, call: Int,
                     battery: Int, other: Int) {
        prefs(ctx).edit()
            .putInt("tg_thread_sms", sms)
            .putInt("tg_thread_notif", notif)
            .putInt("tg_thread_call", call)
            .putInt("tg_thread_battery", battery)
            .putInt("tg_thread_other", other)
            .apply()
    }
    fun setTelegram(ctx: Context, token: String, chat: String) {
        prefs(ctx).edit().putString("tg_token", token.trim()).putString("tg_chat", chat.trim()).apply()
    }

    // ---------- v2.18: MQTT broker (WS4) ----------
    fun mqttOn(ctx: Context): Boolean = prefs(ctx).getBoolean("mqtt_on", false)
    fun mqttBroker(ctx: Context): String = prefs(ctx).getString("mqtt_broker", "") ?: ""
    fun mqttUser(ctx: Context): String = prefs(ctx).getString("mqtt_user", "") ?: ""
    fun mqttPass(ctx: Context): String = prefs(ctx).getString("mqtt_pass", "") ?: ""
    fun setMqtt(ctx: Context, on: Boolean, broker: String, user: String, pass: String) {
        prefs(ctx).edit().putBoolean("mqtt_on", on)
            .putString("mqtt_broker", broker.trim())
            .putString("mqtt_user", user.trim())
            .putString("mqtt_pass", pass).apply()
    }

    // ---------- pairing profiles ----------
    fun profiles(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(prefs(ctx).getString("profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.optString("name") to o.optString("code"))
            }
        } catch (_: Exception) { }
        return out
    }

    fun addProfile(ctx: Context, name: String, code: String) {
        val list = profiles(ctx).filterNot { it.second.equals(code, ignoreCase = true) } +
            (name.trim() to code.trim().uppercase())
        val arr = org.json.JSONArray()
        for ((n, c) in list.take(5)) {
            arr.put(org.json.JSONObject().put("name", n).put("code", c))
        }
        prefs(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    fun removeProfile(ctx: Context, code: String) {
        val list = profiles(ctx).filterNot { it.second.equals(code, ignoreCase = true) }
        val arr = org.json.JSONArray()
        for ((n, c) in list) arr.put(org.json.JSONObject().put("name", n).put("code", c))
        prefs(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    // ---------- per-app daily usage limits (minutes) ----------
    fun usageLimit(ctx: Context, pkg: String): Int =
        try { org.json.JSONObject(prefs(ctx).getString("usage_limits", "{}")).optInt(pkg, 0) }
        catch (e: Exception) { 0 }

    fun setUsageLimit(ctx: Context, pkg: String, minutes: Int) {
        val o = try { org.json.JSONObject(prefs(ctx).getString("usage_limits", "{}")) }
            catch (e: Exception) { org.json.JSONObject() }
        if (minutes <= 0) o.remove(pkg) else o.put(pkg, minutes)
        prefs(ctx).edit().putString("usage_limits", o.toString()).apply()
    }

    fun usageLimitedPkgs(ctx: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val o = org.json.JSONObject(prefs(ctx).getString("usage_limits", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) out.add(keys.next())
        } catch (_: Exception) { }
        return out
    }

    // ---------- v2.3 ----------

    /** Stable per-device id so group chats (3+ phones) can ignore themselves. */
    fun deviceId(ctx: Context): String {
        var id = prefs(ctx).getString("device_id", "")
        if (id.isNullOrEmpty()) {
            id = java.util.UUID.randomUUID().toString().take(12)
            prefs(ctx).edit().putString("device_id", id).apply()
        }
        return id
    }

    // ----- command PIN (stored only as a salted digest) -----
    private fun pinDigestOf(pin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("phonelink-pin:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun cmdPinDigest(ctx: Context): String? = prefs(ctx).getString("cmd_pin_digest", null)
    fun cmdPinIsSet(ctx: Context): Boolean = cmdPinDigest(ctx) != null
    fun setCmdPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString("cmd_pin_digest", pinDigestOf(pin)).apply()
    }
    fun clearCmdPin(ctx: Context) { prefs(ctx).edit().remove("cmd_pin_digest").apply() }
    fun digestForPin(pin: String): String = pinDigestOf(pin)

    // ----- quiet-until (timed silence, e.g. meeting mode) -----
    fun quietUntil(ctx: Context): Long = prefs(ctx).getLong("quiet_until", 0L)
    fun setQuietUntil(ctx: Context, t: Long) { prefs(ctx).edit().putLong("quiet_until", t).apply() }

    // ----- modes -----
    fun studyMode(ctx: Context): Boolean = prefs(ctx).getBoolean("study_mode", false)
    fun modeName(ctx: Context): String = prefs(ctx).getString("mode_name", "off") ?: "off"

    /** Applies a mode bundle locally. Used by the UI and the MODE command. */
    fun applyMode(ctx: Context, mode: String) {
        val e = prefs(ctx).edit()
        when (mode) {
            "sleep" -> {
                e.putBoolean("quiet_on", true)
                e.putInt("quiet_start", 23 * 60).putInt("quiet_end", 7 * 60)
                e.putLong("quiet_until", 0L)
                e.putBoolean("study_mode", false)
                e.putBoolean("tts_codes", false)
            }
            "meeting" -> {
                e.putLong("quiet_until", System.currentTimeMillis() + 60 * 60 * 1000L)
                e.putBoolean("auto_reply_on", true)
                e.putBoolean("study_mode", false)
            }
            "study" -> {
                e.putBoolean("study_mode", true)
                if (digestMinutes(ctx) == 0) e.putInt("digest_minutes", 30)
                e.putLong("quiet_until", 0L)
            }
            else -> {
                e.putBoolean("study_mode", false)
                e.putLong("quiet_until", 0L)
            }
        }
        e.putString("mode_name", mode).apply()
    }

    // ----- misc v2.3 toggles & data -----
    fun guardOn(ctx: Context): Boolean = prefs(ctx).getBoolean("guard_on", false)
    fun intruderOn(ctx: Context): Boolean = prefs(ctx).getBoolean("intruder_on", false)
    fun autosaveMedia(ctx: Context): Boolean = prefs(ctx).getBoolean("autosave_media", false)
    fun dataLimitMb(ctx: Context): Int = prefs(ctx).getInt("data_limit_mb", 0)
    fun setDataLimitMb(ctx: Context, mb: Int) { prefs(ctx).edit().putInt("data_limit_mb", mb).apply() }

    fun myStatus(ctx: Context): String = prefs(ctx).getString("my_status", "") ?: ""
    fun setMyStatus(ctx: Context, s: String) { prefs(ctx).edit().putString("my_status", s.trim()).apply() }

    /** v2.21: the script name remembered by the Dashboard AutoTap
     *  card, exactly like myStatus above. */
    fun autoTapScript(ctx: Context): String = prefs(ctx).getString("autotap_script", "") ?: ""
    fun setAutoTapScript(ctx: Context, s: String) { prefs(ctx).edit().putString("autotap_script", s.trim()).apply() }

    fun sharedNote(ctx: Context): String = prefs(ctx).getString("shared_note", "") ?: ""
    fun setSharedNote(ctx: Context, s: String) { prefs(ctx).edit().putString("shared_note", s).apply() }

    fun templates(ctx: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(prefs(ctx).getString("templates", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.optString("name") to o.optString("body"))
            }
        } catch (_: Exception) { }
        return out
    }

    fun saveTemplates(ctx: Context, list: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        for ((n, b) in list.take(12)) arr.put(org.json.JSONObject().put("name", n).put("body", b))
        prefs(ctx).edit().putString("templates", arr.toString()).apply()
    }

    fun setToggle(ctx: Context, key: String, v: Boolean) {
        prefs(ctx).edit().putBoolean(key, v).apply()
    }

    // ----- remote control (SET command) key mapping -----
    fun remoteKeys(): List<String> = listOf(
        "sender_on", "receiver_on", "fwd_sms", "fwd_notif", "fwd_calls",
        "fwd_battery", "fwd_security", "fwd_system", "fwd_presence",
        "fwd_media", "guard_on")

    fun remoteState(ctx: Context, key: String): Boolean = when (key) {
        "sender_on" -> isSender(ctx)
        "receiver_on" -> isReceiver(ctx)
        "fwd_sms" -> fwdSms(ctx)
        "fwd_notif" -> fwdNotif(ctx)
        "fwd_calls" -> fwdCalls(ctx)
        "fwd_battery" -> fwdBattery(ctx)
        "fwd_security" -> fwdSecurity(ctx)
        "fwd_system" -> fwdSystem(ctx)
        "fwd_presence" -> fwdPresence(ctx)
        "fwd_media" -> fwdMedia(ctx)
        "guard_on" -> guardOn(ctx)
        else -> false
    }

    fun applyRemote(ctx: Context, key: String, v: Boolean) {
        when (key) {
            "sender_on" -> setSender(ctx, v)
            "receiver_on" -> setReceiver(ctx, v)
            else -> setToggle(ctx, key, v)
        }
    }

    /** Comma-separated package names that must never be forwarded. */
    fun excludedApps(ctx: Context): Set<String> =
        (prefs(ctx).getString("excluded_apps", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setExcludedApps(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("excluded_apps", raw.trim()).apply()
    }

    /** Words (comma separated): if a notification/SMS contains one, it is NOT forwarded. */
    fun blockedWords(ctx: Context): List<String> =
        (prefs(ctx).getString("blocked_words", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setBlockedWords(ctx: Context, raw: String) {
        prefs(ctx).edit().putString("blocked_words", raw.trim()).apply()
    }

    fun containsBlockedWord(ctx: Context, text: String): Boolean {
        val lower = text.lowercase()
        return blockedWords(ctx).any { lower.contains(it.lowercase()) }
    }

    // ---------- topics ----------
    // Topic names are a SHA-256 hash of the pair code, so nobody can guess
    // or enumerate them on the public relay. Data and commands use
    // separate topics so the two directions never mix.
    private fun hashTopic(code: String, kind: String): String {
        val input = "phonelink-v1-$kind-${code.trim().uppercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "pl" + kind + hex.take(28)
    }

    fun dataTopic(ctx: Context) = hashTopic(pairCode(ctx), "d")
    fun cmdTopic(ctx: Context) = hashTopic(pairCode(ctx), "c")

    // ---------- receiver state ----------
    fun lastSenderSeen(ctx: Context): Long = prefs(ctx).getLong("last_sender_seen", 0L)
    fun setLastSenderSeen(ctx: Context, t: Long) {
        prefs(ctx).edit().putLong("last_sender_seen", t).apply()
    }
    fun lastSenderName(ctx: Context): String = prefs(ctx).getString("last_sender_name", "") ?: ""
    fun setLastSenderName(ctx: Context, n: String) {
        prefs(ctx).edit().putString("last_sender_name", n).apply()
    }
    fun lastSenderBattery(ctx: Context): Int = prefs(ctx).getInt("last_sender_battery", -1)
    fun setLastSenderBattery(ctx: Context, pct: Int) {
        prefs(ctx).edit().putInt("last_sender_battery", pct).apply()
    }

    // ---------- security ----------
    /** End-to-end encryption of relay payloads (key derived from the pair code). */
    fun encryptionOn(ctx: Context): Boolean = prefs(ctx).getBoolean("e2ee_on", true)
    fun setEncryptionOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("e2ee_on", v).apply() }

    fun appLockOn(ctx: Context): Boolean = prefs(ctx).getBoolean("app_lock_on", false)
    fun setAppLockOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("app_lock_on", v).apply() }
    fun pinHash(ctx: Context): String = prefs(ctx).getString("app_pin_hash", "") ?: ""
    fun setPinHash(ctx: Context, h: String) { prefs(ctx).edit().putString("app_pin_hash", h).apply() }

    // ---------- appearance / onboarding ----------
    /** UI language: "bn", "en" or "system". */
    fun language(ctx: Context): String = prefs(ctx).getString("app_lang", "bn") ?: "bn"
    fun setLanguage(ctx: Context, v: String) { prefs(ctx).edit().putString("app_lang", v).apply() }

    /** Theme: "system", "light" or "dark". */
    fun themeMode(ctx: Context): String = prefs(ctx).getString("theme_mode", "system") ?: "system"
    fun setThemeMode(ctx: Context, v: String) { prefs(ctx).edit().putString("theme_mode", v).apply() }

    /** The automatic permission wizard is shown only once (first run / first service start). */
    fun wizardShown(ctx: Context): Boolean = prefs(ctx).getBoolean("perm_wizard_shown", false)
    fun setWizardShown(ctx: Context) { prefs(ctx).edit().putBoolean("perm_wizard_shown", true).apply() }

    // ---------- v2.4: group roles & per-peer rules ----------
    /** Device ids allowed to run commands here; empty = everyone (legacy). */
    fun commanders(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("role_commanders", emptySet()) ?: emptySet()

    fun setCommanders(ctx: Context, ids: Set<String>) {
        prefs(ctx).edit().putStringSet("role_commanders", ids).apply()
    }

    fun commandsAllowedFrom(ctx: Context, dev: String): Boolean {
        val set = commanders(ctx)
        return set.isEmpty() || dev.isEmpty() || dev in set
    }

    /** Receiver-side per-peer alert mode: "mute" / "digest" / "" (normal). */
    fun peerMode(ctx: Context, dev: String): String =
        prefs(ctx).getString("peer_mode_$dev", "") ?: ""

    fun setPeerMode(ctx: Context, dev: String, mode: String) {
        prefs(ctx).edit().putString("peer_mode_$dev", mode).apply()
    }

    // ---------- v2.4: lost mode ----------
    fun lostOn(ctx: Context) = prefs(ctx).getBoolean("lost_on", false)
    fun setLostOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("lost_on", v).apply() }
    fun lostContact(ctx: Context): String = prefs(ctx).getString("lost_contact", "") ?: ""
    fun setLostContact(ctx: Context, t: String) { prefs(ctx).edit().putString("lost_contact", t).apply() }
    fun lostDarkScreen(ctx: Context) = prefs(ctx).getBoolean("lost_dark", false)
    fun setLostDarkScreen(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("lost_dark", v).apply() }

    // ---------- v2.4: simple mode & check-in ----------
    fun simpleMode(ctx: Context) = prefs(ctx).getBoolean("simple_mode", false)
    fun setSimpleMode(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("simple_mode", v).apply() }

    /** Expected daily check-in time (minutes of day); -1 = off. */
    fun checkinExpectMin(ctx: Context) = prefs(ctx).getInt("checkin_expect", -1)
    fun setCheckinExpectMin(ctx: Context, m: Int) { prefs(ctx).edit().putInt("checkin_expect", m).apply() }
    fun lastCheckinDay(ctx: Context): String = prefs(ctx).getString("checkin_day", "") ?: ""
    fun setLastCheckinDay(ctx: Context, d: String) { prefs(ctx).edit().putString("checkin_day", d).apply() }

    // ---------- v2.4: LAN direct mode ----------
    fun lanOn(ctx: Context) = prefs(ctx).getBoolean("lan_on", true)
    fun setLanOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("lan_on", v).apply() }

    // ---------- v2.4: clipboard auto-sync ----------
    fun clipSyncOn(ctx: Context) = prefs(ctx).getBoolean("clip_sync", false)
    fun setClipSyncOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("clip_sync", v).apply() }

    // ---------- v2.4: smart importance ----------
    fun autoPriorityOn(ctx: Context) = prefs(ctx).getBoolean("auto_priority", true)
    fun setAutoPriorityOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_priority", v).apply() }

    // ---------- v2.4: automation ----------
    fun automationOn(ctx: Context) = prefs(ctx).getBoolean("automation_on", true)
    fun rulesSyncOn(ctx: Context) = prefs(ctx).getBoolean("rules_sync_on", false)

    // ---------- v2.20: FocusLock integration ----------
    /** The FocusLock automation token, saved ONCE from the
     *  Automation screen's FocusLock card and reused by every
     *  focuslock_control step at run time (the steps themselves
     *  never carry it). Lives in the same private cfg store as
     *  the pair code. Empty = not set = steps log and skip. */
    fun focusLockToken(ctx: Context): String =
        prefs(ctx).getString("focuslock_token", "") ?: ""
    fun setFocusLockToken(ctx: Context, v: String) {
        prefs(ctx).edit().putString("focuslock_token", v).apply() }

    // ---------- v2.20: Study Guard lists ----------
    /** One named Study Guard template: the study apps the user
     *  picked once, reused by every Study Guard trigger (Tamim's
     *  decision, 24 Oct 2026: MULTIPLE named lists). Stored as a
     *  JSON array in cfg key "study_lists" — [{id, name, pkgs}] —
     *  the same shape the profiles/templates stores use. */
    class StudyList(val id: String, val name: String,
                    val pkgs: Set<String>)

    fun studyLists(ctx: Context): List<StudyList> {
        val out = mutableListOf<StudyList>()
        try {
            val arr = org.json.JSONArray(
                prefs(ctx).getString("study_lists", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkgs = mutableSetOf<String>()
                val pa = o.optJSONArray("pkgs")
                if (pa != null) for (j in 0 until pa.length())
                    pa.optString(j).takeIf { it.isNotEmpty() }
                        ?.let { pkgs.add(it) }
                out.add(StudyList(o.optString("id"),
                    o.optString("name"), pkgs))
            }
        } catch (e: Exception) { }
        return out.filter { it.id.isNotEmpty() }
    }

    fun studyList(ctx: Context, id: String): StudyList? =
        studyLists(ctx).firstOrNull { it.id == id }

    /** Upsert by id; a blank id mints a fresh stable one. */
    fun saveStudyList(ctx: Context, list: StudyList): StudyList {
        val saved = if (list.id.isNotEmpty()) list else StudyList(
            "sl" + System.currentTimeMillis().toString(36) +
                (100..999).random(), list.name, list.pkgs)
        val cur = studyLists(ctx).filter { it.id != saved.id } + saved
        val arr = org.json.JSONArray()
        for (l in cur) {
            val pa = org.json.JSONArray()
            for (p in l.pkgs) pa.put(p)
            arr.put(org.json.JSONObject().put("id", l.id)
                .put("name", l.name).put("pkgs", pa))
        }
        prefs(ctx).edit().putString("study_lists", arr.toString())
            .apply()
        return saved
    }

    fun deleteStudyList(ctx: Context, id: String) {
        val cur = studyLists(ctx).filter { it.id != id }
        val arr = org.json.JSONArray()
        for (l in cur) {
            val pa = org.json.JSONArray()
            for (p in l.pkgs) pa.put(p)
            arr.put(org.json.JSONObject().put("id", l.id)
                .put("name", l.name).put("pkgs", pa))
        }
        prefs(ctx).edit().putString("study_lists", arr.toString())
            .apply()
    }
    // ---- v2.11 ----
    fun sosNumbers(ctx: Context): List<String> =
        (prefs(ctx).getString("sos_numbers", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun setSosNumbers(ctx: Context, v: String) { prefs(ctx).edit().putString("sos_numbers", v).apply() }
    fun panicPinHash(ctx: Context): String = prefs(ctx).getString("panic_pin_hash", "") ?: ""
    fun setPanicPinHash(ctx: Context, v: String) { prefs(ctx).edit().putString("panic_pin_hash", v).apply() }
    fun panicActive(ctx: Context): Boolean = prefs(ctx).getBoolean("panic_active", false)
    fun setPanicActive(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("panic_active", v).apply() }
    fun autoBackupOn(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_backup_on", false)
    fun autoBackupLast(ctx: Context): Long = prefs(ctx).getLong("auto_backup_last", 0L)
    fun setAutoBackupLast(ctx: Context, v: Long) { prefs(ctx).edit().putLong("auto_backup_last", v).apply() }
    fun accent(ctx: Context): String = prefs(ctx).getString("accent", "emerald") ?: "emerald"
    fun setAccent(ctx: Context, v: String) { prefs(ctx).edit().putString("accent", v).apply() }
    // v2.25.0: optional app theme (phonelink_cfg key "app_theme").
    // Unset/missing = "emerald_dark" = exact pre-2.25 behaviour.
    fun appTheme(ctx: Context): String = prefs(ctx).getString("app_theme", "emerald_dark") ?: "emerald_dark"
    fun setAppTheme(ctx: Context, v: String) { prefs(ctx).edit().putString("app_theme", v).apply() }
    fun webInboxOn(ctx: Context): Boolean = prefs(ctx).getBoolean("web_inbox_on", false)
    fun webPin(ctx: Context): String = prefs(ctx).getString("web_pin", "") ?: ""
    fun setWebPin(ctx: Context, v: String) { prefs(ctx).edit().putString("web_pin", v).apply() }
    fun peerPin(ctx: Context, dev: String): String =
        prefs(ctx).getString("peer_pin_$dev", "") ?: ""
    fun setPeerPin(ctx: Context, dev: String, digest: String) {
        prefs(ctx).edit().putString("peer_pin_$dev", digest).apply()
    }
    fun storedPeerPins(ctx: Context): List<String> =
        prefs(ctx).all.keys.filter { it.startsWith("peer_pin_") }
            .mapNotNull { prefs(ctx).getString(it, "") }.filter { it.isNotEmpty() }
    fun focusOn(ctx: Context): Boolean = prefs(ctx).getBoolean("focus_on", false)
    fun setFocusOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("focus_on", v).apply() }
    fun focusPkgs(ctx: Context): Set<String> =
        (prefs(ctx).getString("focus_pkgs", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun setFocusPkgs(ctx: Context, v: String) { prefs(ctx).edit().putString("focus_pkgs", v).apply() }
    fun focusWindow(ctx: Context): Pair<Int, Int> =
        (prefs(ctx).getInt("focus_start", 9 * 60) to prefs(ctx).getInt("focus_end", 17 * 60))
    fun setFocusWindow(ctx: Context, start: Int, end: Int) {
        prefs(ctx).edit().putInt("focus_start", start).putInt("focus_end", end).apply()
    }
    fun pttAutoplay(ctx: Context): Boolean = prefs(ctx).getBoolean("ptt_autoplay", true)
    fun setPttAutoplay(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("ptt_autoplay", v).apply() }
    // ---- v2.11.1 launcher hide (icon lives on .MainAlias) ----
    fun iconHidden(ctx: Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(
            android.content.ComponentName(ctx, "com.mthtamim.phonelink.MainAlias")) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { false }
    fun setIconHidden(ctx: Context, hidden: Boolean) {
        try {
            ctx.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(ctx, "com.mthtamim.phonelink.MainAlias"),
                if (hidden) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) { }
    }
    fun updateLastCheck(ctx: Context): Long = prefs(ctx).getLong("update_last_check", 0L)
    fun setUpdateLastCheck(ctx: Context, v: Long) { prefs(ctx).edit().putLong("update_last_check", v).apply() }

    /** v2.16: auto-update installs new releases by itself
     *  (Shizuku silent install, else a one-tap installer notice). */
    fun autoUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update", true)
    fun setAutoUpdate(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_update", v).apply() }
    fun setRulesSyncOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("rules_sync_on", v).apply() }
    fun setAutomationOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("automation_on", v).apply() }

    private fun generateCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no confusing I/O/0/1
        return (1..10).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
