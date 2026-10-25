package com.autotap.app
import com.mthtamim.phonelink.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * Foreground service that runs a script step by step.
 * v3 engine: all step types, group loops, per-step repeat, delay ranges,
 * panel speed control + skip-delay, work/rest cycle, safety cap + battery
 * guard, blocked-app protection, end-of-page detection, failure
 * screenshots, watcher alert, and a saved run report.
 */
class RunnerService : Service() {

    @Volatile private var stopFlag = false
    @Volatile private var pauseFlag = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tapCount = 0
    private var loopCount = 0
    @Volatile private var pausedByCall = false
    private var runCapMs = 0L
    private var runTotalLoops = 0
    private var telephonyCallback: android.telephony.TelephonyCallback? = null
    private var startedAt = 0L
    private var startedMono = 0L
    private var failures = mutableListOf<String>()
    private var lastFrameHash = 0L
    private var noChangeStreak = 0
    // v5 state
    private var pendingStartIndex = 0
    @Volatile private var currentStepIndex = 0
    @Volatile private var lastFailStep = 0
    private var stuckHash = 0L
    private var stuckStreak = 0
    private var delayDriftSum = 0L
    private var delayDriftMax = 0L
    private var delayDriftCount = 0
    private var shakeListener: android.hardware.SensorEventListener? = null
    private var lastShakeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CMD -> {
                when (intent.getStringExtra(EXTRA_CMD)) {
                    CMD_PAUSE -> {
                        pauseFlag = true
                        FloatingPanel.setPaused(true)
                        updateNotification("বিরতি দেওয়া হয়েছে")
                    }
                    CMD_RESUME -> {
                        pauseFlag = false
                        FloatingPanel.setPaused(false)
                        updateNotification("চলছে")
                    }
                    CMD_STOP -> stopFlag = true
                    CMD_SPEED_UP -> {
                        speedFactor = (speedFactor * 1.25).coerceAtMost(3.0)
                        toast("গতি: ${"%.2f".format(speedFactor)}x")
                    }
                    CMD_SPEED_DOWN -> {
                        speedFactor = (speedFactor / 1.25).coerceAtLeast(0.34)
                        toast("গতি: ${"%.2f".format(speedFactor)}x")
                    }
                    CMD_NEXT -> skipDelay = true
                }
                return START_STICKY
            }
            ACTION_START -> {
                if (running) return START_STICKY
                val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID) ?: return START_STICKY
                pendingStartIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
                startRun(scriptId)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startRun(scriptId: String) {
        val script = Store.getScripts(this).find { it.id == scriptId } ?: run {
            stopSelf()
            return
        }
        if (script.steps.isEmpty()) {
            toast("এই script-এ কোনো step নেই")
            stopSelf()
            return
        }
        if (!TapAccessibilityService.isEnabled()) {
            toast("আগে Accessibility চালু করো (সেটিংস থেকে)")
            stopSelf()
            return
        }
        // v5 guards: daily time window + daily max runs.
        if (script.timeStartMin >= 0 && script.timeEndMin >= 0) {
            val cal = java.util.Calendar.getInstance()
            val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val s = script.timeStartMin
            val e = script.timeEndMin
            val inside = if (s <= e) nowMin in s..e else (nowMin >= s || nowMin <= e)
            if (!inside) {
                toast("এই সময়ে এই script চলবে না (সময়-জানালা সেট করা)")
                stopSelf()
                return
            }
        }
        if (script.dailyMaxRuns > 0 && Store.dayCount(this, script.id) >= script.dailyMaxRuns) {
            toast("আজকের সর্বোচ্চ রান হয়ে গেছে")
            stopSelf()
            return
        }
        Store.incDayCount(this, script.id)
        running = true
        activeScriptName = script.name
        activeScriptId = script.id
        stopFlag = false
        pauseFlag = false
        speedFactor = 1.0
        tapCount = 0
        loopCount = 0
        failures = mutableListOf()
        lastFrameHash = 0L
        noChangeStreak = 0
        startedAt = System.currentTimeMillis()
        startedMono = SystemClock.elapsedRealtime()
        currentStepIndex = 0
        lastFailStep = 0
        stuckHash = 0L
        stuckStreak = 0
        delayDriftSum = 0L
        delayDriftMax = 0L
        delayDriftCount = 0
        activeStepIndex = 0
        activeStepText = ""
        val dm = resources.displayMetrics
        activePoints = script.steps.mapNotNull { st ->
            when (st.type) {
                StepType.TAP, StepType.DUAL_TAP, StepType.SWIPE, StepType.DRAG_DROP ->
                    intArrayOf(
                        if (script.percentPoints) st.x * dm.widthPixels / 1000 else st.x,
                        if (script.percentPoints) st.y * dm.heightPixels / 1000 else st.y
                    )
                else -> null
            }
        }
        registerShakeStop()
        if (Store.runVideo(this)) {
            ScreenCaptureService.startRecording()
        }

        createChannel()
        registerCallPause()
        startForeground(NOTIF_ID, buildNotification("চলছে: ${script.name}"))
        acquireWakeLock()
        FloatingPanel.show(this)
        updateStats()

        worker = Thread { runLoop(script) }.apply { isDaemon = true; start() }
    }

