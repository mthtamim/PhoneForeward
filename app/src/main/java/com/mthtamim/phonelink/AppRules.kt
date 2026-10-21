package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject

/**
 * Per-app notification rules (Smart Control). Every app is one of:
 * DEFAULT (follow the global behaviour), INSTANT (always forward now),
 * DIGEST (bundle into the periodic digest) or OFF (never forward).
 * On top of that, allowlist mode flips the default: only apps the user
 * explicitly set to INSTANT or DIGEST produce notifications at all.
 */
object AppRules {
    const val DEFAULT = 0
    const val INSTANT = 1
    const val DIGEST = 2
    const val OFF = 3

    private const val PREFS = "phonelink_apprules"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun map(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("rules", "{}")) } catch (e: Exception) { JSONObject() }

    fun rule(ctx: Context, pkg: String): Int = map(ctx).optInt(pkg, DEFAULT)

    fun setRule(ctx: Context, pkg: String, rule: Int) {
        val m = map(ctx)
        if (rule == DEFAULT) m.remove(pkg) else m.put(pkg, rule)
        prefs(ctx).edit().putString("rules", m.toString()).apply()
    }

    fun allowlistMode(ctx: Context): Boolean = prefs(ctx).getBoolean("allowlist", false)
    fun setAllowlistMode(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("allowlist", v).apply()
    }

    fun next(rule: Int): Int = (rule + 1) % 4
}
