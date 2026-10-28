package com.mthtamim.phonelink

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * v2.18 App Locker management. Reached from Settings → Security &
 * Privacy. The locker has its OWN PIN (Crypto.pinHash in the
 * phonelink_locker prefs) — the app-lock PIN is never read here.
 * Grants are shown as state lines only; the grant itself lives in
 * PermissionsActivity (single home rule).
 */
class AppLockerActivity : LocalizedActivity() {

    private lateinit var swMaster: SwitchMaterial
    private lateinit var tvPinState: TextView
    private lateinit var btnPin: MaterialButton
    private lateinit var tvOverlayState: TextView
    private lateinit var tvA11yState: TextView
    private lateinit var llLocked: LinearLayout
    private lateinit var tvNoLocked: TextView
    private lateinit var btnGrace: MaterialButton
    private var binding = false
    private var pendingEnable = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_applocker)

        swMaster = findViewById(R.id.swLockerMaster)
        tvPinState = findViewById(R.id.tvLockerPinState)
        btnPin = findViewById(R.id.btnLockerPin)
        tvOverlayState = findViewById(R.id.tvLockerOverlayState)
        tvA11yState = findViewById(R.id.tvLockerA11yState)
        llLocked = findViewById(R.id.llLockedApps)
        tvNoLocked = findViewById(R.id.tvNoLocked)
        btnGrace = findViewById(R.id.btnLockerGrace)

        findViewById<View>(R.id.btnLockerBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<View>(R.id.btnLockerPerms).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        btnPin.setOnClickListener { showPinDialog() }
        btnGrace.setOnClickListener { showGraceDialog() }

        swMaster.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked && AppLocker.pinHash(this).isEmpty()) {
                // First run: the locker cannot arm without its PIN.
                pendingEnable = true
                swMaster.isChecked = false
                showPinDialog()
            } else {
                AppLocker.setEnabled(this, checked)
            }
        }
        bindSwitch(R.id.swLockerBio, AppLocker.biometricOn(this)) {
            AppLocker.setBiometricOn(this, it) }
        bindSwitch(R.id.swLockerPeer, AppLocker.peerOn(this)) {
            AppLocker.setPeerOn(this, it) }
        bindSwitch(R.id.swLockerIntruder, AppLocker.intruderOn(this)) {
            AppLocker.setIntruderOn(this, it) }
        bindSwitch(R.id.swLockerProtect, AppLocker.protectSelfOn(this)) {
            AppLocker.setProtectSelfOn(this, it); renderLocked() }
        bindSwitch(R.id.swLockerNewApps, AppLocker.lockNewOn(this)) {
            AppLocker.setLockNewOn(this, it) }
        bindSwitch(R.id.swLockerDisguise, AppLocker.disguiseOn(this)) {
            AppLocker.setDisguiseOn(this, it) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        AppLocker.managementOpen = true
        refresh()
    }

    override fun onPause() {
        AppLocker.managementOpen = false
        super.onPause()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchMaterial>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked ->
            if (!binding) onChange(checked)
        }
    }

    private fun setState(tv: TextView, ok: Boolean) {
        tv.text = getString(if (ok) R.string.status_granted
            else R.string.status_missing)
        tv.setTextColor(ContextCompat.getColor(this,
            if (ok) R.color.status_ok else R.color.status_bad))
    }

    private fun refresh() {
        binding = true
        swMaster.isChecked = AppLocker.isEnabled(this)
        binding = false
        val hasPin = AppLocker.pinHash(this).isNotEmpty()
        tvPinState.text = if (hasPin)
            getString(R.string.alk_pin_set, AppLocker.pinLen(this))
        else getString(R.string.alk_pin_unset)
        btnPin.text = getString(if (hasPin) R.string.alk_btn_change_pin
            else R.string.alk_btn_set_pin)
        setState(tvOverlayState, PermChecks.overlayOk(this))
        setState(tvA11yState, PermChecks.a11yEnabled(this))
        btnGrace.text = graceLabel(AppLocker.graceSec(this))
        renderLocked()
    }

    private fun graceLabel(sec: Int): String = getString(when (sec) {
        0 -> R.string.alk_grace_0
        30 -> R.string.alk_grace_30
        60 -> R.string.alk_grace_60
        300 -> R.string.alk_grace_300
        else -> R.string.alk_grace_screen
    })

    private fun showGraceDialog() {
        val secs = intArrayOf(0, 30, 60, 300, -1)
        val labels = secs.map { graceLabel(it) }.toTypedArray()
        val current = secs.indexOf(AppLocker.graceSec(this))
            .let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.alk_grace_label))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppLocker.setGraceSec(this, secs[which])
                btnGrace.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** Locker PIN setup / change. The locker cannot be enabled
     *  until a PIN exists (first-run flow). */
    private fun showPinDialog() {
        val hasPin = AppLocker.pinHash(this).isNotEmpty()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        fun field(hintRes: Int): EditText = EditText(this).apply {
            hint = getString(hintRes)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        val etCurrent = if (hasPin) field(R.string.alk_hint_current_pin) else null
        val etNew = field(R.string.alk_hint_new_pin)
        val etConfirm = field(R.string.alk_hint_confirm_pin)
        etCurrent?.let { box.addView(it) }
        box.addView(etNew)
        box.addView(etConfirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(if (hasPin) R.string.alk_pin_change_title
                else R.string.alk_pin_new_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                pendingEnable = false
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newPin = etNew.text.toString().trim()
                    when {
                        hasPin && Crypto.pinHash(
                            etCurrent?.text?.toString()?.trim() ?: "") !=
                            AppLocker.pinHash(this) ->
                            toast(getString(R.string.alk_pin_wrong_current))
                        newPin.length !in 4..6 || !newPin.all { it.isDigit() } ->
                            toast(getString(R.string.alk_pin_digits))
                        newPin != etConfirm.text.toString().trim() ->
                            toast(getString(R.string.alk_pin_mismatch))
                        else -> {
                            AppLocker.setPin(this, newPin)
                            SecLog.add(this, "App Locker PIN বসানো/বদল হয়েছে")
                            toast(getString(R.string.alk_pin_saved))
                            if (pendingEnable) {
                                pendingEnable = false
                                AppLocker.setEnabled(this, true)
                            }
                            dialog.dismiss()
                            refresh()
                        }
                    }
                }
        }
        dialog.setOnCancelListener { pendingEnable = false }
        dialog.show()
    }

    private fun renderLocked() {
        llLocked.removeAllViews()
        data class Row(val pkg: String, val tag: String, val removable: Boolean)
        val rows = mutableListOf<Row>()
        AppLocker.lockedPkgs(this)
            .sortedBy { AppLocker.appLabel(this, it).lowercase() }
            .forEach { rows.add(Row(it, "", true)) }
        if (AppLocker.protectSelfOn(this)) {
            val tag = getString(R.string.alk_protect_tag)
            rows.add(Row(packageName, tag, false))
            rows.add(Row(AppLocker.SETTINGS_PKG, tag, false))
        }
        tvNoLocked.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                val icon = AppLocker.appIcon(this@AppLockerActivity, row.pkg)
                if (icon != null) setImageDrawable(icon)
                else setImageResource(R.drawable.ic_grid)
            }
            line.addView(iv)
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(TextView(this).apply {
                text = AppLocker.appLabel(this@AppLockerActivity, row.pkg)
                textSize = 15f
                setTextColor(themeOnSurface())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this).apply {
                text = if (row.tag.isEmpty()) row.pkg
                    else row.pkg + " • " + row.tag
                textSize = 12f
                setTextColor(themeOnSurfaceVariant())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.addView(texts)
            if (row.removable) {
                line.addView(ImageButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    setImageResource(R.drawable.ic_trash)
                    background = null
                    contentDescription = getString(R.string.alk_remove)
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    setOnClickListener {
                        AppLocker.removeLocked(this@AppLockerActivity, row.pkg)
                        renderLocked()
                    }
                })
            }
            llLocked.addView(line)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