    private fun runLoop(script: Script) {
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            finishRun("Accessibility বন্ধ হয়ে গেছে")
            return
        }
        val batteryGuard = Store.batteryGuard(this)
        val safetyCapMs = Store.safetyCapMinutes(this) * 60000L
        // Effective cap: the stricter of script timer and app safety cap.
        val capMs = when {
            script.globalMinutes > 0 && safetyCapMs > 0 ->
                minOf(script.globalMinutes * 60000L, safetyCapMs)
            script.globalMinutes > 0 -> script.globalMinutes * 60000L
            else -> safetyCapMs
        }
        // Start delay.
        sleepSliced(script.startDelaySec * 1000L)
        if (stopFlag) { finishRun("বন্ধ করা হয়েছে"); return }

        var workMsAcc = 0L
        val workLimitMs = script.workMin * 60000L
        val restMs = script.restMin * 60000L

        var loop = 0
        var guard = 0
        while (!stopFlag && guard < 100000) {
            guard++
            loop++
            loopCount = loop
            updateStats()
            val t0 = SystemClock.elapsedRealtime()
            runCapMs = capMs
            runTotalLoops = script.loopCount
            val startAt = if (loop == 1) pendingStartIndex else 0
            val result = execSteps(script, script.steps, svc, depth = 0, vars = mutableMapOf(), startAt = startAt)
            if (result == ExecResult.STUCK) { finishRun("স্ক্রিন বদলাচ্ছে না - আটকে গেছে মনে হওয়ায় বন্ধ হয়েছে"); return }
            if (result == ExecResult.BLOCKED) { finishRun("সুরক্ষিত অ্যাপ সামনে থাকায় বন্ধ হয়েছে"); return }
            if (result == ExecResult.PAGE_END) { finishRun("পেজ/ফিড শেষ - নিজে থেকে বন্ধ হয়েছে"); return }
            if (stopFlag) break
            if (capMs > 0 && SystemClock.elapsedRealtime() - startedMono >= capMs) break
            if (script.loopCount > 0 && loop >= script.loopCount) break
            if (batteryGuard && isBatteryLow()) { finishRun("ব্যাটারি কম থাকায় বন্ধ হয়েছে"); return }
            // Work / rest cycle.
            workMsAcc += SystemClock.elapsedRealtime() - t0
            if (workLimitMs > 0 && restMs > 0 && workMsAcc >= workLimitMs) {
                workMsAcc = 0
                FloatingPanel.setStatus("বিরতি চলছে")
                sleepSliced(restMs)
                FloatingPanel.setStatus("")
            }
        }
        finishRun("শেষ হয়েছে")
    }

    private enum class ExecResult { OK, BLOCKED, PAGE_END, STUCK }

    private data class LoopFrame(var index: Int, var remaining: Int, val isWhile: Boolean = false)

    /** Executes a step list once; honors group loops, repeat and conditions. Returns why it ended. */
    private fun execSteps(
        script: Script, steps: List<Step>, svc: TapAccessibilityService, depth: Int,
        vars: MutableMap<String, Int>, startAt: Int = 0
    ): ExecResult {
        val loops = ArrayDeque<LoopFrame>()
        val labelIndex = HashMap<String, Int>()
        steps.forEachIndexed { idx, s ->
            if (s.type == StepType.LABEL && s.text.isNotEmpty()) {
                labelIndex[s.text.trim()] = idx
            }
        }
        fun labelTarget(step: Step, numeric: Int): Int {
            if (step.targetLabel.isNotEmpty()) {
                return labelIndex[step.targetLabel.trim()] ?: -1
            }
            return numeric - 1
        }
        var i = if (depth == 0) startAt.coerceIn(0, (steps.size - 1).coerceAtLeast(0)) else 0
        var skipNext = false
        while (i < steps.size && !stopFlag) {
            while (pauseFlag && !stopFlag) {
                Thread.sleep(200)
            }
            if (stopFlag) break
            // Blocked-app protection: never tap inside a protected app.
            val pkg = svc.currentPackage()
            if (pkg.isNotEmpty() && pkg != packageName && Store.blockedPackages(this).contains(pkg)) {
                return ExecResult.BLOCKED
            }
            val step = steps[i]
            if (skipNext) {
                skipNext = false
                i++
                continue
            }
            if (depth == 0) {
                currentStepIndex = i + 1
                activeStepIndex = i
                activeStepText = step.describe()
            }
            FloatingPanel.setProgress(
                "ধাপ ${i + 1}/${steps.size}: ${step.describe().take(34)}",
                progressFraction(i, steps.size)
            )
            FloatingPanel.setStepPill(i + 1, steps.size)
            // Flow control + variables live here, not in execStep.
            when (step.type) {
                StepType.LABEL -> {
                    i++
                    continue
                }
                StepType.GOTO -> {
                    val target = labelTarget(step, step.x)
                    i = if (target in steps.indices) target else i + 1
                    continue
                }
                StepType.RANDOM -> {
                    if (kotlin.random.Random.nextInt(100) < step.x.coerceIn(0, 100)) {
                        val target = labelTarget(step, step.x2)
                        i = if (target in steps.indices) target else i + 1
                    } else {
                        i++
                    }
                    continue
                }
                StepType.IMAGE_CONDITION -> {
                    val found = awaitImage(step) != null
                    val target = if (found) step.x else step.x2
                    i = if (target in 1..steps.size) target - 1 else i + 1
                    continue
                }
                StepType.COLOR_IF -> {
                    val cond = colorMatches(script, step)
                    val target = if (cond) step.x2 else step.delayMinMs.toInt()
                    i = if (target in 1..steps.size) target - 1 else i + 1
                    continue
                }
                StepType.FINGERPRINT -> {
                    val cond = fingerprintMatches(script, step)
                    val target = if (cond) step.x else step.x2
                    i = if (target in 1..steps.size) target - 1 else i + 1
                    continue
                }
                StepType.SET_VAR -> {
                    val n = svc.findNumber(step.text2, step.region)
                    if (n != null) vars[step.text] = n
                    i++
                    continue
                }
                StepType.IF -> {
                    val cond = evalCondition(step, svc, vars)
                    if (cond && step.targetLabel.isNotEmpty()) {
                        val lt = labelIndex[step.targetLabel.trim()]
                        i = lt ?: (i + 1)
                        continue
                    }
                    val target = if (cond) step.x else step.x2
                    i = if (target in 1..steps.size) target - 1 else i + 1
                    continue
                }
                else -> Unit
            }
            when (step.type) {
                StepType.LOOP_START -> {
                    loops.addLast(LoopFrame(i, if (step.x2 <= 0) Int.MAX_VALUE else step.x2))
                    i++
                    continue
                }
                StepType.WHILE_START -> {
                    loops.addLast(LoopFrame(i, Int.MAX_VALUE, isWhile = true))
                    i++
                    continue
                }
                StepType.LOOP_END -> {
                    val frame = loops.lastOrNull()
                    if (frame == null) {
                        i++
                        continue
                    }
                    if (frame.isWhile) {
                        val condStep = steps[frame.index]
                        if (evalCondition(condStep, svc, vars)) {
                            i = frame.index + 1
                        } else {
                            loops.removeLast()
                            i++
                        }
                    } else {
                        frame.remaining--
                        if (frame.remaining > 0) {
                            i = frame.index + 1
                        } else {
                            loops.removeLast()
                            i++
                        }
                    }
                    continue
                }
                else -> Unit
            }
            val reps = step.repeat.coerceIn(1, 999)
            var r = 0
            while (r < reps && !stopFlag) {
                r++
                val res = execStep(script, step, svc, depth, vars) { skipNext = true }
                if (res == ExecResult.BLOCKED || res == ExecResult.PAGE_END) return res
                if (r < reps) postStepDelay(step, script)
            }
            postStepDelay(step, script)
            // Stuck guard: several actionable steps with an unchanged screen
            // means the script is probably tapping into a void.
            if (depth == 0 && Store.stuckGuard(this) &&
                (step.type == StepType.TAP || step.type == StepType.TEXT_TAP || step.type == StepType.SWIPE)
            ) {
                val frame = ScreenCaptureService.snapshot()
                if (frame != null) {
                    val h = frameHash(frame)
                    if (stuckHash != 0L && kotlin.math.abs(h - stuckHash) < 5000L) {
                        stuckStreak++
                    } else {
                        stuckStreak = 0
                    }
                    stuckHash = h
                    if (stuckStreak >= Store.stuckLimit(this)) {
                        failures.add("স্ক্রিন বদলাচ্ছে না - আটকে গেছে মনে হচ্ছে")
                        return ExecResult.STUCK
                    }
                }
            }
            i++
        }
        return ExecResult.OK
    }

    /** Shared condition: variable expression, image presence, or text presence. */
    private fun evalCondition(step: Step, svc: TapAccessibilityService, vars: Map<String, Int>): Boolean {
        if (step.text2.isNotEmpty()) return evalVarExpr(step.text2, vars)
        if (step.imagePath.isNotEmpty()) return awaitImage(step) != null
        if (step.text.isEmpty()) return false
        return svc.findText(step.text, step.region) != null
    }

    private fun parseHexColor(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6) return null
        return try {
            android.graphics.Color.rgb(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16)
            )
        } catch (e: Exception) { null }
    }

    private fun colorClose(a: Int, b: Int): Boolean {
        val dr = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
        val dg = kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
        val db = kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
        return dr + dg + db < 120
    }

    private fun colorMatches(script: Script, step: Step): Boolean {
        val want = parseHexColor(step.text) ?: return false
        val frame = ScreenCaptureService.snapshot() ?: return false
        return colorClose(pixelAt(frame, pxOf(script, step.x), pyOf(script, step.y)), want)
    }

    private fun fingerprintMatches(script: Script, step: Step): Boolean {
        val frame = ScreenCaptureService.snapshot() ?: return false
        val parts = step.text.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        for (part in parts) {
            val f = part.split(",")
            if (f.size != 3) return false
            val x = f[0].trim().toIntOrNull() ?: return false
            val y = f[1].trim().toIntOrNull() ?: return false
            val want = parseHexColor(f[2]) ?: return false
            if (!colorClose(pixelAt(frame, pxOf(script, x), pyOf(script, y)), want)) return false
        }
        return true
    }

    /** Percent-mode coordinate conversion (points stored 0..1000 of screen). */
    private fun pxOf(script: Script, v: Int): Int =
        if (script.percentPoints) v * resources.displayMetrics.widthPixels / 1000 else v

    private fun pyOf(script: Script, v: Int): Int =
        if (script.percentPoints) v * resources.displayMetrics.heightPixels / 1000 else v

    private fun progressFraction(stepIndex: Int, stepCount: Int): Float {
        val inLoop = if (stepCount > 0) (stepIndex + 1).toFloat() / stepCount else 0f
        return when {
            runTotalLoops > 0 -> (((loopCount - 1) + inLoop) / runTotalLoops).coerceIn(0f, 1f)
            runCapMs > 0 && startedAt > 0 ->
                ((System.currentTimeMillis() - startedAt).toFloat() / runCapMs).coerceIn(0f, 1f)
            else -> inLoop
        }
    }

    /** Evaluates "name>=100" / "name<50" / "name==7" against the run variables. */
    private fun evalVarExpr(expr: String, vars: Map<String, Int>): Boolean {
        val m = Regex("^(.+?)\\s*(>=|<=|==|>|<)\\s*(-?\\d+)$").find(expr.trim()) ?: return false
        val value = vars[m.groupValues[1].trim()] ?: return false
        val target = m.groupValues[3].toIntOrNull() ?: return false
        return when (m.groupValues[2]) {
            ">=" -> value >= target
            "<=" -> value <= target
            "==" -> value == target
            ">" -> value > target
            "<" -> value < target
            else -> false
        }
    }

    /** Runs one step; onSkip is called by CONDITION when the next step must be skipped. */
    private fun execStep(
        script: Script,
        step: Step,
        svc: TapAccessibilityService,
        depth: Int,
        vars: MutableMap<String, Int>,
        onSkip: () -> Unit
    ): ExecResult {
        fun jitter(v: Int): Int = if (script.humanLike) v + Random.nextInt(-8, 9) else v
        when (step.type) {
            StepType.TAP -> {
                var px = pxOf(script, jitter(step.x))
                var py = pyOf(script, jitter(step.y))
                // Anchor resolution order: resource-id (most precise), then
                // label, then the raw point as the final fallback.
                var resolved = false
                if (step.anchorId.isNotEmpty()) {
                    val found = svc.findByViewId(step.anchorId, step.relX, step.relY)
                    if (found != null) {
                        px = found[0]; py = found[1]; resolved = true
                    }
                }
                if (!resolved && step.anchor.isNotEmpty()) {
                    val found = svc.findText(step.anchor, Region.FULL)
                    if (found != null) {
                        px = found[0]; py = found[1]
                    }
                }
                dodgeIfNeeded(px, py)
                verifiedTap(svc, px, py, step.durationMs)
                tapCount++
                Store.addHeatPoint(this, script.id, px, py)
            }
            StepType.DUAL_TAP -> {
                dodgeIfNeeded(pxOf(script, step.x), pyOf(script, step.y))
                svc.dualTap(pxOf(script, step.x), pyOf(script, step.y), pxOf(script, step.x2), pyOf(script, step.y2))
                tapCount += 2
            }
            StepType.SWIPE -> {
                if (step.curved) {
                    svc.swipeCurved(pxOf(script, step.x), pyOf(script, step.y), pxOf(script, step.x2), pyOf(script, step.y2), step.durationMs)
                } else {
                    svc.swipe(pxOf(script, jitter(step.x)), pyOf(script, jitter(step.y)), pxOf(script, jitter(step.x2)), pyOf(script, jitter(step.y2)), step.durationMs)
                }
                tapCount++
                if (script.endDetect && depth == 0) {
                    Thread.sleep(700)
                    if (frameUnchanged()) {
                        noChangeStreak++
                        if (noChangeStreak >= 2) return ExecResult.PAGE_END
                    } else {
                        noChangeStreak = 0
                    }
                }
            }
            StepType.PINCH -> svc.pinch(pxOf(script, step.x), pyOf(script, step.y), step.x2, step.y2, step.durationMs)
            StepType.PATH -> {
                val pts = step.text.split(";").mapNotNull { pair ->
                    val parts = pair.split(",")
                    if (parts.size == 2) {
                        val a = parts[0].trim().toIntOrNull()
                        val b = parts[1].trim().toIntOrNull()
                        if (a != null && b != null) intArrayOf(a, b) else null
                    } else null
                }
                if (pts.size >= 2) {
                    val scaled = if (script.percentPoints) {
                        pts.map { intArrayOf(pxOf(script, it[0]), pyOf(script, it[1])) }
                    } else pts
                    svc.tracePath(scaled, step.durationMs)
                }
            }
            StepType.SYSTEM -> svc.globalAction(step.x)
            StepType.TYPE_TEXT -> {
                var out = step.text
                for ((k, v) in vars) out = out.replace("{$k}", v.toString())
                svc.typeText(out)
            }
            StepType.TEXT_TAP -> {
                val p = awaitText(svc, step)
                if (p != null) {
                    dodgeIfNeeded(p[0], p[1])
                    svc.tap(p[0], p[1], 60)
                    tapCount++
                    if (Store.soundOnFound(this)) beep()
                    if (script.watchIntervalMin > 0) alertFound(script.name, step.text)
                } else {
                    onSearchFailure("Text tap", step.text)
                }
            }
            StepType.WAIT_TEXT -> {
                if (awaitText(svc, step) == null) onSearchFailure("অপেক্ষা", step.text)
                else if (script.watchIntervalMin > 0) alertFound(script.name, step.text)
            }
            StepType.CONDITION -> {
                if (svc.findText(step.text, step.region) == null) onSkip()
            }
            StepType.IMAGE_TAP -> {
                val p = awaitImage(step)
                if (p != null) {
                    svc.tap(p[0], p[1], 60)
                    tapCount++
                } else {
                    onSearchFailure("Image tap", "")
                }
            }
            StepType.COLOR_WAIT -> awaitColorChange(step)
            StepType.WAIT_STABLE -> awaitStable(step)
            StepType.NUMBER_TARGET -> {
                val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
                var reached = false
                while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
                    val n = svc.findNumber(step.text, step.region)
                    if (n != null && n >= step.x) {
                        reached = true
                        break
                    }
                    Thread.sleep(800)
                }
                if (!reached) onSearchFailure("সংখ্যা টার্গেট", step.text)
            }
            StepType.HOLD_UNTIL -> {
                val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
                var found = false
                while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
                    svc.tap(pxOf(script, step.x), pyOf(script, step.y), 1800)
                    if (svc.findText(step.text, step.region) != null) {
                        found = true
                        break
                    }
                }
                if (!found) onSearchFailure("চেপে ধরা", step.text)
            }
            StepType.DRAG_DROP -> {
                svc.dragDrop(pxOf(script, step.x), pyOf(script, step.y), pxOf(script, step.x2), pyOf(script, step.y2), 600, step.durationMs)
                tapCount++
            }
            StepType.THREE_FINGER -> {
                svc.threeFingerSwipe(pxOf(script, step.x), pyOf(script, step.y), pyOf(script, step.y2), step.durationMs)
                tapCount++
            }
            StepType.KEY_EVENT -> pressKey(step.x)
            StepType.CLIPBOARD_SET -> {
                var out = step.text
                for ((k, v) in vars) out = out.replace("{$k}", v.toString())
                try {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("AutoTap", out))
                } catch (e: Exception) { /* clipboard is best-effort */ }
            }
            StepType.CLIPBOARD_GET -> {
                try {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                    val n = Regex("\\d[\\d,]*").find(clipText)?.value?.replace(",", "")?.toIntOrNull() ?: 0
                    if (step.text.isNotEmpty()) vars[step.text] = n
                } catch (e: Exception) { /* clipboard is best-effort */ }
            }
            StepType.FILE_WRITE -> {
                try {
                    var out = step.text2
                    for ((k, v) in vars) out = out.replace("{$k}", v.toString())
                    val dir = File(getExternalFilesDir(null), "AutoTap").apply { mkdirs() }
                    val safeName = step.text.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "log" }
                    File(dir, "$safeName.txt").appendText(out + "\n")
                } catch (e: Exception) { /* file write is best-effort */ }
            }
            StepType.HTTP_CALL -> {
                try {
                    val conn = java.net.URL(step.text).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = step.text2.ifEmpty { "GET" }.uppercase()
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.connect()
                    conn.responseCode
                    conn.disconnect()
                } catch (e: Exception) { /* HTTP signal is best-effort */ }
            }
            StepType.RUN_SCRIPT -> {
                if (depth < 1) {
                    val target = Store.getScripts(this).find { it.id == step.text }
                    if (target != null && target.steps.isNotEmpty()) {
                        val res = execSteps(script, target.steps, svc, depth + 1, vars)
                        if (res != ExecResult.OK) return res
                    }
                }
            }
            else -> Unit
        }
        return ExecResult.OK
    }

    private fun postStepDelay(step: Step, script: Script) {
        val delayT0 = System.currentTimeMillis()
        var delay = if (step.delayMaxMs > step.delayMinMs && step.delayMaxMs > 0) {
            val lo = step.delayMinMs
            val hi = step.delayMaxMs
            lo + (Math.random() * (hi - lo)).toLong()
        } else {
            step.delayAfterMs
        }
        if (script.humanLike && delay > 0) {
            val factor = 1.0 + (Math.random() * 0.3 - 0.15)
            delay = (delay * factor).toLong()
        }
        delay = (delay / speedFactor).toLong()
        if (delay > 0) sleepSliced(delay)
        // Delay drift: how late the real wait was versus the intended wait.
        val drift = (System.currentTimeMillis() - delayT0) - delay
        if (delay > 0 && drift >= 0) {
            delayDriftSum += drift
            delayDriftCount++
            if (drift > delayDriftMax) delayDriftMax = drift
        }
    }

    /** Tap with optional verification: if the screen did not change after
     *  the tap, retry up to the configured count (needs screen capture). */
    private fun verifiedTap(svc: TapAccessibilityService, x: Int, y: Int, holdMs: Long) {
        if (!Store.tapVerify(this)) {
            svc.tap(x, y, holdMs)
            return
        }
        val before = ScreenCaptureService.snapshot()?.let { frameHash(it) } ?: -1L
        svc.tap(x, y, holdMs)
        if (before < 0L) return
        var tries = 0
        val maxTries = Store.tapRetry(this)
        while (tries < maxTries && !stopFlag) {
            Thread.sleep(450)
            val after = ScreenCaptureService.snapshot()?.let { frameHash(it) } ?: -1L
            if (after < 0L || kotlin.math.abs(after - before) >= 5000L) return
            svc.tap(x, y, holdMs)
            tries++
        }
    }

    /** Media / volume keys through AudioManager. */
    private fun pressKey(code: Int) {
        try {
            val am = getSystemService(android.media.AudioManager::class.java) ?: return
            when (code) {
                1 -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE, 0
                )
                2 -> am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER, 0
                )
                3 -> am.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                )
                4 -> am.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                )
                5 -> am.dispatchMediaKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                )
            }
        } catch (e: Exception) { /* keys are best-effort */ }
    }

    /** Moves the floating panel away when the next tap would land under it. */
    private fun dodgeIfNeeded(x: Int, y: Int) {
        val b = FloatingPanel.panelBounds() ?: return
        if (x in b[0]..b[2] && y in b[1]..b[3]) {
            FloatingPanel.dodge()
            Thread.sleep(280)
        }
    }

    private fun beep() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(this, uri)?.play()
        } catch (e: Exception) { /* sound is best-effort */ }
    }

    private fun awaitText(svc: TapAccessibilityService, step: Step): IntArray? {
        val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
            svc.findText(step.text, step.region)?.let { return it }
            Thread.sleep(400)
        }
        return svc.findText(step.text, step.region)
    }

    private fun awaitImage(step: Step): IntArray? {
        val file = if (step.imagePath.isNotEmpty()) File(step.imagePath) else null
        val template = if (file != null && file.exists()) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } else null
        if (template == null) return null
        val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
            val frame = ScreenCaptureService.snapshot()
            if (frame != null) {
                val tol = Store.templateTolerance(this)
                val found = ImageSearch.find(frame, template, step.region, (1.0 - tol) * 130.0)
                if (found != null) return found
            }
            Thread.sleep(600)
        }
        return null
    }

    private fun pixelAt(bmp: Bitmap, x: Int, y: Int): Int {
        val px = x.coerceIn(0, bmp.width - 1)
        val py = y.coerceIn(0, bmp.height - 1)
        return bmp.getPixel(px, py)
    }

    private fun awaitColorChange(step: Step) {
        val first = ScreenCaptureService.snapshot() ?: return
        val base = pixelAt(first, step.x, step.y)
        val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
            val frame = ScreenCaptureService.snapshot()
            if (frame != null && pixelAt(frame, step.x, step.y) != base) return
            Thread.sleep(300)
        }
        onSearchFailure("রঙ বদল", "(${step.x}, ${step.y})")
    }

    /** Frame similarity hash: sum of a 16x16 downscale. */
    private fun frameHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var sum = 0L
        for (yy in 0 until 16) for (xx in 0 until 16) sum += small.getPixel(xx, yy).toLong()
        if (small !== bmp) small.recycle()
        return sum
    }

    private fun frameUnchanged(): Boolean {
        val frame = ScreenCaptureService.snapshot() ?: return false
        val h = frameHash(frame)
        val same = lastFrameHash != 0L && kotlin.math.abs(h - lastFrameHash) < 5000L
        lastFrameHash = h
        return same
    }

    private fun awaitStable(step: Step) {
        val deadline = SystemClock.elapsedRealtime() + step.timeoutSec * 1000L
        var last = 0L
        var stableCount = 0
        while (SystemClock.elapsedRealtime() < deadline && !stopFlag) {
            val frame = ScreenCaptureService.snapshot()
            if (frame != null) {
                val h = frameHash(frame)
                if (last != 0L && kotlin.math.abs(h - last) < 5000L) {
                    stableCount++
                    if (stableCount >= 2) return
                } else {
                    stableCount = 0
                }
                last = h
            }
            Thread.sleep(500)
        }
    }

    private fun onSearchFailure(kind: String, text: String) {
        lastFailStep = currentStepIndex
        failures.add(if (text.isNotEmpty()) "$kind: \"$text\" পাওয়া যায়নি" else "$kind পাওয়া যায়নি")
        saveFailureScreenshot()
    }

    /** Saves the current screen (when capture is running) for debugging a failed search step. */
    private fun saveFailureScreenshot() {
        try {
            val frame = ScreenCaptureService.snapshot() ?: return
            val dir = File(filesDir, "failures").apply { mkdirs() }
            val out = File(dir, "fail-${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { frame.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Store.setLastFailureShot(this, out.absolutePath)
        } catch (e: Exception) {
            // Screenshot is best-effort debugging help only.
        }
    }

    /** Watcher alert: a watched script found its text - tell the user loudly. */
    private fun alertFound(scriptName: String, text: String) {
        createChannel()
        val nm = getSystemService(NotificationManager::class.java)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoTap পাহারা: পাওয়া গেছে!")
            .setContentText("Script \"$scriptName\"-এ \"$text\" পাওয়া গেছে")
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIF_ID, notif)
    }

    private fun isBatteryLow(): Boolean {
        return try {
            val bm = getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            level in 1..14 && !charging
        } catch (e: Exception) { false }
    }

    private fun sleepSliced(totalMs: Long) {
        if (Store.precisionTiming(this) && totalMs in 1..3000) {
            val deadline = SystemClock.uptimeMillis() + totalMs
            while (!stopFlag) {
                if (skipDelay) {
                    skipDelay = false
                    return
                }
                while (pauseFlag && !stopFlag) Thread.sleep(50)
                val leftMs = deadline - SystemClock.uptimeMillis()
                if (leftMs <= 0) break
                if (leftMs > 12) Thread.sleep(minOf(leftMs - 8, 50)) else Thread.yield()
            }
            skipDelay = false
            return
        }
        var left = totalMs
        while (left > 0 && !stopFlag) {
            if (skipDelay) {
                skipDelay = false
                return
            }
            while (pauseFlag && !stopFlag) Thread.sleep(200)
            val chunk = minOf(left, 100L)
            Thread.sleep(chunk)
            left -= chunk
        }
        skipDelay = false
    }

    private fun updateStats() {
        mainHandler.post {
            FloatingPanel.setStats(tapCount, startedMono, loopCount)
        }
    }

    private fun finishRun(reason: String) {
        unregisterShakeStop()
        val videoPath = ScreenCaptureService.stopRecording()
        // Per-script stats (runs / completions / failures / failing step).
        try {
            Store.bumpScriptStat(this, activeScriptId, failures.isEmpty(), lastFailStep)
        } catch (e: Exception) { /* stats are best-effort */ }
        // Save the run report before resetting state.
        val durSec = if (startedMono > 0) ((SystemClock.elapsedRealtime() - startedMono) / 1000).coerceAtLeast(0) else ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val report = StringBuilder()
        report.append("শেষ রান: $activeScriptName\n")
        report.append("কারণ: $reason\n")
        report.append("সময়: ${durSec / 60} মিনিট ${durSec % 60} সেকেন্ড, Tap: $tapCount, লুপ: $loopCount")
        if (failures.isNotEmpty()) {
            report.append("\nযা পাওয়া যায়নি (${failures.size}):\n")
            failures.take(5).forEach { report.append("- $it\n") }
        }
        if (delayDriftCount > 0) {
            report.append("\nDelay drift: গড় ${delayDriftSum / delayDriftCount}ms, সর্বোচ্চ ${delayDriftMax}ms")
        }
        if (videoPath != null) {
            report.append("\nরানের ভিডিও: $videoPath")
        }
        Store.setLastReport(this, report.toString().trim())
        Store.addLifetime(this, tapCount, durSec)
        if (Store.soundOnFinish(this)) beep()
        if (Store.vibrateOnFinish(this)) {
            try {
                val vib = getSystemService(android.os.Vibrator::class.java)
                vib?.vibrate(android.os.VibrationEffect.createOneShot(350, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) { /* vibration is best-effort */ }
        }
        postFinalNotification(reason)

        running = false
        activeScriptName = ""
        activeScriptId = ""
        mainHandler.post {
            FloatingPanel.hide()
            toast("Script বন্ধ: $reason")
        }
        releaseWakeLock()
        unregisterCallPause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun postFinalNotification(reason: String) {
        try {
            createChannel()
            val nm = getSystemService(NotificationManager::class.java)
            val notif = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AutoTap রান শেষ")
                .setContentText("$activeScriptName - $reason, Tap: $tapCount")
                .setAutoCancel(true)
                .build()
            nm.notify(FINAL_NOTIF_ID, notif)
        } catch (e: Exception) {
            // Final summary is best-effort.
        }
    }

    /** Shake-to-stop: a hard shake of the phone stops the run (optional). */
    private fun registerShakeStop() {
        if (!Store.shakeStop(this)) return
        try {
            val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    event ?: return
                    val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                    val mag = kotlin.math.sqrt(x * x + y * y + z * z)
                    if (mag > 30f) {
                        val now = System.currentTimeMillis()
                        if (now - lastShakeAt > 1500) {
                            lastShakeAt = now
                            stopFlag = true
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            shakeListener = listener
        } catch (e: Exception) { /* shake stop is best-effort */ }
    }

    private fun unregisterShakeStop() {
        try {
            val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
            shakeListener?.let { sm.unregisterListener(it) }
        } catch (e: Exception) { /* ignore */ }
        shakeListener = null
    }

    /** Auto-pause while a phone call is active (optional, needs phone-state permission). */
    private fun registerCallPause() {
        if (!Store.callPauseEnabled(this)) return
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        onCallState(state)
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            }
        } catch (e: Exception) { /* call pause is best-effort */ }
    }

    private fun onCallState(state: Int) {
        if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
            if (pausedByCall) {
                pausedByCall = false
                pauseFlag = false
                FloatingPanel.setPaused(false)
            }
        } else {
            if (!pauseFlag) {
                pausedByCall = true
                pauseFlag = true
                FloatingPanel.setPaused(true)
                FloatingPanel.setStatus("কল চলছে - বিরতি")
            }
        }
    }

    private fun unregisterCallPause() {
        try {
            val cb = telephonyCallback ?: return
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.unregisterTelephonyCallback(cb)
            }
        } catch (e: Exception) { /* ignore */ }
        telephonyCallback = null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTap:runner").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            wakeLock = null
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AutoTap চলমান Script", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoTap")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        stopFlag = true
        running = false
        releaseWakeLock()
        mainHandler.post { FloatingPanel.hide() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.autotap.app.START"
        const val ACTION_CMD = "com.autotap.app.CMD"
        const val EXTRA_SCRIPT_ID = "script_id"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_CMD = "cmd"
        const val CMD_PAUSE = "pause"
        const val CMD_RESUME = "resume"
        const val CMD_STOP = "stop"
        const val CMD_SPEED_UP = "speed_up"
        const val CMD_SPEED_DOWN = "speed_down"
        const val CMD_NEXT = "next"
        private const val CHANNEL_ID = "autotap_runner"
        private const val NOTIF_ID = 41
        private const val FINAL_NOTIF_ID = 44
        private const val ALERT_NOTIF_ID = 45

        @Volatile var running = false
            private set
        @Volatile var activeScriptName = ""
            private set
        @Volatile var activeScriptId = ""
            private set
        @Volatile var speedFactor = 1.0
        @Volatile var skipDelay = false
        // v5: live run info for the panel mini-map.
        @Volatile var activePoints: List<IntArray> = emptyList()
        @Volatile var activeStepIndex = 0
        @Volatile var activeStepText = ""

        fun startFrom(ctx: Context, scriptId: String, startIndex: Int) {
            val intent = Intent(ctx, RunnerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT_ID, scriptId)
                putExtra(EXTRA_START_INDEX, startIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun start(ctx: Context, scriptId: String) {
            val intent = Intent(ctx, RunnerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT_ID, scriptId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun sendCmd(ctx: Context, cmd: String) {
            val intent = Intent(ctx, RunnerService::class.java).apply {
                action = ACTION_CMD
                putExtra(EXTRA_CMD, cmd)
            }
            try {
                ctx.startService(intent)
            } catch (e: Exception) {
                // Service already stopped; nothing to command.
            }
        }
    }
}
