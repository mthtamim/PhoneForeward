package com.mthtamim.phonelink

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

/** Handles notification action taps that work fine from the background. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra("url") ?: return
                val name = intent.getStringExtra("name")?.ifEmpty { "phonelink-file" } ?: "phonelink-file"
                try {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    dm.enqueue(req)
                } catch (_: Exception) { }
            }
            ACTION_QUICK_SMS -> {
                // A quick reply from the incoming-call alert: the text is
                // sent by the OTHER phone from its own SIM (REPLY_SMS).
                val number = intent.getStringExtra("number") ?: return
                val text = intent.getStringExtra("text") ?: return
                val extra = org.json.JSONObject().put("number", number).put("text", text)
                Relay.sendCommand(context.applicationContext, "REPLY_SMS", null, extra)
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.mthtamim.phonelink.DOWNLOAD"
        const val ACTION_QUICK_SMS = "com.mthtamim.phonelink.QUICK_SMS"
    }
}
