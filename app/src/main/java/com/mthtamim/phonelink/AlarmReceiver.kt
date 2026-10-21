package com.mthtamim.phonelink

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires a remote (or local) alarm: loud ringing + a full-screen page. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val text = intent.getStringExtra("text")?.ifEmpty { "⏰ অ্যালার্ম" } ?: "⏰ অ্যালার্ম"
        RingHelper.start(ctx, maxAll = true)
        try {
            val screen = Intent(ctx, ScreenActivity::class.java)
                .putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, 777, screen,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notif = Notification.Builder(ctx, LinkService.CHANNEL_URGENT)
                .setContentTitle("⏰ অ্যালার্ম")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(777, notif)
        } catch (_: Exception) { }
    }
}
