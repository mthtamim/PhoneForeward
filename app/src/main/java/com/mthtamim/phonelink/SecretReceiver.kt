package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v2.11.1: the secret way back in when the launcher icon is hidden.
 * Dial *#*#7646#*#* (7646 = "PHON" on the keypad) and Android sends
 * this broadcast; we simply open the app. The app lock still applies.
 */
class SecretReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(open)
        } catch (_: Exception) { }
    }
}
