package com.mthtamim.phonelink

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.11 panic mode decoy: shown when the panic PIN is entered at the
 * app lock. Looks like an empty, boring inbox screen. The small button
 * returns to the lock, where the real PIN clears panic mode.
 */
class PanicActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panic)
        val clock = findViewById<TextView>(R.id.tvPanicClock)
        clock.text = SimpleDateFormat("hh:mm a • dd MMM", Locale.getDefault())
            .format(Date())
        findViewById<Button>(R.id.btnPanicRetry).setOnClickListener {
            SessionState.unlocked = false
            startActivity(Intent(this, LockActivity::class.java))
            finish()
        }
    }
}
