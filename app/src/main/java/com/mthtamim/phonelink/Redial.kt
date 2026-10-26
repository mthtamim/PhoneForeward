package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.media.AudioManager
import com.autotap.app.Region
import com.autotap.app.TapAccessibilityService

/**
 * v2.22: "উত্তর না দেওয়া পর্যন্ত বারবার কল" — redial until
 * answered, shared by the Dashboard card (manual) and the
 * redial_control rule action. One session at a time; the
 * session lives in its own prefs so it survives a process
 * restart, and Automation.tick (~20 s) drives it — so the
 * gap is approximate (the first tick after the gap), never
 * exact.
 *
 * State machine: calling -> ended (25 s log settle) -> wait
 * (gap) -> calling ... A call ends when TelephonyManager
 * reports IDLE. "Answered" is read from the call log: the
 * latest outgoing call to the number with duration > 0.
 * A duration of 0 (or no fresh log entry) means not
 * answered. The session stops on its own when answered,
 * when the attempt cap is hit, when CALL_PHONE, call log
 * or phone-state permission is lost, or when the user
 * stops it. Starting a new session replaces a running one.
 *
 * v2.23: ring limit (seconds, 0 = none) — when a call has
 * stayed OFFHOOK past the limit, the merged AutoTap
 * Accessibility engine taps the dialer's "End call" button.
 * The tap is gated twice: TapAccessibilityService must be
 * enabled, and the foreground app must be a dialer. It is
 * gated a third time by a ringing guard — the tap fires
 * only while the dialer still shows Dialing, Calling or
 * Ringing. Once answered, the in-call screen shows a timer
 * instead, so a missing ringing label means "do not tap":
 * a live conversation is never cut by the ring limit.
 * On any failed gate the reason is logged once per call
 * and the session waits for the natural end (carrier
 * timeout, decline, voicemail). The dialer UI varies, so
 * the ring limit is best-effort until phone-tested.
 * onanswer resume = an answered call counts as one attempt
 * and the session continues after the gap; stop (default)
 * ends the session on answer. speakerOn sets the
 * speakerphone while a redial call is OFFHOOK and restores
 * it to off when the call ends — Redial only touches the
 * speakerphone when the toggle is ON.
 */
object Redial {
    const val OK = 0
    const val ERR_NUMBER = 1
    const val ERR_CALL_PHONE = 2
    const val ERR_CALL_LOG = 3

    private const val PREFS = "phonelink_redial"
    private const val LOG_SETTLE_MS = 25_000L
    private const val PLACE_GUARD_MS = 10_000L
    private const val TAP_OK = 0
    private const val TAP_NO_A11Y = 1
    private const val TAP_NO_BUTTON = 2
    private const val TAP_WRONG_APP = 3
    private const val TAP_MAYBE_ANSWERED = 4

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun digits(s: String): String = s.filter { it.isDigit() }

    fun isActive(ctx: Context): Boolean =
        sp(ctx).getBoolean("active", false)

    fun sessionNumber(ctx: Context): String =
        sp(ctx).getString("number", "") ?: ""

    fun sessionSim(ctx: Context): String =
        sp(ctx).getString("sim", "default") ?: "default"

    fun sessionGap(ctx: Context): Int = sp(ctx).getInt("gapSec", 30)

    fun sessionMax(ctx: Context): Int = sp(ctx).getInt("maxAtt", 20)

    fun sessionRing(ctx: Context): Int = sp(ctx).getInt("ringSec", 0)

    fun sessionOnanswer(ctx: Context): String =
        sp(ctx).getString("onanswer", "stop") ?: "stop"

    fun sessionSpeaker(ctx: Context): Boolean =
        sp(ctx).getBoolean("speakerOn", false)

    fun statusText(ctx: Context): String {
        val prefs = sp(ctx)
        return if (prefs.getBoolean("active", false)) {
            ctx.getString(R.string.dash_redial_active,
                prefs.getInt("attempts", 0), prefs.getInt("maxAtt", 20))
        } else {
            val last = prefs.getString("lastResult", "") ?: ""
            if (last.isNotEmpty()) last
            else ctx.getString(R.string.dash_redial_idle)
        }
    }

