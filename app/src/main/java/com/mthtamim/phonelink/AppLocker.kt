package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.18 App Locker: locks arbitrary apps behind the locker's OWN PIN
 * (Crypto.pinHash, stored in the dedicated "phonelink_locker" prefs —
 * the app-lock PIN in phonelink_cfg is never touched).
 *
 * Enforcement: A11yService window-state events are the instant path;
 * LinkService's 5-second foreground poll is the fallback when
 * Accessibility is off. The cover is a TYPE_APPLICATION_OVERLAY
 * window when the overlay grant exists, else the same layout hosted
 * by AppLockActivity (best effort). Hard safety exclusions
 * (systemui, the default launcher, the default dialer / in-call UI)
 * can NEVER be locked — not from the picker, not by manual package
 * entry, not by automation. PhoneLink itself and Settings join the
 * enforced set only through the dedicated Protect toggle.
 */
object AppLocker {

    const val INTRUDER_THRESHOLD = 3
    const val SETTINGS_PKG = "com.android.settings"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_locker", Context.MODE_PRIVATE)

    // ---------------- settings ----------------

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) AppLockOverlay.hide(ctx)
    }

    /** The locker's own PIN hash (Crypto.pinHash scheme); "" = unset. */
    fun pinHash(ctx: Context): String =
        prefs(ctx).getString("pin_hash", "") ?: ""

    fun pinLen(ctx: Context): Int =
        prefs(ctx).getInt("pin_len", 4).coerceIn(4, 6)

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit()
            .putString("pin_hash", Crypto.pinHash(pin))
            .putInt("pin_len", pin.length)
            .apply()
    }

    fun biometricOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("bio_on", true)

    fun setBiometricOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("bio_on", on).apply()
    }

    /** Relock delay: -1 = until screen off, 0 = immediately, else seconds. */
    fun graceSec(ctx: Context): Int = prefs(ctx).getInt("grace_sec", 30)

    fun setGraceSec(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt("grace_sec", sec).apply()
    }

    fun peerOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("peer_on", false)

    fun setPeerOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("peer_on", on).apply()
    }

    fun intruderOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("intruder_on", true)

    fun setIntruderOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("intruder_on", on).apply()
    }

    fun protectSelfOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("protect_self", false)

    fun setProtectSelfOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("protect_self", on).apply()
    }

    fun lockNewOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("lock_new", false)

    fun setLockNewOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("lock_new", on).apply()
    }

    fun disguiseOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean("disguise_on", false)

    fun setDisguiseOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("disguise_on", on).apply()
    }

    // ---------------- locked set ----------------

    fun lockedPkgs(ctx: Context): MutableSet<String> {
        val out = mutableSetOf<String>()
        try {
            val arr = JSONArray(prefs(ctx).getString("locked", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)
                if (p.isNotEmpty()) out.add(p)
            }
        } catch (_: Exception) { }
        return out
    }

    private fun saveLocked(ctx: Context, set: Set<String>) {
        val arr = JSONArray()
        set.sorted().forEach { arr.put(it) }
        prefs(ctx).edit().putString("locked", arr.toString()).apply()
    }

    fun isInSet(ctx: Context, pkg: String): Boolean =
        lockedPkgs(ctx).contains(pkg)

    /** Validates and adds; false when the package may not be locked. */
    fun addLocked(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty() || pickerExcluded(ctx, pkg)) return false
        val set = lockedPkgs(ctx)
        if (set.add(pkg)) saveLocked(ctx, set)
        return true
    }

    fun removeLocked(ctx: Context, pkg: String) {
        val set = lockedPkgs(ctx)
        if (set.remove(pkg)) saveLocked(ctx, set)
        clearGrace(ctx, pkg)
        if (AppLockOverlay.forPkg == pkg) AppLockOverlay.hide(ctx)
    }

    /** Enforced = in the locked set, or PhoneLink/Settings while the
     *  Protect toggle is on. */
    fun isLocked(ctx: Context, pkg: String): Boolean {
        if (isInSet(ctx, pkg)) return true
        if (protectSelfOn(ctx) &&
            (pkg == ctx.packageName || pkg == SETTINGS_PKG)) return true
        return false
    }

    // ---------------- safety exclusions ----------------

    private val STATIC_NEVER = setOf(
        "com.android.systemui", "android",
        "com.android.incallui", "com.android.server.telecom")

    fun defaultLauncherPkg(ctx: Context): String = try {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: ""
    } catch (_: Exception) { "" }

    fun defaultDialerPkg(ctx: Context): String = try {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE)
            as android.telecom.TelecomManager
        tm.defaultDialerPackage ?: ""
    } catch (_: Exception) { "" }

    /** System-critical packages that can NEVER be locked, by any path. */
    fun hardExcluded(ctx: Context, pkg: String): Boolean {
        if (pkg in STATIC_NEVER) return true
        if (pkg.isNotEmpty() && pkg == defaultLauncherPkg(ctx)) return true
        if (pkg.isNotEmpty() && pkg == defaultDialerPkg(ctx)) return true
        return false
    }

    /** Picker/manual exclusion = hard exclusions + the two packages
     *  that lock only via the Protect toggle. */
    fun pickerExcluded(ctx: Context, pkg: String): Boolean =
        hardExcluded(ctx, pkg) || pkg == ctx.packageName || pkg == SETTINGS_PKG

    // ---------------- app data (picker) ----------------

    data class AppEntry(val pkg: String, val label: String, val system: Boolean)

    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    fun appIcon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) { null }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) { false }

    /** EVERY installed package (the locker picker walks this list;
     *  exclusions are filtered by the caller). */
    fun allInstalled(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val out = mutableListOf<AppEntry>()
        try {
            @Suppress("DEPRECATION")
            val pkgs = pm.getInstalledPackages(0)
            for (pi in pkgs) {
                val ai = pi.applicationInfo ?: continue
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val label = try { pm.getApplicationLabel(ai).toString() }
                    catch (_: Exception) { pi.packageName }
                out.add(AppEntry(pi.packageName, label, system))
            }
        } catch (_: Exception) { }
        return out.sortedBy { it.label.lowercase() }
    }

    /** Launchable (launcher-icon) apps — the rule editor's picker. */
    fun launchableApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (found.containsKey(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() }
                    catch (_: Exception) { pkg }
                found[pkg] = AppEntry(pkg, label, false)
            }
        } catch (_: Exception) { }
        return found.values.sortedBy { it.label.lowercase() }
    }

    // ---------------- grace ----------------

    /** Package whose current foreground stint was just unlocked with
     *  the "immediately" preset: it must not re-cover until the user
     *  leaves the app and comes back. */
    @Volatile var unlockedStintPkg: String? = null

    private fun sessionGraces(ctx: Context): MutableSet<String> {
        val out = mutableSetOf<String>()
        try {
            val arr = JSONArray(
                prefs(ctx).getString("grace_session", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)
                if (p.isNotEmpty()) out.add(p)
            }
        } catch (_: Exception) { }
        return out
    }

    private fun saveSessionGraces(ctx: Context, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs(ctx).edit().putString("grace_session", arr.toString()).apply()
    }

    /** Grants the post-unlock grace for [pkg]. [session] = the peer
     *  one-session grant, which ignores the configured preset. */
    fun grantGrace(ctx: Context, pkg: String, session: Boolean = false) {
        when {
            session || graceSec(ctx) == -1 -> {
                val set = sessionGraces(ctx)
                if (set.add(pkg)) saveSessionGraces(ctx, set)
            }
            graceSec(ctx) > 0 -> prefs(ctx).edit().putLong(
                "grace_until_$pkg",
                System.currentTimeMillis() + graceSec(ctx) * 1000L).apply()
            else -> unlockedStintPkg = pkg
        }
    }

    fun graceValid(ctx: Context, pkg: String): Boolean {
        if (sessionGraces(ctx).contains(pkg)) return true
        return prefs(ctx).getLong("grace_until_$pkg", 0L) >
            System.currentTimeMillis()
    }

    fun clearGrace(ctx: Context, pkg: String) {
        val set = sessionGraces(ctx)
        if (set.remove(pkg)) saveSessionGraces(ctx, set)
        prefs(ctx).edit().remove("grace_until_$pkg").apply()
        if (unlockedStintPkg == pkg) unlockedStintPkg = null
    }

    // ---------------- enforcement ----------------

    @Volatile var foregroundPkg: String = ""

    /** Management screens set this so the poll never covers them. */
    @Volatile var managementOpen: Boolean = false

    private val EXEMPT_ACTIVITIES = listOf(
        "AppLockActivity", "AppLockerActivity", "AppPickerActivity",
        "LockActivity", "PinActivity")

    fun callActive(ctx: Context): Boolean = try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
            as TelephonyManager
        @Suppress("DEPRECATION")
        tm.callState != TelephonyManager.CALL_STATE_IDLE
    } catch (_: Exception) { false }

    /**
     * The one enforcement entry point, fed by the A11y live path
     * (with the window's class name) and by the 5 s poll (without).
     */
    fun onForeground(ctx: Context, pkg: String, className: String?) {
        if (pkg.isEmpty()) return
        val app = ctx.applicationContext
        if (pkg != foregroundPkg) {
            val stint = unlockedStintPkg
            if (stint != null && stint != pkg) unlockedStintPkg = null
            foregroundPkg = pkg
        }
        if (!isEnabled(app) || pinHash(app).isEmpty()) {
            AppLockOverlay.hide(app)
            return
        }
        if (pkg == app.packageName) {
            val exempt = managementOpen || (className != null &&
                EXEMPT_ACTIVITIES.any { className.endsWith(it) })
            if (exempt) {
                AppLockOverlay.hide(app)
                return
            }
            // Our own overlay window ALSO reports this package when
            // it surfaces — while a cover is up, own-package events
            // must change nothing, or the cover would hide itself.
            if (AppLockOverlay.root != null) return
            if (!protectSelfOn(app)) {
                AppLockOverlay.hide(app)
                return
            }
            // Protect on: PhoneLink itself is a lock target here.
        }
        // Never cover the system UI, the launcher, the dialer or the
        // in-call UI — whatever the locked set says.
        if (pkg in STATIC_NEVER || hardExcluded(app, pkg)) {
            AppLockOverlay.hide(app)
            return
        }
        // A live call owns the screen; never cover anything mid-call.
        if (callActive(app)) {
            AppLockOverlay.hide(app)
            return
        }
        if (isLocked(app, pkg)) {
            if (graceValid(app, pkg) || unlockedStintPkg == pkg) {
                AppLockOverlay.hide(app)
                return
            }
            AppLockOverlay.show(app, pkg)
        } else {
            AppLockOverlay.hide(app)
        }
    }

    /** 5-second poll fallback (LinkService fast loop). */
    fun pollCheck(ctx: Context) {
        val app = ctx.applicationContext
        if (!isEnabled(app) || pinHash(app).isEmpty()) return
        if (managementOpen) return
        val pkg = Automation.foregroundPkg(app)
        if (pkg.isEmpty()) return
        if (pkg == app.packageName && AppLockActivity.resumedPkg != null) return
        onForeground(app, pkg, null)
    }

    fun onScreenOff(ctx: Context) {
        val app = ctx.applicationContext
        // "Until screen off" graces (and peer one-session grants) end
        // here; timed graces keep their own clocks.
        saveSessionGraces(app, emptySet())
        unlockedStintPkg = null
        AppLockOverlay.hide(app)
    }

    // ---------------- peer approval ----------------

    /** Sends the unlock request to the peer as an APPLOCK inbox
     *  event; returns the request id. */
    fun askPeer(ctx: Context, pkg: String): String {
        val app = ctx.applicationContext
        val req = Relay.newId()
        prefs(app).edit()
            .putString("pending_req", req)
            .putString("pending_pkg", pkg)
            .putLong("pending_at", System.currentTimeMillis())
            .apply()
        Relay.send(app, Relay.TYPE_APPLOCK,
            "🔓 অ্যাপ আনলকের অনুরোধ",
            appLabel(app, pkg) + " (" + pkg + ")",
            JSONObject().put("lockpkg", pkg).put("lockreq", req))
        SecLog.add(app, "App Locker: পিয়ারের কাছে আনলক অনুরোধ — $pkg")
        return req
    }

    fun pending(ctx: Context): Pair<String, String>? {
        val req = prefs(ctx).getString("pending_req", "") ?: ""
        val pkg = prefs(ctx).getString("pending_pkg", "") ?: ""
        return if (req.isNotEmpty() && pkg.isNotEmpty()) req to pkg else null
    }

    private fun clearPending(ctx: Context, pkg: String) {
        val p = pending(ctx) ?: return
        if (p.second == pkg) prefs(ctx).edit()
            .remove("pending_req").remove("pending_pkg").apply()
    }

    /** Command-topic APPLOCK_GRANT landed (PIN gate already passed
     *  in LinkService.pinOk): one-session grace + uncover. */
    fun grantFromPeer(ctx: Context, pkg: String, req: String) {
        val app = ctx.applicationContext
        val p = pending(app)
        if (p != null && req.isNotEmpty() && p.first != req) return // stale
        clearPending(app, pkg)
        grantGrace(app, pkg, session = true)
        if (AppLockOverlay.forPkg == pkg) AppLockOverlay.hide(app)
    }

    fun denyFromPeer(ctx: Context, pkg: String, req: String) {
        val app = ctx.applicationContext
        val p = pending(app)
        if (p != null && req.isNotEmpty() && p.first != req) return // stale
        clearPending(app, pkg)
        prefs(app).edit()
            .putString("denied_pkg", pkg)
            .putString("denied_req", req)
            .putLong("denied_at", System.currentTimeMillis())
            .apply()
    }

    /** (pkg, at) of the latest peer denial, for the lock screen. */
    fun deniedInfo(ctx: Context): Pair<String, Long> {
        val p = prefs(ctx)
        return (p.getString("denied_pkg", "") ?: "") to
            p.getLong("denied_at", 0L)
    }

    // ---------------- intruder flow ----------------

    /**
     * Wrong-PIN limit reached on the lock screen. The selfie rides
     * the EXISTING intruder-selfie machinery — the same
     * SessionState.snapFront flag + SnapService one-shot that
     * AdminReceiver.onPasswordFailed uses; SnapService captures the
     * front camera and uploads through Relay.postFile.
     */
    fun onIntruder(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        val label = appLabel(app, pkg)
        SecLog.add(app, "App Locker: $label-এ ভুল PIN-এর সীমা ছাড়িয়েছে")
        Automation.log(app, "🔒 App Locker: $label — ভুল PIN-এর সীমা")
        Automation.onEvent(app, "applock_failed", JSONObject()
            .put("pkg", pkg).put("app", label)
            .put("title", label).put("body", pkg))
        if (Config.pairCode(app).isNotBlank()) {
            Relay.send(app, Relay.TYPE_SYSTEM, "🚨 App Locker: ভুল PIN",
                "$label খুলতে বারবার ভুল PIN দেওয়া হয়েছে।")
            try {
                SessionState.snapFront = true
                val svc = Intent(app, SnapService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(svc)
                } else {
                    app.startService(svc)
                }
            } catch (_: Exception) { }
        }
    }

    // ---------------- automation + installs ----------------

    /** lock_app action: add to the locked set and lock it now. */
    fun lockFromAction(ctx: Context, pkgRaw: String): Boolean {
        val pkg = pkgRaw.trim()
        if (pkg.isEmpty()) return false
        if (!addLocked(ctx, pkg)) return false
        clearGrace(ctx, pkg)
        if (foregroundPkg == pkg) onForeground(ctx, pkg, null)
        return true
    }

    /** unlock_app action: drop from the locked set and uncover. */
    fun unlockFromAction(ctx: Context, pkgRaw: String): Boolean {
        val pkg = pkgRaw.trim()
        if (pkg.isEmpty() || !isInSet(ctx, pkg)) return false
        removeLocked(ctx, pkg)
        return true
    }

    /** PackageReceiver hook: auto-lock fresh installs when enabled. */
    fun onPackageAdded(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        if (!lockNewOn(app) || !isEnabled(app) || pinHash(app).isEmpty()) return
        if (pickerExcluded(app, pkg) || isInSet(app, pkg)) return
        if (!addLocked(app, pkg)) return
        val label = appLabel(app, pkg)
        SecLog.add(app, "App Locker: নতুন অ্যাপ নিজে নিজে লক — $label ($pkg)")
        Automation.localNotify(app,
            "🔒 App Locker: নতুন অ্যাপ \"$label\" লক-তালিকায় যোগ হয়েছে।")
    }

    // ---------------- themed context for the overlay ----------------

    /** A context carrying the app's own theme choice (theme_mode +
     *  accent prefs), so the overlay lock screen follows the app
     *  theme — including its day/night variant — not just the OS. */
    fun themedContext(ctx: Context): Context {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("phonelink_cfg",
            Context.MODE_PRIVATE)
        val sysNight = app.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        val night = when (prefs.getString("theme_mode", "system")) {
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            "light" -> Configuration.UI_MODE_NIGHT_NO
            else -> sysNight
        }
        val cfg = Configuration(app.resources.configuration)
        cfg.uiMode = (cfg.uiMode and
            Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        val confCtx = app.createConfigurationContext(cfg)
        // v2.25.1: the overlay must follow the chosen app theme too,
        // not just the accent — otherwise AppLockUi's theme colours
        // would resolve against the base theme in the overlay host.
        // Same precedence as LocalizedActivity: a non-default theme
        // carries its own accent; accent applies only under Emerald.
        val style = when (prefs.getString("app_theme", "emerald_dark")) {
            "aurora" -> R.style.Theme_PhoneLinkAurora
            "ocean_deep" -> R.style.Theme_PhoneLinkOceanDeep
            "midnight_sapphire" -> R.style.Theme_PhoneLinkMidnightSapphire
            "sunset_ember" -> R.style.Theme_PhoneLinkSunsetEmber
            "rose_quartz" -> R.style.Theme_PhoneLinkRoseQuartz
            "forest_mist" -> R.style.Theme_PhoneLinkForestMist
            "graphite_mono" -> R.style.Theme_PhoneLinkGraphiteMono
            else -> when (prefs.getString("accent", "emerald")) {
                "blue" -> R.style.Theme_PhoneLinkBlue
                "purple" -> R.style.Theme_PhoneLinkPurple
                "amber" -> R.style.Theme_PhoneLinkAmber
                "rose" -> R.style.Theme_PhoneLinkRose
                else -> R.style.Theme_PhoneLink
            }
        }
        return ContextThemeWrapper(confCtx, style)
    }
}

