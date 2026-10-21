package com.mthtamim.phonelink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * Tiny transparent activity whose only job is copying text. It exists
 * because Android 10+ blocks clipboard writes from background components —
 * a notification action opens this, it copies while briefly foregrounded,
 * toasts and finishes.
 */
class CopyActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text") ?: ""
        if (text.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PhoneLink", text))
            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
