package com.mthtamim.phonelink

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification?.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            Automation.onEvent(applicationContext, "notif_cleared", JSONObject()
                .put("title", title)
                .put("app", sbn.packageName ?: "")
                .put("body", ""))
        } catch (_: Exception) { }
    }

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
        Automation.onEvent(ctx, "notif_received", JSONObject()
            .put("title", title).put("body", text).put("app", Contacts.appLabel(ctx, pkg)))
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

        // Keyword alerts punch through every app rule: OFF, allowlist
        // and digest are all skipped for a message with an alert word.
        // v2.4 smart importance: money/OTP notifications are treated the
        // same way when the auto-priority toggle is on.
        val bankHit = Config.autoPriorityOn(ctx) &&
            listOf("বিকাশ", "নগদ", "bkash", "nagad", "রকেট", "উপায়").any {
                full.lowercase().contains(it)
            }
        val alertHit = Config.containsAlertWord(ctx, full) || bankHit ||
            (Config.autoPriorityOn(ctx) && Otp.find(full).isNotEmpty())

        // Smart Control per-app rule.
        val appRule = AppRules.rule(ctx, pkg)
        if (!alertHit) {
            // Study mode: everything except priority senders is digested.
            if (Config.studyMode(ctx) && Config.digestMinutes(ctx) > 0 &&
                appRule != AppRules.OFF &&
                !(AppRules.allowlistMode(ctx) && appRule == AppRules.DEFAULT)) {
                DigestStore.add(ctx, app, heading, bodyText)
                return
            }
            if (appRule == AppRules.OFF) return
            if (AppRules.allowlistMode(ctx) && appRule == AppRules.DEFAULT) return
            if (appRule == AppRules.DIGEST && Config.digestMinutes(ctx) > 0) {
                DigestStore.add(ctx, app, heading, bodyText)
                return
            }
        }

        // Remember the notification's reply action (WhatsApp/Messenger
        // style) so the other phone can answer without touching this one.
        val reply = findReplyAction(sbn)
        if (reply != null) registerReplyTarget(sbn.key, reply.first, reply.second, pkg)

        val extra = JSONObject()
        if (reply != null) extra.put("notifKey", sbn.key)
        if (alertHit) extra.put("priority", 1)
        val code = Otp.find("$title $bodyText")
        val prefix = when {
            alertHit && code.isNotEmpty() -> "🚨🔑 "
            alertHit -> "🚨 "
            code.isNotEmpty() -> "🔑 "
            else -> ""
        }
        if (code.isNotEmpty()) extra.put("code", code)
        Relay.send(ctx, Relay.TYPE_NOTIF, "$prefix$heading", bodyText,
            if (extra.length() > 0) extra else null)
    }

    private fun findReplyAction(sbn: StatusBarNotification): Pair<PendingIntent, RemoteInput>? {
        val n = sbn.notification
        val actions = mutableListOf<Notification.Action>()
        n.actions?.let { actions.addAll(it) }
        try { actions.addAll(Notification.WearableExtender(n).actions) } catch (_: Exception) { }
        for (a in actions) {
            val inputs = a.remoteInputs ?: continue
            if (inputs.isNotEmpty() && a.actionIntent != null) {
                return a.actionIntent to inputs[0]
            }
        }
        return null
    }

    companion object {
        private val lock = Any()
        private val recent = HashMap<String, Long>()

        @Volatile private var instance: NotifListener? = null

        /** Dismisses one of this phone's notifications by its key. */
        fun dismiss(key: String): Boolean {
            val svc = instance ?: return false
            return try { svc.cancelNotification(key); true } catch (_: Exception) { false }
        }

        /** v2.7: dismisses every notification posted by one package. */
        fun cancelPackage(pkg: String): Boolean {
            val svc = instance ?: return false
            return try {
                var n = 0
                for (sbn in svc.activeNotifications) {
                    if (sbn.packageName == pkg) { svc.cancelNotification(sbn.key); n++ }
                }
                n > 0
            } catch (_: Exception) { false }
        }

        /** Dismisses all of this phone's notifications. */
        fun dismissAll(): Boolean {
            val svc = instance ?: return false
            return try { svc.cancelAllNotifications(); true } catch (_: Exception) { false }
        }

        class ReplyTarget(
            val intent: PendingIntent,
            val remote: RemoteInput,
            val pkg: String
        )

        private val replyTargets = LinkedHashMap<String, ReplyTarget>()

        fun registerReplyTarget(sbnKey: String, intent: PendingIntent, remote: RemoteInput, pkg: String) {
            synchronized(lock) {
                replyTargets[sbnKey] = ReplyTarget(intent, remote, pkg)
                while (replyTargets.size > 100) {
                    replyTargets.remove(replyTargets.keys.first())
                }
            }
        }

        /** Fires the stored reply action with the given text. */
        fun fireReply(ctx: Context, sbnKey: String, text: String): Boolean {
            val target = synchronized(lock) { replyTargets[sbnKey] } ?: return false
            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(target.remote.resultKey, text)
                RemoteInput.addResultsToIntent(arrayOf(target.remote), intent, bundle)
                target.intent.send(ctx, 0, intent)
                true
            } catch (e: Exception) { false }
        }
    }
}
