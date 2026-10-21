package com.mthtamim.phonelink

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/** Shows a message from the other phone big on screen (SCREEN command). */
class ScreenActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.tvScreenText).text = intent.getStringExtra("text") ?: ""
        findViewById<Button>(R.id.btnScreenClose).setOnClickListener { finish() }
    }
}
