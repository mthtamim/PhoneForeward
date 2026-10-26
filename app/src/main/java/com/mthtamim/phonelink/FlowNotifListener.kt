package com.mthtamim.phonelink

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Lab notification listener. v1.2.0 only declared it for grant
 * parity; v1.3.0 wires it for real: posted notifications are
 * tracked so the notif_reply action can answer one via
 * RemoteInput. The user enables the listener in system Settings
 * (Permissions screen row); nothing auto-enables it. When the
 * listener is off, replies simply log "no matching notification".
 */
class FlowNotifListener : NotificationListenerService() {

    companion object {
        private val active = ConcurrentHashMap<String, StatusBarNotification>()
        private val rankingByKey = ConcurrentHashMap<String, Ranking>()

        @Volatile
        private var appCtx: Context? = null

        /**
         * v1.4.0: ranking for the newest posted notification of
         * [pkg], used by the app_notif_priority / visibility tick
         * branches. Null when nothing from that package is posted.
         */
        fun rankingFor(pkg: String): Ranking? {
            return try {
                var best: StatusBarNotification? = null
                for ((_, sbn) in active) {
                    if (sbn.packageName != pkg) {
                        continue
                    }
                    if (best == null || sbn.postTime > best.postTime) {
                        best = sbn
                    }
                }
                val target = best ?: return null
                rankingByKey[target.key]
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Reply [text] to the first active notification whose title
         * or body contains [match] (case-insensitive) and whose
         * actions carry a RemoteInput. Returns false when nothing
         * matches or the send fails.
         */
        fun reply(match: String, text: String): Boolean {
            val ctx = appCtx ?: return false
            for ((_, sbn) in active) {
                val n = sbn.notification ?: continue
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                if (!title.contains(match, ignoreCase = true) &&
                    !body.contains(match, ignoreCase = true)
                ) {
                    continue
                }
                val actions = n.actions ?: continue
                for (action in actions) {
                    val inputs = action.remoteInputs ?: continue
                    if (inputs.isEmpty()) {
                        continue
                    }
                    try {
                        val results = Bundle()
                        results.putCharSequence(inputs[0].resultKey, text)
                        val intent = android.content.Intent()
                        RemoteInput.addResultsToIntent(inputs, intent, results)
                        action.actionIntent.send(ctx, 0, intent)
                        return true
                    } catch (_: Exception) {
                    }
                }
            }
            return false
        }

        /**
         * PhoneLink v2.24.0: the notif_present condition. Blank
         * [match] means any active notification; otherwise true
         * when some active notification's title or body contains
         * the text (case-insensitive) or its package equals it.
         * False when the listener is off (nothing tracked) — the
         * condition then honestly never matches, it never claims
         * a notification that is not there.
         */
        fun hasActive(match: String): Boolean {
            if (match.isEmpty()) {
                return active.isNotEmpty()
            }
            for ((_, sbn) in active) {
                if (sbn.packageName == match) {
                    return true
                }
                val n = sbn.notification ?: continue
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                if (title.contains(match, ignoreCase = true) ||
                    body.contains(match, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            active[sbn.key] = sbn
            try {
                val ranking = Ranking()
                if (currentRanking.getRanking(sbn.key, ranking)) {
                    rankingByKey[sbn.key] = ranking
                }
            } catch (_: Exception) {
            }
            val ctx = appCtx ?: applicationContext
            try {
                val n = sbn.notification
                val data = org.json.JSONObject()
                data.put(
                    "title",
                    n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                )
                data.put(
                    "body",
                    n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                )
                data.put("app", sbn.packageName)
                data.put("pkg", sbn.packageName)
                FlowEngine.onEvent(ctx, "notif_received", data)
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        onNotificationRemovedWithReason(sbn, -1)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        onNotificationRemovedWithReason(sbn, reason)
    }

    private fun onNotificationRemovedWithReason(sbn: StatusBarNotification?, reason: Int) {
        if (sbn != null) {
            active.remove(sbn.key)
            rankingByKey.remove(sbn.key)
            val ctx = appCtx ?: applicationContext
            try {
                val n = sbn.notification
                val data = org.json.JSONObject()
                data.put(
                    "title",
                    n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                )
                data.put(
                    "body",
                    n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                )
                data.put("app", sbn.packageName)
                data.put("pkg", sbn.packageName)
                FlowEngine.onEvent(ctx, "notif_cleared", data)
                if (reason == NotificationListenerService.REASON_CLICK) {
                    FlowEngine.onEvent(ctx, "notif_interacted", data)
                }
            } catch (_: Exception) {
            }
        }
    }
}