/**
 * The overlay host for the lock screen. Idempotent per package;
 * falls back to AppLockActivity when the overlay grant is missing.
 */
object AppLockOverlay {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile var root: View? = null
        private set
    @Volatile var forPkg: String? = null
        private set
    private var ui: AppLockUi? = null
    @Volatile private var lastFallbackAt = 0L
    @Volatile private var lastFallbackPkg: String? = null

    fun show(ctx: Context, pkg: String) {
        handler.post {
            val app = ctx.applicationContext
            if (root != null && forPkg == pkg) return@post
            if (AppLockActivity.resumedPkg == pkg) return@post
            hideNow(app)
            if (!Settings.canDrawOverlays(app)) {
                val now = System.currentTimeMillis()
                if (lastFallbackPkg == pkg &&
                    now - lastFallbackAt < 3_000) return@post
                lastFallbackPkg = pkg
                lastFallbackAt = now
                try {
                    app.startActivity(Intent(app, AppLockActivity::class.java)
                        .putExtra("pkg", pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                return@post
            }
            try {
                val themed = AppLocker.themedContext(app)
                val view = LayoutInflater.from(themed)
                    .inflate(R.layout.view_applock, null)
                val wm = app.getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT)
                wm.addView(view, lp)
                root = view
                forPkg = pkg
                ui = AppLockUi(themed, view, pkg, AppLockUi.Host.OVERLAY,
                    onUnlocked = { hide(app) },
                    onBiometric = {
                        // BiometricPrompt needs an activity: swap the
                        // overlay for the same lock screen hosted by
                        // AppLockActivity (it prompts immediately).
                        hide(app)
                        try {
                            app.startActivity(
                                Intent(app, AppLockActivity::class.java)
                                    .putExtra("pkg", pkg)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) { }
                    })
                view.isFocusableInTouchMode = true
                view.requestFocus()
                view.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_DOWN) {
                        ui?.goHome()
                        true
                    } else false
                }
            } catch (_: Exception) {
                root = null
                forPkg = null
                ui = null
            }
        }
    }

    fun hide(ctx: Context) {
        handler.post { hideNow(ctx.applicationContext) }
    }

    private fun hideNow(app: Context) {
        val view = root ?: return
        try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE)
                as WindowManager
            wm.removeView(view)
        } catch (_: Exception) { }
        ui?.destroy()
        ui = null
        root = null
        forPkg = null
    }
}
