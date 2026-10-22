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
    private var lastSplit = false

    override fun onServiceConnected() {
        instance = this
        try {
            val info = serviceInfo
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (_: Exception) { }
    }

    /** v2.8: volume key triggers (the key press itself is not consumed). */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP ->
                    Automation.onEvent(applicationContext, "volume_up", JSONObject())
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                    Automation.onEvent(applicationContext, "volume_down", JSONObject())
            }
        }
        return false
    }

    /** v2.8: all visible text of the active window (for read_screen). */
    fun dumpText(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collect(root, texts, 0)
        return texts.joinToString(" ").take(4000)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private val focusNotified = mutableMapOf<String, Long>()
    private var lastAppPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // v2.11 focus guard: bounce blocked apps to the home screen
        // during the focus window, and tell the peer (throttled).
        try {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty() && pkg != packageName &&
                Config.focusOn(applicationContext) &&
                Config.focusPkgs(applicationContext).contains(pkg)) {
                val (fs, fe) = Config.focusWindow(applicationContext)
                val cal = java.util.Calendar.getInstance()
                val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    cal.get(java.util.Calendar.MINUTE)
                val inWindow = if (fs <= fe) mins in fs..fe
                    else mins >= fs || mins <= fe
                if (inWindow) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    val lastN = focusNotified[pkg] ?: 0L
                    if (System.currentTimeMillis() - lastN > 10 * 60_000L) {
                        focusNotified[pkg] = System.currentTimeMillis()
                        Relay.send(applicationContext, Relay.TYPE_INFO,
                            "🎯 ফোকাস গার্ড",
                            "$pkg খোলার চেষ্টা আটকে দেওয়া হয়েছে।")
                    }
                    return
                }
            }
        } catch (_: Exception) { }
        // v2.14.1: live app launch/close from window changes. The
        // usage-stats poll in tick() can notice a launch ~25 minutes
        // late (or miss short visits); this is instant, and it shares
        // the poll's state map so the two paths never double-fire.
        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                if (pkg.isNotEmpty() && pkg != packageName &&
                    pkg != "com.android.systemui" && pkg != "android" &&
                    !cls.contains("InputMethod") && pkg != lastAppPkg) {
                    val prev = lastAppPkg
                    lastAppPkg = pkg
                    if (prev != null)
                        Automation.fireAppEvent(applicationContext, "app_closed", prev)
                    Automation.fireAppEvent(applicationContext, "app_launched", pkg)
                }
            }
        } catch (_: Exception) { }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // v2.9: split-screen edge (checked before the text throttle)
        try {
            val split = (windows?.size ?: 0) >= 2
            if (split != lastSplit) {
                lastSplit = split
                if (split) Automation.onEvent(applicationContext, "split_screen",
                    JSONObject())
            }
        } catch (_: Exception) { }
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

        /** v2.8: taps at a position given in percent of the screen. */
        fun tapAt(xPct: Float, yPct: Float): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val path = android.graphics.Path().apply {
                moveTo(dm.widthPixels * xPct / 100f, dm.heightPixels * yPct / 100f)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription
                    .StrokeDescription(path, 0, 60)).build()
            return try { svc.dispatchGesture(gesture, null, null) }
                catch (_: Exception) { false }
        }

        /** v2.8: swipes between two percent positions. */
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val path = android.graphics.Path().apply {
                moveTo(dm.widthPixels * x1 / 100f, dm.heightPixels * y1 / 100f)
                lineTo(dm.widthPixels * x2 / 100f, dm.heightPixels * y2 / 100f)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription
                    .StrokeDescription(path, 0, ms.coerceIn(50, 2000))).build()
            return try { svc.dispatchGesture(gesture, null, null) }
                catch (_: Exception) { false }
        }

        /** v2.8: current screen text, for the read_screen action. */
        fun readScreenText(): String = instance?.dumpText() ?: ""

        /** v2.9: sets text into the currently focused input field. */
        fun typeText(text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val node = root.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return try { node.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                args) } catch (_: Exception) { false }
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
