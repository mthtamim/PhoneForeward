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

    fun setToggle(ctx: Context, key: String, v: Boolean) {
        prefs(ctx).edit().putBoolean(key, v).apply()
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

    private fun generateCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no confusing I/O/0/1
        return (1..10).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }
}
