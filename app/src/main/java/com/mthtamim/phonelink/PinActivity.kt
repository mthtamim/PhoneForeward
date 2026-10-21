package com.mthtamim.phonelink

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle

/**
 * v2.9: enters or leaves screen pinning (lock task mode) on behalf of
 * the screen_pin / unpin automation actions. startLockTask must be
 * called from an Activity, hence this transparent trampoline.
 */
class PinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent.getBooleanExtra("pin", true)) {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
                val admin = ComponentName(this, AdminReceiver::class.java)
                try {
                    dpm.setLockTaskPackages(admin, arrayOf(packageName))
                } catch (_: Exception) { }
                startLockTask()
            } else {
                stopLockTask()
            }
        } catch (_: Exception) { }
        finish()
    }
}
