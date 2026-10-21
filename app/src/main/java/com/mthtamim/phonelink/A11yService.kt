package com.mthtamim.phonelink

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * v2.4 optional accessibility helper (owner enables it in Android
 * Settings; the app never turns it on itself). Powers two automation
 * pieces MacroDroid-style: the "screen text appears" trigger and the
 * "tap by text" action. Everything stays on-device; only the matched
 * trigger event crosses into the rule engine.
 */
class A11yService : AccessibilityService() {

    private var lastText = ""
    private var lastFireAt = 0L

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 2500) return
        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collect(root, texts, 0)
        val joined = texts.joinToString(" ").take(4000)
        if (joined.isBlank() || joined == lastText) return
        lastText = joined
        lastFireAt = now
        Automation.onEvent(applicationContext, "screen_text", JSONObject()
            .put("title", event.packageName?.toString() ?: "")
            .put("body", joined))
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 12 || out.size > 80) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    companion object {
        @Volatile var instance: A11yService? = null

        /** v2.7: performs a global navigation action (home/back/recents/notifications). */
        fun globalKey(name: String): Boolean {
            val svc = instance ?: return false
            val action = when (name.lowercase()) {
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return try { svc.performGlobalAction(action) } catch (_: Exception) { false }
        }

        /** Taps the first on-screen element containing the given text. */
        fun tapByText(text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
            for (node in nodes) {
                var n: AccessibilityNodeInfo? = node
                var hops = 0
                while (n != null && hops < 6) {
                    if (n.isClickable) {
                        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    n = n.parent
                    hops++
                }
            }
            return false
        }
    }
}
