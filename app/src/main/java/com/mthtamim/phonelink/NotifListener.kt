package com.mthtamim.phonelink

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val ctx = applicationContext
        if (!Config.isSender(ctx) || !Config.fwdNotif(ctx)) return

        val pkg = sbn.packageName ?: return
        if (pkg == ctx.packageName) return
        if (Config.excludedApps(ctx).contains(pkg)) return

        val notif = sbn.notification ?: return
        // Skip ongoing/foreground-service noise (music players, downloads in progress etc.)
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        text = text.trim()
        if (title.isEmpty() && text.isEmpty()) return

        val full = "$title $text"
        if (Config.containsBlockedWord(ctx, full)) return

        // De-duplicate identical notifications arriving within 5 seconds
        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = recent[key] ?: 0L
            if (now - last < 5000) return
            recent[key] = now
            if (recent.size > 200) recent.clear()
        }

        val app = Contacts.appLabel(ctx, pkg)
        val heading = if (title.isNotEmpty()) "$app: $title" else app
        val bodyText = text.ifEmpty { title }
        val code = Otp.find("$title $bodyText")
        if (code.isNotEmpty()) {
            val extra = org.json.JSONObject().put("code", code)
            Relay.send(ctx, Relay.TYPE_NOTIF, "🔑 $heading", bodyText, extra)
        } else {
            Relay.send(ctx, Relay.TYPE_NOTIF, heading, bodyText)
        }
    }

    companion object {
        private val lock = Any()
        private val recent = HashMap<String, Long>()
    }
}
