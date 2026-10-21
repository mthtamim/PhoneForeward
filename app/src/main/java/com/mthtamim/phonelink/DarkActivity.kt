package com.mthtamim.phonelink

import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/**
 * v2.4 fake-off screen for lost mode: a full-screen black page that
 * makes the phone look switched off while the link service keeps
 * sending its location underneath. Five quick taps dismiss it.
 * (The real power menu can still shut the phone down — no app can
 * prevent that; this only covers the casual glance.)
 */
class DarkActivity : LocalizedActivity() {

    private var taps = 0
    private var firstTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dark)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<TextView>(R.id.tvDarkHint).setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - firstTapAt > 3000) { taps = 0; firstTapAt = now }
            taps++
            if (taps >= 5) finish()
        }
    }

    override fun onBackPressed() {
        // Swallow back while the disguise is on.
    }
}