    /** Places the first call immediately; returns an error code.
     *  ringSec 0 = no ring limit; onanswer "resume" continues
     *  after an answered call, anything else stops on answer. */
    fun start(ctx: Context, number: String, sim: String,
              gapSec: Int, maxAtt: Int, ringSec: Int,
              onanswer: String, speakerOn: Boolean): Int {
        val num = number.filter { it.isDigit() || it == '+' }
        if (num.isEmpty()) return ERR_NUMBER
        if (ctx.checkSelfPermission(
                android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED) return ERR_CALL_PHONE
        if (ctx.checkSelfPermission(
                android.Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED) return ERR_CALL_LOG
        val gap = gapSec.coerceIn(10, 3600)
        val max = maxAtt.coerceIn(1, 999)
        val now = System.currentTimeMillis()
        sp(ctx).edit()
            .putBoolean("active", true)
            .putString("number", num)
            .putString("sim", sim)
            .putInt("gapSec", gap)
            .putInt("maxAtt", max)
            .putInt("ringSec", ringSec.coerceIn(0, 300))
            .putString("onanswer",
                if (onanswer == "resume") "resume" else "stop")
            .putBoolean("speakerOn", speakerOn)
            .putInt("attempts", 1)
            .putString("state", "calling")
            .putLong("lastCallAt", now)
            .putLong("endedAt", 0L)
            .putLong("nextAt", 0L)
            .putBoolean("ringTapTried", false)
            .putBoolean("speakerApplied", false)
            .apply()
        Automation.log(ctx, "redial: " + num + " — 1/" + max)
        placeCall(ctx, num, sim)
        return OK
    }

    /** reason: user | answered | max | perm. No-op when idle. */
    fun stop(ctx: Context, reason: String) {
        val prefs = sp(ctx)
        if (!prefs.getBoolean("active", false)) return
        if (prefs.getBoolean("speakerApplied", false))
            setSpeaker(ctx, false)
        val attempts = prefs.getInt("attempts", 0)
        val text = when (reason) {
            "answered" -> ctx.getString(
                R.string.redial_log_answered, attempts)
            "max" -> ctx.getString(R.string.redial_log_max)
            "perm" -> ctx.getString(R.string.redial_log_perm)
            else -> ctx.getString(R.string.redial_log_stopped)
        }
        prefs.edit().putBoolean("active", false)
            .putString("state", "idle")
            .putBoolean("speakerApplied", false)
            .putString("lastResult", text)
            .apply()
        Automation.log(ctx, "redial: " + text)
    }

    fun placeCall(ctx: Context, number: String, sim: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL,
                Uri.parse("tel:" + number))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != "default") {
                val slot = if (sim == "sim1") 0 else 1
                Automation.simAccountForSlot(ctx, slot)?.let {
                    intent.putExtra(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
                }
            }
            ctx.startActivity(intent)
        } catch (_: Exception) { }
    }

    /** Driven by Automation.tick (~20 s). Never throws. */
    fun poll(ctx: Context) {
        try {
            pollInner(ctx)
        } catch (_: Exception) { }
    }

    private fun pollInner(ctx: Context) {
        val prefs = sp(ctx)
        if (!prefs.getBoolean("active", false)) return
        if (ctx.checkSelfPermission(
                android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(
                android.Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED) {
            stop(ctx, "perm")
            return
        }
        val tmState = try {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE)
                as TelephonyManager).callState
        } catch (_: Exception) {
            stop(ctx, "perm")
            return
        }
        // The redial call ended (or never started): whatever
        // speaker state Redial set goes back to off, even
        // when the session itself continues (resume mode).
        if (prefs.getBoolean("speakerApplied", false) &&
            tmState == TelephonyManager.CALL_STATE_IDLE) {
            prefs.edit().putBoolean("speakerApplied", false).apply()
            setSpeaker(ctx, false)
        }
        val now = System.currentTimeMillis()
        val state = prefs.getString("state", "idle") ?: "idle"
        when (state) {
            "calling" -> {
                if (tmState == TelephonyManager.CALL_STATE_IDLE &&
                    now - prefs.getLong("lastCallAt", 0) >=
                    PLACE_GUARD_MS) {
                    prefs.edit().putString("state", "ended")
                        .putLong("endedAt", now).apply()
                } else if (tmState !=
                    TelephonyManager.CALL_STATE_IDLE) {
                    // OFFHOOK: the redial call is ringing or live.
                    if (prefs.getBoolean("speakerOn", false)) {
                        setSpeaker(ctx, true)
                        prefs.edit()
                            .putBoolean("speakerApplied", true).apply()
                    }
                    val ring = prefs.getInt("ringSec", 0)
                    if (ring > 0 &&
                        !prefs.getBoolean("ringTapTried", false) &&
                        now - prefs.getLong("lastCallAt", 0) >=
                        ring * 1000L) {
                        prefs.edit()
                            .putBoolean("ringTapTried", true).apply()
                        val tap = attemptEndCallTap(ctx)
                        val reason = when (tap.first) {
                            TAP_OK -> ctx.getString(
                                R.string.redial_log_ring_ending)
                            TAP_NO_A11Y -> ctx.getString(
                                R.string.redial_log_ring_no_a11y)
                            TAP_WRONG_APP -> ctx.getString(
                                R.string.redial_log_ring_wrong_app,
                                tap.second)
                            TAP_MAYBE_ANSWERED -> ctx.getString(
                                R.string.redial_log_ring_answered)
                            else -> ctx.getString(
                                R.string.redial_log_ring_no_button)
                        }
                        Automation.log(ctx, "redial: " + reason)
                    }
                }
            }
            "ended" -> {
                if (tmState != TelephonyManager.CALL_STATE_IDLE) {
                    prefs.edit().putString("state", "calling").apply()
                    return
                }
                if (now - prefs.getLong("endedAt", 0) < LOG_SETTLE_MS)
                    return
                val number = prefs.getString("number", "") ?: ""
                val dur = lastOutgoingDuration(ctx, number)
                if (dur != null && dur > 0) {
                    val attempts = prefs.getInt("attempts", 0)
                    val max = prefs.getInt("maxAtt", 20)
                    if (prefs.getString("onanswer", "stop") ==
                        "resume") {
                        Automation.log(ctx, "redial: " +
                            ctx.getString(
                                R.string.redial_log_answered_resume,
                                attempts, max))
                    } else {
                        stop(ctx, "answered")
                        return
                    }
                }
                val attempts = prefs.getInt("attempts", 0)
                val max = prefs.getInt("maxAtt", 20)
                if (attempts >= max) {
                    stop(ctx, "max")
                    return
                }
                prefs.edit().putString("state", "wait")
                    .putLong("nextAt",
                        now + prefs.getInt("gapSec", 30) * 1000L)
                    .apply()
            }
            "wait" -> {
                if (tmState != TelephonyManager.CALL_STATE_IDLE) return
                val nextAt = prefs.getLong("nextAt", 0)
                if (now < nextAt) return
                val attempts = prefs.getInt("attempts", 0)
                val max = prefs.getInt("maxAtt", 20)
                if (attempts >= max) {
                    stop(ctx, "max")
                    return
                }
                val number = prefs.getString("number", "") ?: ""
                val sim = prefs.getString("sim", "default") ?: "default"
                prefs.edit().putInt("attempts", attempts + 1)
                    .putLong("lastCallAt", now)
                    .putBoolean("ringTapTried", false)
                    .putString("state", "calling").apply()
                Automation.log(ctx, "redial: " + number + " — " +
                    (attempts + 1) + "/" + max)
                placeCall(ctx, number, sim)
            }
            else -> { }
        }
    }

