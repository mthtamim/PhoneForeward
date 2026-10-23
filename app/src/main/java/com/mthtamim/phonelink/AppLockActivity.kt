package com.mthtamim.phonelink

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * v2.18: the App Locker lock screen hosted as an activity — used
 * when the overlay grant is missing (best-effort cover) and as the
 * biometric host when the overlay's fingerprint key is tapped
 * (BiometricPrompt needs an activity). Renders the same
 * view_applock layout through the same AppLockUi controller.
 */
class AppLockActivity : LocalizedActivity() {

    private var ui: AppLockUi? = null
    private var pkg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg") ?: ""
        // Nothing to do when the locker is off, the app is not
        // locked, or a grace already covers this stint.
        if (pkg.isEmpty() || !AppLocker.isEnabled(this) ||
            !AppLocker.isLocked(this, pkg) ||
            AppLocker.graceValid(this, pkg)) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        openPkg = pkg
        setContentView(R.layout.view_applock)
        val root = findViewById<android.view.View>(R.id.lockRoot)
        val controller = AppLockUi(this, root, pkg, AppLockUi.Host.ACTIVITY,
            onUnlocked = { finish() },
            onBiometric = { showBiometric() })
        ui = controller
        onBackPressedDispatcher.addCallback(this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    controller.goHome()
                }
            })
        // Offer biometrics right away when the locker allows it,
        // mirroring the app-lock screen's behaviour.
        if (AppLocker.biometricOn(this)) showBiometric()
    }

    private fun showBiometric() {
        val bm = androidx.biometric.BiometricManager.from(this)
        if (bm.canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) !=
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult) {
                    AppLocker.grantGrace(this@AppLockActivity, pkg)
                    finish()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.alk_title))
            .setSubtitle(getString(R.string.alk_lock_by))
            .setNegativeButtonText(getString(R.string.btn_cancel))
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        if (pkg.isNotEmpty()) resumedPkg = pkg
    }

    override fun onPause() {
        if (resumedPkg == pkg) resumedPkg = null
        super.onPause()
    }

    override fun onDestroy() {
        ui?.destroy()
        ui = null
        if (openPkg == pkg) openPkg = null
        if (resumedPkg == pkg) resumedPkg = null
        super.onDestroy()
    }

    companion object {
        /** The package an AppLockActivity instance exists for. */
        @Volatile var openPkg: String? = null

        /** The package the activity is RESUMED over right now — the
         *  overlay host and the poll defer to it only while this is
         *  set, so a backgrounded lock activity never leaves the
         *  target app uncovered. */
        @Volatile var resumedPkg: String? = null
    }
}
