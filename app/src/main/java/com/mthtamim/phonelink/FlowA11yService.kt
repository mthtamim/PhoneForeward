package com.mthtamim.phonelink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lab accessibility service, v1.2.0: performs REAL gestures for the
 * tap/swipe blocks (Automate's Interact). The user enables it in
 * system Settings (Permissions screen, "Accessibility helper" row);
 * nothing here auto-enables anything. When the service is not
 * enabled, engine gesture actions log a clear enablement message
 * instead of silently succeeding.
 *
 * All gesture calls block the calling (engine background) thread for
 * at most ~2.5s waiting for the dispatch callback, then report
 * success/failure. Never call these from the main thread.
 */
class FlowA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FlowA11yService? = null

        fun isOn(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        // v1.3.0 fingerprint-gesture trigger (best-effort): the
        // controller exists only when the manifest config requests
        // fingerprint gestures AND the device supports them; on
        // other devices it simply never fires. Logged once here.
        try {
            val fgc = fingerprintGestureController
            if (fgc == null || !fgc.isGestureDetectionAvailable) {
                FlowStore.log(this, "fingerprint gestures unavailable on this device")
            } else {
                fgc.registerFingerprintGestureCallback(
                    object : FingerprintGestureController.FingerprintGestureCallback() {
                        override fun onGestureDetected(gesture: Int) {
                            val data = org.json.JSONObject()
                            data.put("gesture", gesture)
                            FlowEngine.onEvent(
                                this@FlowA11yService, "fingerprint_gesture", data
                            )
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                FlowStore.log(this, "fingerprint gestures available")
            }
        } catch (_: Exception) {
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val data = org.json.JSONObject()
                    val text = event.text?.joinToString(" ") ?: ""
                    val desc = event.contentDescription?.toString() ?: ""
                    data.put("title", text)
                    data.put("body", desc)
                    data.put("app", event.packageName?.toString() ?: "")
                    FlowEngine.onEvent(this, "ui_click", data)
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val data = org.json.JSONObject()
                    val text = event.text?.joinToString(" ") ?: ""
                    data.put("title", text)
                    data.put("body", event.contentDescription?.toString() ?: "")
                    data.put("app", event.packageName?.toString() ?: "")
                    FlowEngine.onEvent(this, "ui_focused", data)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    // Toasts arrive here with a null parcelable data
                    // (real notifications go through the listener).
                    if (event.parcelableData == null) {
                        val data = org.json.JSONObject()
                        data.put("title", event.text?.joinToString(" ") ?: "")
                        data.put("body", "")
                        data.put("app", event.packageName?.toString() ?: "")
                        FlowEngine.onEvent(this, "toast_posted", data)
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = collectText(rootInActiveWindow)
                    if (text.isNotEmpty() && text != lastScreenText) {
                        lastScreenText = text
                        val data = org.json.JSONObject()
                        data.put("title", text)
                        data.put("body", text)
                        data.put("app", event.packageName?.toString() ?: "")
                        FlowEngine.onEvent(this, "screen_text", data)
                    }
                }
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    checkKeyboardAndSplitScreen()
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Foreground-package edge (complements the
                    // UsageStats path in FlowTriggers): a window state
                    // change to a new package is an app launch. The
                    // shared lastForegroundApp keeps the UsageStats
                    // tick from re-firing the same transition.
                    val pkg = event.packageName?.toString() ?: ""
                    if (pkg.isNotEmpty() && pkg != lastWindowPkg) {
                        if (lastWindowPkg.isNotEmpty()) {
                            val closed = org.json.JSONObject()
                            closed.put("pkg", lastWindowPkg)
                            FlowEngine.onEvent(this, "app_closed", closed)
                        }
                        val opened = org.json.JSONObject()
                        opened.put("pkg", pkg)
                        FlowEngine.onEvent(this, "app_launched", opened)
                        lastWindowPkg = pkg
                        FlowTriggers.lastForegroundApp = pkg
                        // PhoneLink v2.24.0: NO Lab-style PIN gate here.
                        // App locking is owned by PhoneLink's AppLocker
                        // (its own overlay + PIN); a second gate from
                        // the Flow helper would double-prompt. Flow
                        // lock_app/unlock_app actions only edit the
                        // AppLocker set.
                    }
                    checkKeyboardAndSplitScreen()
                }
                else -> {
                }
            }
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var lastScreenText = ""

    @Volatile
    private var lastWindowPkg = ""

    @Volatile
    private var lastKeyboardVisible = false

    @Volatile
    private var lastSplitScreen = false

    /** Window text, capped so a huge screen does not flood the log. */
    private fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return ""
        }
        val sb = StringBuilder()
        collectTextInto(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextInto(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (sb.length > 4000 || depth > 12) {
            return
        }
        try {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                if (sb.isNotEmpty()) {
                    sb.append(' ')
                }
                sb.append(text)
            }
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                if (sb.isNotEmpty()) {
                    sb.append(' ')
                }
                sb.append(desc)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectTextInto(child, sb, depth + 1)
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Keyboard visibility (an INPUT_METHOD window exists) and
     *  split-screen (two APPLICATION windows) edges. */
    private fun checkKeyboardAndSplitScreen() {
        try {
            val windows = windows ?: return
            var keyboard = false
            var appWindows = 0
            for (window in windows) {
                when (window.type) {
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ->
                        keyboard = true
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ->
                        appWindows++
                }
            }
            if (keyboard != lastKeyboardVisible) {
                lastKeyboardVisible = keyboard
                val data = org.json.JSONObject()
                data.put("visible", keyboard.toString())
                FlowEngine.onEvent(this, "keyboard_visible", data)
            }
            val split = appWindows >= 2
            if (split != lastSplitScreen) {
                lastSplitScreen = split
                if (split) {
                    FlowEngine.onEvent(this, "split_screen", org.json.JSONObject())
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        try {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val data = org.json.JSONObject()
                data.put("code", event.keyCode.toString())
                data.put(
                    "key",
                    android.view.KeyEvent.keyCodeToString(event.keyCode)
                )
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP ->
                        FlowEngine.onEvent(this, "volume_up", data)
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                        FlowEngine.onEvent(this, "volume_down", data)
                    else ->
                        FlowEngine.onEvent(this, "key_pressed", data)
                }
            }
        } catch (_: Exception) {
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
    }

    /** Tap at absolute screen pixels. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        return gesture(path, 100L)
    }

    /** Swipe across absolute screen pixels over [ms] milliseconds. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        return gesture(path, ms.coerceIn(1L, 5000L))
    }

    private fun gesture(path: Path, ms: Long): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val stroke = GestureDescription.StrokeDescription(path, 0L, ms)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = try {
            dispatchGesture(desc, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }, null)
        } catch (_: Exception) {
            false
        }
        if (!dispatched) {
            return false
        }
        try {
            done.await(2500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        return ok.get()
    }

    /** Find the first visible node showing [text] and tap its center. */
    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                try {
                    if (!node.isVisibleToUser) {
                        continue
                    }
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() <= 0 || r.height() <= 0) {
                        continue
                    }
                    return tap(r.exactCenterX(), r.exactCenterY())
                } finally {
                    node.recycle()
                }
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            root.recycle()
        }
    }

    /** Type [text] into the currently focused input field. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return false
            try {
                if (!target.isEditable) {
                    return false
                }
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
                return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                target.recycle()
            }
        } catch (_: Exception) {
            return false
        } finally {
            root.recycle()
        }
    }

    /** Collect visible texts of the active window, one per line. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val out = ArrayList<String>()
        try {
            collectTexts(root, out, 0)
        } catch (_: Exception) {
        } finally {
            root.recycle()
        }
        return out.joinToString("\n")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size >= 200) {
            return
        }
        try {
            if (node.isVisibleToUser) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    out.add(t)
                } else {
                    val d = node.contentDescription?.toString()?.trim()
                    if (!d.isNullOrEmpty()) {
                        out.add(d)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    collectTexts(child, out, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** System global actions: back, home, recents, notifications, quick settings, power dialog. */
    fun globalAction(name: String): Boolean {
        val action = when (name.trim().lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings", "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power", "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return try {
            performGlobalAction(action)
        } catch (_: Exception) {
            false
        }
    }
}
