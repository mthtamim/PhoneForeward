package com.mthtamim.phonelink

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.5.0 host for screen_pin / unpin. Screen pinning pins the
 * CALLING activity's task, so the actions cannot pin from a
 * service — they launch this activity instead. With pin=true
 * it calls startLockTask() (first pin shows Android's system
 * confirmation prompt) and stays on a minimal screen; with
 * unpin=true it calls stopLockTask() and finishes at once.
 * Exiting a pinned screen also works via the system gesture
 * (Back + Recents, hold Back on some devices).
 */
class FlowPinActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val unpin = intent?.getBooleanExtra("unpin", false) ?: false
        if (unpin) {
            try {
                stopLockTask()
            } catch (_: Exception) {
            }
            FlowStore.log(this, "unpin: screen unpinned")
            finish()
            return
        }
        try {
            startLockTask()
            FlowStore.log(this, "screen_pin: screen pinned")
        } catch (e: Exception) {
            FlowStore.log(this, "screen_pin FAILED: " + (e.message ?: ""))
            finish()
            return
        }
        val tv = TextView(this).apply {
            text = "Screen pinned by PhoneLink"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(tv)
        }
        setContentView(root)
    }
}