    /** Speakerphone is only touched when the session asked
     *  for it; otherwise Redial never changes audio routing. */
    private fun setSpeaker(ctx: Context, on: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager
            am.setSpeakerphoneOn(on)
        } catch (_: Exception) { }
    }

    /** Ends the redial call via the merged AutoTap engine:
     *  finds the dialer's "End call" button and taps it.
     *  Returns the tap code plus the foreground package for
     *  the log. The ringing guard is the safety: the tap
     *  fires only while the dialer still shows Dialing,
     *  Calling or Ringing — once answered, the in-call
     *  screen shows a timer instead, and a missing ringing
     *  label means "do not tap", so a live conversation is
     *  never cut. A dialer that shows neither label falls
     *  back to waiting for the natural end. */
    private fun attemptEndCallTap(ctx: Context): Pair<Int, String> {
        val svc = TapAccessibilityService.instance
            ?: return Pair(TAP_NO_A11Y, "")
        val pkg = try { svc.currentPackage() }
            catch (_: Exception) { "" }
        val p = pkg.lowercase()
        if (!p.contains("dialer") && !p.contains("incall"))
            return Pair(TAP_WRONG_APP, pkg)
        val ringing = try {
            svc.findText("Dialing", Region.FULL) != null ||
                svc.findText("Calling", Region.FULL) != null ||
                svc.findText("Ringing", Region.FULL) != null
        } catch (_: Exception) { false }
        if (!ringing) return Pair(TAP_MAYBE_ANSWERED, pkg)
        val pt = try { svc.findText("End call", Region.FULL) }
            catch (_: Exception) { null }
            ?: return Pair(TAP_NO_BUTTON, pkg)
        val ok = try { svc.tap(pt[0], pt[1], 100L) }
            catch (_: Exception) { false }
        return if (ok) Pair(TAP_OK, pkg)
            else Pair(TAP_NO_BUTTON, pkg)
    }

    /** Duration of the latest outgoing call to [number] in the
     *  last 15 minutes, or null when no such entry exists yet. */
    private fun lastOutgoingDuration(
        ctx: Context, number: String): Long? {
        val target = digits(number).takeLast(10)
        if (target.isEmpty()) return null
        try {
            val cur = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION,
                    CallLog.Calls.DATE),
                CallLog.Calls.TYPE + "=?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                CallLog.Calls.DATE + " DESC")
            cur?.use {
                val now = System.currentTimeMillis()
                var seen = 0
                while (it.moveToNext() && seen < 8) {
                    seen++
                    val date = it.getLong(2)
                    if (now - date > 15 * 60_000L) break
                    val n = digits(it.getString(0) ?: "")
                    if (n.takeLast(10) == target) return it.getLong(1)
                }
            }
        } catch (_: Exception) { }
        return null
    }
}
