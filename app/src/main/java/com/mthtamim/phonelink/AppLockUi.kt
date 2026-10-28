package com.mthtamim.phonelink

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.18: drives the App Locker lock screen (layout/view_applock)
 * for both hosts — the overlay window and AppLockActivity — so the
 * PIN pad, dots, disguise cover and peer-approval states behave
 * identically either way. [onBiometric] differs per host: the
 * overlay swaps itself for the activity (BiometricPrompt needs
 * one); the activity shows the prompt in place.
 */
class AppLockUi(
    private val ctx: Context,
    private val root: View,
    private val pkg: String,
    private val host: Host,
    private val onUnlocked: () -> Unit,
    private val onBiometric: () -> Unit
) {
    enum class Host { OVERLAY, ACTIVITY }

    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private val shownAt = System.currentTimeMillis()
    private val entered = StringBuilder()
    private var attempts = 0
    private var intruderDone = false
    private var deniedShown = false
    private var asked = false
    private val pinLen = AppLocker.pinLen(ctx)

    private val tvClock: TextView = root.findViewById(R.id.tvLockClock)
    private val tvDate: TextView = root.findViewById(R.id.tvLockDate)
    private val tvMsg: TextView = root.findViewById(R.id.tvLockMsg)
    private val llDots: LinearLayout = root.findViewById(R.id.llLockDots)
    private val llMain: View = root.findViewById(R.id.llLockMain)
    private val cardFake: MaterialCardView = root.findViewById(R.id.cardLockFake)
    private val btnAskPeer: MaterialButton = root.findViewById(R.id.btnLockAskPeer)
    private val dots = mutableListOf<View>()
    private val dotDraws = mutableListOf<GradientDrawable>()

    private val clockTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = Date()
            tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            tvDate.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
            handler.postDelayed(this, 1000)
        }
    }

    /** Watches for the peer's verdict while the cover is up: a grant
     *  lands as a session grace (unlock), a denial shows its line. */
    private val peerTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (AppLocker.graceValid(ctx, pkg)) {
                destroy()
                onUnlocked()
                return
            }
            if (!deniedShown) {
                val (dpkg, at) = AppLocker.deniedInfo(ctx)
                if (dpkg == pkg && at > shownAt) {
                    deniedShown = true
                    setMsg(ctx.getString(R.string.alk_lock_denied), error = true)
                    btnAskPeer.isEnabled = true
                    btnAskPeer.text = ctx.getString(R.string.alk_lock_ask_peer)
                    asked = false
                }
            }
            handler.postDelayed(this, 700)
        }
    }

    init {
        val label = AppLocker.appLabel(ctx, pkg)
        root.findViewById<TextView>(R.id.tvLockAppName).text = label
        val icon = AppLocker.appIcon(ctx, pkg)
        val iv = root.findViewById<ImageView>(R.id.ivLockIcon)
        if (icon != null) iv.setImageDrawable(icon)
        else iv.setImageResource(R.drawable.ic_grid)

        // PIN dots (count = the locker PIN's own length)
        val density = ctx.resources.displayMetrics.density
        val size = (13 * density).toInt()
        val margin = (5 * density).toInt()
        repeat(pinLen) {
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setStroke((1.5f * density).toInt(),
                ThemeColors.outline(ctx))
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, 0, margin, 0)
            v.layoutParams = lp
            v.background = d
            llDots.addView(v)
            dots.add(v)
            dotDraws.add(d)
        }

        val digitIds = listOf(R.id.keyD1, R.id.keyD2, R.id.keyD3,
            R.id.keyD4, R.id.keyD5, R.id.keyD6, R.id.keyD7, R.id.keyD8,
            R.id.keyD9, R.id.keyD0)
        val digitVals = listOf("1", "2", "3", "4", "5", "6", "7", "8",
            "9", "0")
        for (i in digitIds.indices) {
            root.findViewById<MaterialButton>(digitIds[i])
                .setOnClickListener { onDigit(digitVals[i]) }
        }
        root.findViewById<ImageButton>(R.id.keyDel)
            .setOnClickListener { onDelete() }

        // Biometric key: only when the locker setting allows it and
        // the hardware can actually authenticate.
        val bioOk = AppLocker.biometricOn(ctx) &&
            BiometricManager.from(ctx).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        val keyBio = root.findViewById<ImageButton>(R.id.keyBio)
        keyBio.visibility = if (bioOk) View.VISIBLE else View.INVISIBLE
        if (bioOk) keyBio.setOnClickListener { onBiometric() }

        // Ask peer: only when the setting is on and a peer is paired.
        val peerOk = AppLocker.peerOn(ctx) &&
            Config.pairCode(ctx).isNotBlank()
        btnAskPeer.visibility = if (peerOk) View.VISIBLE else View.GONE
        if (peerOk) btnAskPeer.setOnClickListener {
            if (asked) return@setOnClickListener
            asked = true
            AppLocker.askPeer(ctx, pkg)
            btnAskPeer.isEnabled = false
            btnAskPeer.text = ctx.getString(R.string.alk_lock_asked)
            setMsg(ctx.getString(R.string.alk_lock_asked), error = false)
        }

        // Disguise cover: fake system ANR card first; a long-press on
        // its title reveals the real PIN pad. "Close app" escapes to
        // Home (the app stays locked); "Wait" does nothing, like the
        // real dialog.
        if (AppLocker.disguiseOn(ctx)) {
            llMain.visibility = View.GONE
            cardFake.visibility = View.VISIBLE
            val tvFake = root.findViewById<TextView>(R.id.tvFakeTitle)
            tvFake.text = ctx.getString(R.string.alk_fake_title, label)
            val reveal = View.OnLongClickListener {
                cardFake.visibility = View.GONE
                llMain.visibility = View.VISIBLE
                animateIn()
                true
            }
            tvFake.setOnLongClickListener(reveal)
            cardFake.setOnLongClickListener(reveal)
            root.findViewById<MaterialButton>(R.id.btnFakeWait)
                .setOnClickListener { /* stays, like the real dialog */ }
            root.findViewById<MaterialButton>(R.id.btnFakeClose)
                .setOnClickListener { goHome() }
        } else {
            animateIn()
        }

        handler.post(clockTick)
        handler.postDelayed(peerTick, 700)
    }

    private fun animateIn() {
        llMain.alpha = 0f
        llMain.translationY = 26f * ctx.resources.displayMetrics.density
        llMain.animate().alpha(1f).translationY(0f)
            .setDuration(230).start()
    }

    private fun setMsg(text: String, error: Boolean) {
        tvMsg.text = text
        // v2.25.1: non-error message follows the theme variant; error
        // keeps the semantic status red, intentionally not themed.
        tvMsg.setTextColor(if (error) ContextCompat.getColor(ctx, R.color.status_bad)
            else ThemeColors.onSurfaceVariant(ctx))
    }

    private fun redrawDots(error: Boolean = false) {
        // v2.25.1: filled PIN dots follow the theme accent
        // (Emerald: #10B981 = pl_mint, unchanged).
        val filled = ThemeColors.primary(ctx)
        val bad = ContextCompat.getColor(ctx, R.color.status_bad)
        for (i in dots.indices) {
            val d = dotDraws[i]
            when {
                error && i < entered.length -> d.setColor(bad)
                i < entered.length -> d.setColor(filled)
                else -> d.setColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    private fun onDigit(d: String) {
        if (entered.length >= pinLen) return
        entered.append(d)
        root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        redrawDots()
        // subtle pop on the dot that just filled
        val dot = dots.getOrNull(entered.length - 1)
        if (dot != null) {
            dot.scaleX = 1.45f
            dot.scaleY = 1.45f
            dot.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        if (entered.length == pinLen) {
            handler.postDelayed({ verify() }, 150)
        }
    }

    private fun onDelete() {
        if (entered.isNotEmpty()) {
            entered.deleteCharAt(entered.length - 1)
            redrawDots()
        }
    }

    private fun verify() {
        if (destroyed) return
        // A delete inside the auto-submit delay must not become a
        // false wrong attempt.
        if (entered.length != pinLen) return
        if (Crypto.pinHash(entered.toString()) == AppLocker.pinHash(ctx)) {
            AppLocker.grantGrace(ctx, pkg)
            destroy()
            onUnlocked()
        } else {
            wrongPin()
        }
    }

    private fun wrongPin() {
        attempts++
        setMsg(ctx.getString(R.string.alk_lock_wrong) + " • " +
            ctx.getString(R.string.alk_lock_attempts, attempts), error = true)
        redrawDots(error = true)
        ObjectAnimator.ofFloat(llDots, "translationX",
            0f, -16f, 16f, -11f, 11f, -6f, 0f).apply {
            duration = 340
            start()
        }
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(70,
                    VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(70)
            }
        } catch (_: Exception) { }
        handler.postDelayed({
            if (!destroyed) {
                entered.setLength(0)
                redrawDots()
            }
        }, 380)
        if (attempts >= AppLocker.INTRUDER_THRESHOLD &&
            AppLocker.intruderOn(ctx) && !intruderDone) {
            intruderDone = true
            AppLocker.onIntruder(ctx, pkg)
        }
    }

    /** Escape path shared by both hosts: send the user Home; the
     *  locked app stays backgrounded and re-covers on return. */
    fun goHome() {
        if (A11yService.globalKey("home")) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
    }
}
