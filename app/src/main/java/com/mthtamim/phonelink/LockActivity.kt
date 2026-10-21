package com.mthtamim.phonelink

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** App-lock gate: PIN entry, with biometrics offered when available. */
class LockActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Config.appLockOn(this) || SessionState.unlocked) { proceed(); return }
        setContentView(R.layout.activity_lock)

        val etPin = findViewById<EditText>(R.id.etPin)
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            val pin = etPin.text.toString()
            // v2.11 panic mode: the panic PIN opens the decoy screen
            // and silently mutes the app until the real PIN is used.
            if (pin.isNotEmpty() && Config.panicPinHash(this).isNotEmpty() &&
                Crypto.pinHash(pin) == Config.panicPinHash(this)) {
                Config.setPanicActive(this, true)
                SessionState.unlocked = true
                startActivity(android.content.Intent(this, PanicActivity::class.java))
                finish()
            } else if (pin.isNotEmpty() &&
                Crypto.pinHash(pin) == Config.pinHash(this)) {
                Config.setPanicActive(this, false)
                proceed()
            } else {
                toast(getString(R.string.lock_wrong_pin))
                etPin.text.clear()
            }
        }
        val btnBio = findViewById<Button>(R.id.btnBiometric)
        val bm = BiometricManager.from(this)
        val canBio = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        btnBio.visibility = if (canBio) android.view.View.VISIBLE else android.view.View.GONE
        btnBio.setOnClickListener { showBiometric() }
        if (canBio) showBiometric()
    }

    private fun showBiometric() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    proceed()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setNegativeButtonText(getString(R.string.btn_cancel))
            .build()
        prompt.authenticate(info)
    }

    private fun proceed() {
        SessionState.unlocked = true
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
