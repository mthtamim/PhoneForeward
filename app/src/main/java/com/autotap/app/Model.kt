package com.autotap.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * v3 step types. OCR is intentionally NOT here: Tamim removed OCR in v3 to
 * keep the APK small; it returns in a future "Ultimate" version. Old v2
 * scripts containing OCR_TAP are migrated to TEXT_TAP on load.
 */
enum class StepType {
    TAP, SWIPE, TEXT_TAP, WAIT_TEXT, IMAGE_TAP, PINCH, CONDITION,
    SYSTEM, TYPE_TEXT, COLOR_WAIT, WAIT_STABLE, NUMBER_TARGET,
    RUN_SCRIPT, PATH, DUAL_TAP, HOLD_UNTIL, LOOP_START, LOOP_END,
    SET_VAR, IF, GOTO, RANDOM,
    // v5 additions
    DRAG_DROP, THREE_FINGER, KEY_EVENT, CLIPBOARD_SET, CLIPBOARD_GET,
    FILE_WRITE, HTTP_CALL, IMAGE_CONDITION, COLOR_IF, FINGERPRINT,
    LABEL, WHILE_START
}

/** Screen region presets for text / image search. 0 = full screen. */
object Region {
    const val FULL = 0
    const val TOP = 1
    const val BOTTOM = 2
    const val CENTER = 3

    fun name(preset: Int): String = when (preset) {
        TOP -> "উপরের অর্ধেক"
        BOTTOM -> "নিচের অর্ধেক"
        CENTER -> "মাঝের অংশ"
        else -> "পুরো স্ক্রিন"
    }

    fun bounds(preset: Int, w: Int, h: Int): IntArray = when (preset) {
        TOP -> intArrayOf(0, 0, w, h / 2)
        BOTTOM -> intArrayOf(0, h / 2, w, h)
        CENTER -> intArrayOf(w / 4, h / 4, w * 3 / 4, h * 3 / 4)
        else -> intArrayOf(0, 0, w, h)
    }

    fun contains(preset: Int, w: Int, h: Int, cx: Int, cy: Int): Boolean {
        val b = bounds(preset, w, h)
        return cx in b[0]..b[2] && cy in b[1]..b[3]
    }
}

object SystemAction {
    const val BACK = 0
    const val HOME = 1
    const val RECENTS = 2
    fun name(code: Int): String = when (code) {
        HOME -> "Home বাটন"
        RECENTS -> "Recents বাটন"
        else -> "Back বাটন"
    }
}

 /** Key-event codes for KEY_EVENT steps (handled with AudioManager). */
fun keyName(code: Int): String = when (code) {
    1 -> "ভলিউম +"
    2 -> "ভলিউম -"
    3 -> "Media play/pause"
    4 -> "Media next"
    5 -> "Media previous"
    else -> "Key $code"
}

/**
 * One action in a script. Field use by type:
 * TAP (x,y,durationMs=hold), SWIPE (x,y -> x2,y2, durationMs),
 * TEXT_TAP / WAIT_TEXT / CONDITION (text, region, timeoutSec),
 * IMAGE_TAP (imagePath, region, timeoutSec), PINCH (x,y center, x2=start dist, y2=end dist),
 * SYSTEM (x = SystemAction code), TYPE_TEXT (text = text to type),
 * COLOR_WAIT (x,y point, timeoutSec - waits until the pixel color changes),
 * WAIT_STABLE (timeoutSec - waits until the screen stops changing),
 * NUMBER_TARGET (text = label to find near the number, x = target value, region, timeoutSec),
 * RUN_SCRIPT (text = target script id),
 * PATH (text = "x,y;x,y;..." recorded freehand path, durationMs),
 * DUAL_TAP (x,y + x2,y2 simultaneous), HOLD_UNTIL (x,y hold point, text to wait for, timeoutSec),
 * LOOP_START (x2 = repeat count, 0 = forever until script end conditions), LOOP_END.
 * delayAfterMs = wait after this step; when delayMaxMs > delayMinMs >= 0 the
 * delay is random in [delayMinMs, delayMaxMs]. repeat = run this step N times.
 */
