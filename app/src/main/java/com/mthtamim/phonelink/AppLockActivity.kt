package com.mthtamim.phonelink

import android.content.Intent
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
            onUnlocked = { unlockAndReturn() },
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
                    unlockAndReturn()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.alk_title))
            .setSubtitle(getString(R.string.alk_lock_by))
            .setNegativeButtonText(getString(R.string.btn_cancel))
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) { }
    }

    /**
     * v2.19.0 fix: a successful unlock (PIN via AppLockUi, biometric
     * here, or a peer grant observed by AppLockUi) must hand the user
     * back to the app they were opening. The old code just called
     * finish() — and because this activity lived in PhoneLink's own
     * task, an existing MainActivity underneath surfaced instead of
     * the target app. Now the target is relaunched FIRST (its own
     * task comes to the front with its stack intact), then we finish.
     * RESET_TASK_IF_NEEDED is deliberately NOT used: it could reset
     * the target task to its root intent and lose the screen the
     * user was on; REORDER_TO_FRONT preserves it.
     */
    private fun unlockAndReturn() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launch)
            }
        } catch (_: Exception) { }
        finish()
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