data class Step(
    val type: StepType,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val durationMs: Long = 600,
    val delayAfterMs: Long = 1000,
    val text: String = "",
    val region: Int = Region.FULL,
    val timeoutSec: Int = 10,
    val imagePath: String = "",
    val delayMinMs: Long = 0,
    val delayMaxMs: Long = 0,
    val repeat: Int = 1,
    /** Element anchor for TAP: the label of the element at the point when it
     *  was placed. At run time the element is searched first; the raw
     *  coordinates are only the fallback, so scripts survive layout moves. */
    val anchor: String = "",
    /** Second text slot: SET_VAR label / IF variable expression / TYPE_TEXT var use. */
    val text2: String = "",
    /** v5: resource-id anchor (more precise than the label when present). */
    val anchorId: String = "",
    /** v5: relative position inside the anchored element, per-mille (0..1000); -1 = center. */
    val relX: Int = -1,
    val relY: Int = -1,
    /** v5: SWIPE follows a curved (human-like) path when true. */
    val curved: Boolean = false,
    /** v5: GOTO / RANDOM jump to the LABEL step with this name when set. */
    val targetLabel: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("x", x); put("y", y); put("x2", x2); put("y2", y2)
        put("durationMs", durationMs); put("delayAfterMs", delayAfterMs)
        put("text", text); put("region", region); put("timeoutSec", timeoutSec)
        put("imagePath", imagePath)
        put("delayMinMs", delayMinMs); put("delayMaxMs", delayMaxMs)
        put("repeat", repeat)
        put("anchor", anchor); put("text2", text2)
        put("anchorId", anchorId); put("relX", relX); put("relY", relY)
        put("curved", curved); put("targetLabel", targetLabel)
    }

    fun describe(): String {
        val base = when (type) {
            StepType.TAP -> "Tap ($x, $y) - ধরে ${durationMs}ms"
            StepType.SWIPE -> "Swipe ($x, $y) থেকে ($x2, $y2) - ${durationMs}ms"
            StepType.TEXT_TAP -> "Text tap: \"$text\" (${Region.name(region)}, ${timeoutSec}s)"
            StepType.WAIT_TEXT -> "অপেক্ষা: \"$text\" (${Region.name(region)}, ${timeoutSec}s)"
            StepType.IMAGE_TAP -> "Image tap (${Region.name(region)}, ${timeoutSec}s)"
            StepType.PINCH -> "Pinch ($x, $y) - $x2 থেকে $y2 px"
            StepType.CONDITION -> "শর্ত: \"$text\" না থাকলে পরের step বাদ"
            StepType.SYSTEM -> SystemAction.name(x)
            StepType.TYPE_TEXT -> "লেখা লিখবে: \"$text\""
            StepType.COLOR_WAIT -> "রঙ বদল অপেক্ষা ($x, $y) - ${timeoutSec}s"
            StepType.WAIT_STABLE -> "স্ক্রিন স্থির অপেক্ষা - ${timeoutSec}s"
            StepType.NUMBER_TARGET -> "সংখ্যা টার্গেট: $x হতে হবে (\"$text\"-এর পাশে, ${timeoutSec}s)"
            StepType.RUN_SCRIPT -> "অন্য script চালাবে"
            StepType.PATH -> "আঁকা পথ (freehand) - ${durationMs}ms"
            StepType.DUAL_TAP -> "দুই জায়গায় একসাথে tap ($x, $y) + ($x2, $y2)"
            StepType.HOLD_UNTIL -> "চেপে ধরা ($x, $y) - \"$text\" না আসা পর্যন্ত (${timeoutSec}s)"
            StepType.LOOP_START -> "লুপ শুরু (${if (x2 == 0) "অসীম" else "$x2 বার"})"
            StepType.LOOP_END -> "লুপ শেষ"
            StepType.SET_VAR -> "Variable: $text = সংখ্যা (লেবেল: $text2)"
            StepType.IF -> "যদি ${if (text2.isNotEmpty()) text2 else "\"$text\" আছে"} -> ধাপ $x, নাহলে -> ধাপ $x2"
            StepType.GOTO -> "লাফ দাও ধাপ $x-এ"
            StepType.RANDOM -> "Random: $x% সম্ভাবনায় ধাপ $x2-এ লাফ"
            StepType.DRAG_DROP -> "Drag & drop ($x, $y) -> ($x2, $y2)"
            StepType.THREE_FINGER -> "তিন-আঙুল swipe ($x, $y থেকে $y2)"
            StepType.KEY_EVENT -> "Key: ${keyName(x)}"
            StepType.CLIPBOARD_SET -> "Clipboard-এ রাখো: \"$text\""
            StepType.CLIPBOARD_GET -> "Clipboard থেকে সংখ্যা -> variable $text"
            StepType.FILE_WRITE -> "ফাইলে লেখো: $text.txt"
            StepType.HTTP_CALL -> "HTTP ${if (text2.isEmpty()) "GET" else text2}: $text"
            StepType.IMAGE_CONDITION -> "ছবি থাকলে পরের ধাপ, না থাকলে ধাপ $x (অপেক্ষা ${timeoutSec}s)"
            StepType.COLOR_IF -> "রঙ ($x, $y) ~ $text হলে -> ধাপ $x2, নাহলে -> ধাপ ${if (delayMinMs > 0) delayMinMs.toInt() else 0}"
            StepType.FINGERPRINT -> "স্ক্রিন ফিঙ্গারপ্রিন্ট মিললে -> ধাপ $x, নাহলে -> ধাপ $x2"
            StepType.LABEL -> "লেবেল: $text"
            StepType.WHILE_START -> "যতক্ষণ ${if (text2.isNotEmpty()) text2 else "\"$text\" আছে"} (শর্ত-লুপ শুরু)"
        }
        val rep = if (repeat > 1) " x$repeat" else ""
        val delay = if (delayMaxMs > delayMinMs && delayMaxMs > 0)
            "অপেক্ষা ${delayMinMs}-${delayMaxMs}ms" else "অপেক্ষা ${delayAfterMs}ms"
        return "$base$rep, $delay"
    }

    companion object {
        fun fromJson(o: JSONObject): Step {
            val rawType = o.optString("type", "TAP")
            val type = when (rawType) {
                // v2 migration: OCR removed in v3 (returns in Ultimate version).
                "OCR_TAP" -> StepType.TEXT_TAP
                else -> try { StepType.valueOf(rawType) } catch (e: Exception) { StepType.TAP }
            }
            return Step(
                type = type,
                x = o.optInt("x"), y = o.optInt("y"),
                x2 = o.optInt("x2"), y2 = o.optInt("y2"),
                durationMs = o.optLong("durationMs", 600),
                delayAfterMs = o.optLong("delayAfterMs", 1000),
                text = o.optString("text", ""),
                region = o.optInt("region", Region.FULL),
                timeoutSec = o.optInt("timeoutSec", 10),
                imagePath = o.optString("imagePath", ""),
                delayMinMs = o.optLong("delayMinMs", 0),
                delayMaxMs = o.optLong("delayMaxMs", 0),
                repeat = o.optInt("repeat", 1),
                anchor = o.optString("anchor", ""),
                text2 = o.optString("text2", ""),
                anchorId = o.optString("anchorId", ""),
                relX = o.optInt("relX", -1),
                relY = o.optInt("relY", -1),
                curved = o.optBoolean("curved", false),
                targetLabel = o.optString("targetLabel", "")
            )
        }
    }
}

/**
 * A saved profile / script.
 * v3 additions: work/rest cycle (workMin/restMin), end-of-page detection
 * (endDetect - needs screen capture), trigger app (triggerPackage - auto
 * start when that app opens), watcher (watchIntervalMin - re-checks and
 * alerts when a search step finds its text).
 */
data class Script(
    val id: String,
    var name: String,
    val steps: MutableList<Step> = mutableListOf(),
    var loopCount: Int = 0,
    var globalMinutes: Int = 0,
    var startDelaySec: Int = 0,
    var humanLike: Boolean = false,
    var scheduleEnabled: Boolean = false,
    var scheduleHour: Int = 8,
    var scheduleMinute: Int = 0,
    var workMin: Int = 0,
    var restMin: Int = 0,
    var endDetect: Boolean = false,
    var triggerPackage: String = "",
    var watchIntervalMin: Int = 0,
    var favorite: Boolean = false,
    /** Weekday bitmask for the daily schedule: bit 0 = Sunday ... bit 6 = Saturday. 127 = every day. */
    var scheduleDays: Int = 127,
    // v5 guards + triggers
    /** Points stored as per-mille of screen (0..1000) instead of pixels. */
    var percentPoints: Boolean = false,
    /** Daily time window (minutes of day) in which the script may run; -1 = no window. */
    var timeStartMin: Int = -1,
    var timeEndMin: Int = -1,
    /** Max starts per day; 0 = unlimited. */
    var dailyMaxRuns: Int = 0,
    var triggerWifi: String = "",
    var triggerBatteryLevel: Int = -1,
    var triggerBatteryBelow: Boolean = true,
    var triggerHeadset: Boolean = false,
    var triggerScreenOn: Boolean = false,
    var triggerNotifPkg: String = "",
    var triggerNotifText: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("loopCount", loopCount); put("globalMinutes", globalMinutes)
        put("startDelaySec", startDelaySec)
        put("humanLike", humanLike)
        put("scheduleEnabled", scheduleEnabled)
        put("scheduleHour", scheduleHour); put("scheduleMinute", scheduleMinute)
        put("workMin", workMin); put("restMin", restMin)
        put("endDetect", endDetect)
        put("triggerPackage", triggerPackage)
        put("watchIntervalMin", watchIntervalMin)
        put("favorite", favorite)
        put("scheduleDays", scheduleDays)
        put("percentPoints", percentPoints)
        put("timeStartMin", timeStartMin); put("timeEndMin", timeEndMin)
        put("dailyMaxRuns", dailyMaxRuns)
        put("triggerWifi", triggerWifi)
        put("triggerBatteryLevel", triggerBatteryLevel)
        put("triggerBatteryBelow", triggerBatteryBelow)
        put("triggerHeadset", triggerHeadset)
        put("triggerScreenOn", triggerScreenOn)
        put("triggerNotifPkg", triggerNotifPkg)
        put("triggerNotifText", triggerNotifText)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
    }

    fun loopText(): String = if (loopCount == 0) "অসীম লুপ" else "$loopCount বার লুপ"
    fun timerText(): String = if (globalMinutes == 0) "টাইমার নেই" else "$globalMinutes মিনিট টাইমার"

    /** Steps that need the screen-capture service (image / color / stable / end-detect). */
    fun needsCapture(): Boolean = endDetect || steps.any {
        it.type == StepType.IMAGE_TAP || it.type == StepType.COLOR_WAIT || it.type == StepType.WAIT_STABLE ||
            it.type == StepType.IMAGE_CONDITION || it.type == StepType.COLOR_IF || it.type == StepType.FINGERPRINT
    }

    companion object {
        fun fromJson(o: JSONObject): Script {
            val s = Script(
                id = o.optString("id", System.currentTimeMillis().toString()),
                name = o.optString("name", "Script"),
                loopCount = o.optInt("loopCount", 0),
                globalMinutes = o.optInt("globalMinutes", 0),
                startDelaySec = o.optInt("startDelaySec", 0),
                humanLike = o.optBoolean("humanLike", false),
                scheduleEnabled = o.optBoolean("scheduleEnabled", false),
                scheduleHour = o.optInt("scheduleHour", 8),
                scheduleMinute = o.optInt("scheduleMinute", 0),
                workMin = o.optInt("workMin", 0),
                restMin = o.optInt("restMin", 0),
                endDetect = o.optBoolean("endDetect", false),
                triggerPackage = o.optString("triggerPackage", ""),
                watchIntervalMin = o.optInt("watchIntervalMin", 0),
                favorite = o.optBoolean("favorite", false),
                scheduleDays = o.optInt("scheduleDays", 127),
                percentPoints = o.optBoolean("percentPoints", false),
                timeStartMin = o.optInt("timeStartMin", -1),
                timeEndMin = o.optInt("timeEndMin", -1),
                dailyMaxRuns = o.optInt("dailyMaxRuns", 0),
                triggerWifi = o.optString("triggerWifi", ""),
                triggerBatteryLevel = o.optInt("triggerBatteryLevel", -1),
                triggerBatteryBelow = o.optBoolean("triggerBatteryBelow", true),
                triggerHeadset = o.optBoolean("triggerHeadset", false),
                triggerScreenOn = o.optBoolean("triggerScreenOn", false),
                triggerNotifPkg = o.optString("triggerNotifPkg", ""),
                triggerNotifText = o.optString("triggerNotifText", "")
            )
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { s.steps.add(Step.fromJson(it)) }
            }
            return s
        }

        fun newId(): String = System.currentTimeMillis().toString() + (100..999).random().toString()
    }
}
