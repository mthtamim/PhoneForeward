package com.autotap.app
import com.mthtamim.phonelink.R

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var headerBar: LinearLayout
    private var tab: Int
        get() = selectedTab
        set(value) { selectedTab = value }
    private var pendingExport: Script? = null
    private var pendingStartScriptId: String? = null
    private var pendingImageScriptId: String? = null

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scriptId = pendingStartScriptId
        pendingStartScriptId = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            if (scriptId != null) {
                RunnerService.start(this, scriptId)
                toast("চালু হলো - Image/রঙ ধাপের জন্য স্ক্রিন capture-ও চালু")
            }
        } else if (scriptId != null) {
            toast("Capture permission ছাড়া Image/রঙ ধাপ চলবে না")
        }
    }

    private val templatePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val scriptId = pendingImageScriptId
        pendingImageScriptId = null
        if (uri != null && scriptId != null) {
            try {
                val dir = java.io.File(filesDir, "templates").apply { mkdirs() }
                val file = java.io.File(dir, "tpl_${System.currentTimeMillis()}.img")
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val script = Store.getScript(this, scriptId) ?: return@registerForActivityResult
                if (pendingImageCondition) {
                    pendingImageCondition = false
                    script.steps.add(Step(StepType.IMAGE_CONDITION, imagePath = file.absolutePath, timeoutSec = 15))
                    Store.upsert(this, script)
                    toast("ছবি-শর্ত step যোগ হলো (না পেলে পরের ধাপই চলবে; লাফ চাইলে ধাপ নম্বর বসাও)")
                    rebuild()
                    return@registerForActivityResult
                }
                script.steps.add(Step(StepType.IMAGE_TAP, imagePath = file.absolutePath, timeoutSec = 15))
                Store.upsert(this, script)
                toast("Image tap step যোগ হলো")
                rebuild()
            } catch (e: Exception) {
                toast("ছবি নেওয়া যায়নি")
            }
        }
    }

    companion object {
        // Tab survives the recreate() used to re-render the chip row.
        var selectedTab = 0
        // App-lock PIN is asked once per process.
        var unlocked = false
        // Scripts tab: when set, the tab shows ONLY this script's editor page.
        var editingScriptId: String? = null
        var scriptSearch: String = ""
        var scriptFilter: Int = 0 // 0 all, 1 favorites, 2 scheduled
        var pendingImageCondition: Boolean = false
        // v5.2 mock UI state: collapsibles + settings hub page.
        var runSettingsOpen: Boolean = false
        var addStepsOpen: Boolean = false
        var expandedStep: Int = -1
        var settingsPage: Int = 0
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartIntent(intent)
    }

    /** v5.3.1: registered ONCE from onCreate — onNewIntent never fires on a fresh launch. */
    private fun registerBackHandlerAndRestore() {
        // Phone Back button: leave sub-pages first, exit only at a tab root.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editingScriptId != null -> {
                        editingScriptId = null
                        expandedStep = -1
                        rebuild()
                    }
                    settingsPage != 0 -> {
                        settingsPage = 0
                        rebuild()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        // Fresh reinstall: pull the automatic backup back (Downloads/AutoTap).
        if (Store.getScripts(this).isEmpty()) {
            val restored = Backup.tryRestore(this)
            if (restored > 0) {
                toast("আগের backup থেকে ${restored}টি script ফিরে এসেছে")
            }
        }
        TriggerService.start(this)
    }

    /** Long-press icon shortcuts carry a script id to start immediately. */
    private fun handleStartIntent(intent: Intent?) {
        val id = intent?.getStringExtra("auto_start_id") ?: return
        intent.removeExtra("auto_start_id")
        val script = Store.getScript(this, id) ?: return
        if (script.steps.isEmpty()) return
        if (isAccessibilityOn()) {
            startScript(script)
            moveTaskToBack(true)
        } else {
            toast("Shortcut চালাতে আগে Accessibility চালু করো")
        }
    }

    /** Publishes long-press icon shortcuts for favorite scripts (up to 3). */
    private fun publishShortcuts() {
        try {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            val favorites = Store.getScripts(this).filter { it.favorite }.take(3)
            val shortcuts = favorites.map { script ->
                val i = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("auto_start_id", script.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                android.content.pm.ShortcutInfo.Builder(this, "fav_${script.id}")
                    .setShortLabel(script.name.take(24))
                    .setLongLabel("চালাও: ${script.name}")
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_autotap))
                    .setIntent(i)
                    .build()
            }
            sm.dynamicShortcuts = shortcuts
        } catch (e: Exception) { /* shortcuts are a bonus */ }
    }

    private val qrImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
                val json = bmp?.let { QrShare.decode(it) }
                if (json != null) {
                    val n = Store.importJson(this, json)
                    if (n > 0) { toast("$n টি script import হলো"); rebuild() }
                    else toast("QR থেকে script পড়া যায়নি")
                } else toast("ছবিতে QR পাওয়া যায়নি")
            } catch (e: Exception) {
                toast("QR import হয়নি")
            }
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val script = pendingExport
        pendingExport = null
        if (uri != null && script != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(script.toJson().toString(2).toByteArray(Charsets.UTF_8))
                }
                toast("Export হলো: ${script.name}")
            } catch (e: Exception) {
                toast("Export হয়নি")
            }
        }
    }

    private var pendingExportText: String? = null

    private val exportTextLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri != null && text != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                toast("Export হলো")
            } catch (e: Exception) {
                toast("Export হয়নি")
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("empty")
                val trimmed = text.trim()
                if (trimmed.startsWith("{") && JSONObject(trimmed).has("scripts")) {
                    val n = Backup.importBackup(this, trimmed)
                    toast(if (n > 0) "Backup থেকে ${n}টি script এলো" else "Backup ফাইলে script পাওয়া যায়নি")
                    rebuild()
                    return@registerForActivityResult
                }
                if (trimmed.startsWith("[")) {
                    val n = Store.importJson(this, trimmed)
                    toast(if (n > 0) "${n}টি script import হলো" else "Import হয়নি")
                    rebuild()
                    return@registerForActivityResult
                }
                val parsed = Script.fromJson(JSONObject(text))
                val imported = Script(
                    id = Script.newId(), name = parsed.name,
                    steps = parsed.steps, loopCount = parsed.loopCount,
                    globalMinutes = parsed.globalMinutes, startDelaySec = parsed.startDelaySec,
                    humanLike = parsed.humanLike
                )
                Store.upsert(this, imported)
                Store.setSelectedId(this, imported.id)
                toast("Import হলো: ${imported.name} (${imported.steps.size} step)")
                rebuild()
            } catch (e: Exception) {
                toast("Import হয়নি - ফাইলটা ঠিক script JSON না")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App lock: when a PIN is set, ask for it once per process.
        val pin = Store.appPin(this)
        if (pin.isNotEmpty() && !unlocked) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "PIN"
                setTextColor(Ui.TEXT)
            }
            AlertDialog.Builder(this)
                .setTitle("AutoTap লক করা")
                .setMessage("তোমার PIN দাও")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("খোলো") { _, _ ->
                    if (input.text.toString() == pin) {
                        unlocked = true
                        recreate()
                    } else {
                        toast("ভুল PIN")
                        finish()
                    }
                }
                .setNegativeButton("বের হও") { _, _ -> finish() }
                .show()
            return
        }
        handleStartIntent(intent)
        registerBackHandlerAndRestore()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
        }
        headerBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootLayout.addView(headerBar)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Bottom navigation bar (v3 UI): the active tab is an emerald-stroked pill.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 2))
        }
        listOf("হোম", "স্ক্রিপ্ট", "টেমপ্লেট", "সেটিংস").forEachIndexed { i, label ->
            val item = android.widget.TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (tab == i) Ui.BRIGHT else Ui.SECONDARY)
                setPadding(0, Ui.dp(this@MainActivity, 9), 0, Ui.dp(this@MainActivity, 9))
                background = Ui.rounded(this@MainActivity, Ui.RAISED, 999, stroke = tab == i)
                setOnClickListener { tab = i; recreate() }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = Ui.dp(this@MainActivity, 8)
            })
        }
        rootLayout.addView(nav)
        setContentView(rootLayout)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) rebuild()
        try { WidgetProvider.refresh(this) } catch (e: Exception) {}
        publishShortcuts()
    }

    private fun rebuild() {
        if (!::content.isInitialized) return
        updateHeader()
        content.removeAllViews()
        when (tab) {
            0 -> buildHome()
            1 -> buildScripts()
            2 -> buildTemplates()
            else -> buildSettings()
        }
    }

    // ---------- helpers ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun smallBtn(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false
        setTextColor(Ui.TEXT)
        background = Ui.rounded(this@MainActivity, Ui.RAISED, 14, stroke = true)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@MainActivity, 4); rightMargin = Ui.dp(this@MainActivity, 4)
            topMargin = Ui.dp(this@MainActivity, 6)
        }
    }

    private fun btnRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { row.addView(it) }
        return row
    }

    /** Compact button for dense step rows: no forced min height. */
    private fun miniBtn(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false
        setTextColor(Ui.TEXT)
        background = Ui.rounded(this@MainActivity, Ui.RAISED, 12, stroke = true)
        minimumHeight = 0
        minHeight = 0
        setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 7),
            Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 7))
        setOnClickListener { action() }
    }

    /** Compact row: miniBtns spread evenly with small gaps. */
    private fun miniRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = Ui.dp(this, 6)
            b.layoutParams = lp
            row.addView(b)
        }
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        return row
    }

    private fun editTextDialog(title: String, initial: String, numeric: Boolean, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setTextColor(Ui.TEXT)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("ঠিক আছে") { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun confirmDialog(title: String, msg: String, done: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("হ্যাঁ") { _, _ -> done() }
            .setNegativeButton("না", null)
            .show()
    }

    /** Starts a script, asking for screen-capture consent first when it has OCR/Image steps. */
    private fun startScript(script: Script) {
        when {
            script.steps.isEmpty() -> toast("এই script-এ কোনো step নেই - আগে পয়েন্ট বসাও")
            !isAccessibilityOn() -> toast("আগে Accessibility চালু করো")
            script.needsCapture() && ScreenCaptureService.instance == null -> {
                pendingStartScriptId = script.id
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(mpm.createScreenCaptureIntent())
            }
            else -> {
                RunnerService.start(this, script.id)
                toast("চালু হলো - এখন যেকোনো অ্যাপে যাও, floating panel থেকে নিয়ন্ত্রণ করো")
            }
        }
    }

    /** Region chooser used when adding / editing search steps. */
    private fun chooseRegion(done: (Int) -> Unit) {
        val names = arrayOf("পুরো স্ক্রিন", "উপরের অর্ধেক", "নিচের অর্ধেক", "মাঝের অংশ")
        AlertDialog.Builder(this)
            .setTitle("কোন অংশে খুঁজবে?")
            .setItems(names) { _, which -> done(which) }
            .show()
    }

    /** Adds a text-based step (TEXT_TAP / WAIT_TEXT / OCR_TAP / CONDITION) via dialogs. */
    private fun addSearchStep(script: Script, type: StepType) {
        editTextDialog("কোন শব্দ খুঁজবে?", "", numeric = false) { word ->
            if (word.isEmpty()) return@editTextDialog
            chooseRegion { region ->
                script.steps.add(Step(type, text = word, region = region, timeoutSec = 15))
                Store.upsert(this, script)
                toast("Step যোগ হলো")
                rebuild()
            }
        }
    }

    private fun isAccessibilityOn(): Boolean {
        if (TapAccessibilityService.isEnabled()) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith(packageName + "/") }
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /** Launchable user apps as (label, package) pairs, for pickers. */
    private fun installedApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        val out = mutableListOf<Pair<String, String>>()
        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            out.add(ri.loadLabel(pm).toString() to pkg)
        }
        return out.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun triggerLabel(script: Script): String = when {
        script.triggerPackage.isEmpty() -> "বন্ধ"
        script.triggerPackage == "CHARGING" -> "চার্জে লাগালে"
        else -> {
            val app = installedApps().firstOrNull { it.second == script.triggerPackage }
            (app?.first ?: script.triggerPackage) + " খুললে"
        }
    }

    private fun pickTrigger(script: Script) {
        val apps = installedApps()
        val labels = mutableListOf("বন্ধ", "চার্জে লাগালে")
        labels.addAll(apps.map { it.first })
        AlertDialog.Builder(this)
            .setTitle("কখন নিজে চালু হবে?")
            .setItems(labels.toTypedArray()) { _, which ->
                script.triggerPackage = when (which) {
                    0 -> ""
                    1 -> "CHARGING"
                    else -> apps[which - 2].second
                }
                Store.upsert(this, script)
                rebuild()
            }
            .show()
    }

    // ---------- হোম ----------

    /** Small square icon button used in headers (fixed size, never stretches). */
    private fun iconChip(text: String, action: () -> Unit): android.widget.TextView {
        return android.widget.TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(this@MainActivity, Ui.RAISED, 12, stroke = true)
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@MainActivity, 40), Ui.dp(this@MainActivity, 40))
            setOnClickListener { action() }
        }
    }

    /**
     * Dynamic top bar: on a sub-page (script editor, settings group) the big
     * app header is replaced by a back arrow + that page's name (+ play in
     * the editor). At tab roots the normal app title shows.
     */
    private fun updateHeader() {
        if (!::headerBar.isInitialized) return
        headerBar.removeAllViews()
        val editing = if (tab == 1) editingScriptId?.let { Store.getScript(this, it) } else null
        val groupTitles = mapOf(
            1 to "চালানো ও গতি", 2 to "ট্রিগার ও সময়", 3 to "নিরাপত্তা",
            4 to "বাইরের সংযোগ", 5 to "অ্যাপ সম্পর্কে"
        )
        when {
            editing != null -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, Ui.dp(this@MainActivity, 10))
                }
                row.addView(iconChip("←") {
                    editingScriptId = null
                    expandedStep = -1
                    rebuild()
                })
                val name = android.widget.TextView(this).apply {
                    text = editing.name + if (editing.favorite) " ★" else ""
                    textSize = 18f
                    setTextColor(Ui.TEXT)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 8), 0)
                }
                row.addView(name)
                val runningThis = RunnerService.running && RunnerService.activeScriptId == editing.id
                row.addView(roundRunBtn(runningThis) {
                    if (runningThis) {
                        RunnerService.sendCmd(this, RunnerService.CMD_STOP)
                    } else if (isAccessibilityOn()) {
                        Store.setSelectedId(this, editing.id)
                        startScript(editing)
                        moveTaskToBack(true)
                    } else toast("আগে Accessibility চালু করো")
                })
                headerBar.addView(row)
            }
            tab == 3 && settingsPage != 0 -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, Ui.dp(this@MainActivity, 10))
                }
                row.addView(iconChip("←") { settingsPage = 0; rebuild() })
                val name = android.widget.TextView(this).apply {
                    text = groupTitles[settingsPage] ?: "সেটিংস"
                    textSize = 18f
                    setTextColor(Ui.TEXT)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(Ui.dp(this@MainActivity, 10), 0, 0, 0)
                }
                row.addView(name)
                headerBar.addView(row)
            }
            else -> {
                headerBar.addView(Ui.appTitle(this, "AutoTap - অটো ক্লিক ও স্ক্রল"))
                headerBar.addView(Ui.secondary(this, "নিজের বানানো script অনুযায়ী অটো tap, swipe আর scroll"))
            }
        }
    }

    private fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun buildHome() {
        // Status chips on one line (mock design): tap a missing one to fix it.
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(this@MainActivity, 6))
        }
        fun chip(ok: Boolean, label: String, action: () -> Unit) {
            val tv = android.widget.TextView(this).apply {
                text = (if (ok) "✓ " else "✗ ") + label
                textSize = 12.5f
                setTextColor(if (ok) Ui.BRIGHT else Ui.DANGER)
                background = Ui.rounded(this@MainActivity, Ui.CARD, 999, stroke = true)
                setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 5),
                    Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = Ui.dp(this@MainActivity, 8) }
                setOnClickListener { action() }
            }
            chips.addView(tv)
        }
        chip(isAccessibilityOn(), "Accessibility") {
            if (!isAccessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("AutoTap খুঁজে On করো। Restricted settings এলে App info থেকে Allow restricted settings দাও")
            }
        }
        chip(canOverlay(), "Overlay") {
            if (!canOverlay()) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        chip(batteryUnrestricted(), "ব্যাটারি") {
            if (!batteryUnrestricted()) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) { toast("সেটিংস খোলা যায়নি") }
                }
            }
        }
        content.addView(chips)
        content.addView(Ui.infoRow(this, "ব্যাটারি Unrestricted না থাকলে ফোনের battery saver trigger/সময়মতো চালু মেরে ফেলতে পারে (বিশেষ করে Infinix/XOS-এ)। উপরের 'ব্যাটারি' চিপে চাপলেই ঠিক করা যায়।"))

        // Hero card: the selected script, one big run button.
        val selected = Store.selectedScript(this)
        val hero = Ui.card(this)
        if (selected == null) {
            hero.addView(Ui.cardTitle(this, "কোনো script নেই"))
            hero.addView(Ui.body(this, "স্ক্রিপ্ট ট্যাব থেকে একটা বানাও, বা নিচের অটো স্ক্রল চাপো।"))
        } else {
            hero.addView(Ui.cardTitle(this, selected.name))
            hero.addView(Ui.secondary(this,
                "${selected.steps.size}টি step - ${selected.loopText()} - ${selected.timerText()}"))
            if (RunnerService.running) {
                hero.addView(Ui.secondary(this, "এখন চলছে: ${RunnerService.activeScriptName}"))
                hero.addView(Ui.dangerButton(this, "■ বন্ধ করো") {
                    RunnerService.sendCmd(this, RunnerService.CMD_STOP)
                    toast("বন্ধ হচ্ছে")
                })
            } else {
                hero.addView(Ui.primaryButton(this, "▶ চালু করো") { startScript(selected) })
            }
            hero.addView(Ui.secondaryButton(this, "অন্য script বাছো") {
                val all = Store.getScripts(this)
                AlertDialog.Builder(this)
                    .setTitle("কোন script?")
                    .setItems(all.map { it.name }.toTypedArray()) { _, which ->
                        Store.setSelectedId(this, all[which].id)
                        rebuild()
                    }
                    .show()
            })
        }
        content.addView(hero)

        // One-tap auto scroll: builds AND starts in a single tap.
        val quickCard = Ui.card(this)
        quickCard.addView(Ui.cardTitle(this, "এক ট্যাপে অটো স্ক্রল"))
        quickCard.addView(Ui.primaryButton(this, "⇅ অটো স্ক্রল চালু") {
            val m = resources.displayMetrics
            val cxp = m.widthPixels / 2
            val existing = Store.getScripts(this).firstOrNull { it.name == "অটো স্ক্রল" }
            val script = existing ?: Script(id = Script.newId(), name = "অটো স্ক্রল")
            script.steps.clear()
            script.steps.add(Step(
                StepType.SWIPE, cxp, (m.heightPixels * 0.75).toInt(),
                cxp, (m.heightPixels * 0.28).toInt(),
                durationMs = 650, delayAfterMs = 2500))
            script.loopCount = 0
            script.globalMinutes = 0
            Store.upsert(this, script)
            Store.setSelectedId(this, script.id)
            if (isAccessibilityOn()) {
                startScript(script)
                moveTaskToBack(true)
            } else {
                toast("আগে Accessibility চালু করো")
                rebuild()
            }
        })
        quickCard.addView(Ui.infoRow(this, "Reels, Shorts বা লেখা পড়ার জন্য: নিচ থেকে উপরে swipe হতে থাকবে, প্রতি swipe-এর পর 2.5 সেকেন্ড অপেক্ষা।"))
        content.addView(quickCard)

        // Last run, one compact line + optional failure screenshot.
        Store.lastReport(this)?.let { report ->
            val reportCard = Ui.card(this)
            reportCard.addView(Ui.cardTitle(this, "শেষ রান"))
            reportCard.addView(Ui.body(this, report))
            Store.lastFailureShot(this)?.let { path ->
                reportCard.addView(Ui.secondaryButton(this, "ব্যর্থতার screenshot দেখো") {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(path)
                        if (bmp != null) {
                            val iv = android.widget.ImageView(this)
                            iv.setImageBitmap(bmp)
                            iv.adjustViewBounds = true
                            AlertDialog.Builder(this).setView(iv)
                                .setPositiveButton("বন্ধ", null).show()
                        } else toast("ছবিটা আর নেই")
                    } catch (e: Exception) { toast("ছবি খোলা যায়নি") }
                })
            }
            content.addView(reportCard)
        }

        val lifeSec = Store.lifetimeSeconds(this)
        content.addView(Ui.secondary(this,
            "মোট: ${Store.lifetimeRuns(this)} রান · ${Store.lifetimeTaps(this)} tap · ${lifeSec / 60} মিনিট"))
    }

    // ---------- স্ক্রিপ্ট ----------

    /** Round green play/stop button (mock design). */
    private fun roundRunBtn(running: Boolean, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = if (running) "\u25A0" else "\u25B6"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Ui.BG)
            background = Ui.rounded(this@MainActivity, Ui.ACCENT, 999)
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@MainActivity, 44), Ui.dp(this@MainActivity, 44))
            setOnClickListener { onClick() }
        }
    }

    /** One-line step label for the compact editor rows. */
    private fun shortDescribe(step: Step): String = when (step.type) {
        StepType.TAP -> "Tap (${step.x}, ${step.y})"
        StepType.SWIPE -> "Swipe " + when {
            step.y2 < step.y -> "\u2191"
            step.y2 > step.y -> "\u2193"
            step.x2 > step.x -> "\u2192"
            else -> "\u2190"
        }
        StepType.TEXT_TAP -> "Text tap \"${step.text.take(14)}\""
        StepType.WAIT_TEXT -> "Wait text \"${step.text.take(14)}\""
        StepType.IMAGE_TAP -> "Image tap"
        StepType.DUAL_TAP -> "Dual tap"
        StepType.DRAG_DROP -> "Drag & drop"
        StepType.THREE_FINGER -> "3-finger swipe"
        StepType.PATH -> "\u0986\u0981\u0995\u09BE path"
        else -> step.describe().take(32)
    }

    /** Right-side timing text for a compact step row. */
    private fun stepTiming(step: Step): String {
        val dur = when (step.type) {
            StepType.TAP, StepType.SWIPE, StepType.DRAG_DROP,
            StepType.PINCH, StepType.PATH, StepType.THREE_FINGER -> "${step.durationMs}ms \u00B7 "
            else -> ""
        }
        val delay = if (step.delayMaxMs > step.delayMinMs && step.delayMaxMs > 0)
            "${step.delayMinMs}-${step.delayMaxMs}ms" else "${step.delayAfterMs}ms"
        return dur + delay
    }

    private fun buildScripts() {
        // Editor page mode: one script takes the whole tab so it can be
        // worked on without the clutter of every other script's details.
        editingScriptId?.let { eid ->
            val editing = Store.getScript(this, eid)
            if (editing != null) {
                buildScriptEditorPage(editing)
                return
            }
            editingScriptId = null
        }
        val scripts = Store.getScripts(this)
        if (scripts.isEmpty()) {
            val empty = Ui.card(this)
            empty.addView(Ui.body(this, "কোনো script নেই এখনো।"))
            content.addView(empty)
            return
        }
        val selectedId = Store.selectedScript(this)?.id
        // Search + filter chips.
        val searchCard = Ui.card(this)
        val searchInput = EditText(this).apply {
            hint = "script খোঁজো..."
            setText(scriptSearch)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
        }
        searchCard.addView(searchInput)
        searchCard.addView(btnRow(
            smallBtn("খোঁজো") { scriptSearch = searchInput.text.toString(); rebuild() },
            smallBtn(if (scriptFilter == 0) "[সব]" else "সব") { scriptFilter = 0; rebuild() },
            smallBtn(if (scriptFilter == 1) "[★ প্রিয়]" else "★ প্রিয়") { scriptFilter = 1; rebuild() },
            smallBtn(if (scriptFilter == 2) "[সময়-ঠিক]" else "সময়-ঠিক") { scriptFilter = 2; rebuild() }
        ))
        content.addView(searchCard)

        val filtered = scripts.filter { s ->
            (scriptFilter == 0 || (scriptFilter == 1 && s.favorite) || (scriptFilter == 2 && s.scheduleEnabled)) &&
                (scriptSearch.isEmpty() || s.name.contains(scriptSearch, ignoreCase = true))
        }
        if (filtered.isEmpty()) {
            val none = Ui.card(this)
            none.addView(Ui.body(this, "এই খোঁজে কোনো script নেই"))
            content.addView(none)
        }
        filtered.forEach { script ->
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(this@MainActivity, Ui.CARD, 16, stroke = true)
                setPadding(Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 8),
                    Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(this@MainActivity, 10) }
            }
            val runningThis = RunnerService.running && RunnerService.activeScriptId == script.id
            rowCard.addView(roundRunBtn(runningThis) {
                if (runningThis) {
                    RunnerService.sendCmd(this, RunnerService.CMD_STOP)
                } else if (isAccessibilityOn()) {
                    Store.setSelectedId(this, script.id)
                    startScript(script)
                    moveTaskToBack(true)
                } else {
                    toast("আগে Accessibility চালু করো")
                }
            })
            val stats = Store.scriptStats(this, script.id)
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 4), 0)
            }
            mid.addView(Ui.body(this, script.name + if (script.id == selectedId) " ✓" else ""))
            mid.addView(Ui.secondary(this,
                "${script.steps.size} ধাপ" +
                    (if (script.favorite) " · ★" else "") +
                    (if (script.scheduleEnabled) " · %02d:%02d".format(script.scheduleHour, script.scheduleMinute) else "") +
                    (if (stats[0] > 0) " · চলেছে ${stats[0]} বার" else "")))
            mid.setOnClickListener {
                Store.setSelectedId(this, script.id)
                editingScriptId = script.id
                expandedStep = -1; runSettingsOpen = false; addStepsOpen = false
                rebuild()
            }
            rowCard.addView(mid)
            rowCard.addView(smallBtn("⋮") { showScriptMenu(script, selectedId) })
            content.addView(rowCard)
        }
        val addRow = Ui.card(this)
        addRow.addView(Ui.primaryButton(this, "+ নতুন script") {
            editTextDialog("script-এর নাম", "", numeric = false) { name ->
                if (name.isNotEmpty()) {
                    val s = Script(id = Script.newId(), name = name)
                    Store.upsert(this, s)
                    Store.setSelectedId(this, s.id)
                    rebuild()
                }
            }
        })
        addRow.addView(btnRow(
            smallBtn("Import (JSON)") { importLauncher.launch(arrayOf("application/json")) },
            smallBtn("QR ছবি") { qrImportLauncher.launch(arrayOf("image/*")) },
            smallBtn("QR scan") { startActivity(Intent(this, ScanActivity::class.java)) }
        ))
        content.addView(addRow)
    }

    /** The ⋮ menu on a script row: everything that used to be separate buttons. */
    private fun showScriptMenu(script: Script, selectedId: String?) {
        val items = arrayOf(
            "এডিট করো",
            "সিলেক্ট করো",
            if (script.favorite) "★ সরাও" else "★ Star দাও",
            "কপি করো",
            "Export (JSON ফাইল)",
            "QR শেয়ার",
            "Heatmap দেখো",
            "হিসাব রিসেট",
            "মুছো"
        )
        AlertDialog.Builder(this)
            .setTitle(script.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Store.setSelectedId(this, script.id)
                        editingScriptId = script.id
                        expandedStep = -1; runSettingsOpen = false; addStepsOpen = false
                        rebuild()
                    }
                    1 -> { Store.setSelectedId(this, script.id); rebuild() }
                    2 -> {
                        script.favorite = !script.favorite
                        Store.upsert(this, script)
                        publishShortcuts()
                        rebuild()
                    }
                    3 -> {
val merged = Script.fromJson(JSONObject(script.toJson().toString()).apply {
                            put("id", Script.newId()); put("name", script.name + " কপি")
                        })
                        Store.upsert(this, merged)
                        toast("কপি তৈরি হলো")
                        rebuild()
                    }
                    4 -> {
                        pendingExport = script
                        exportLauncher.launch("${script.name}.json")
                    }
                    5 -> {
                        val text = script.toJson().toString()
                        if (text.length > 1800) {
                            toast("Script বড় - QR-তে হবে না, Export ব্যবহার করো")
                        } else {
                            val bmp = QrShare.encode(text)
                                if (bmp == null) {
                                    toast("Scriptটা QR-এর জন্য বড় - ফাইল Export ব্যবহার করো")
                                } else {
                                    val iv = android.widget.ImageView(this)
                                    iv.setImageBitmap(bmp)
                                    iv.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24))
                                    iv.setBackgroundColor(android.graphics.Color.WHITE)
                                    AlertDialog.Builder(this)
                                        .setTitle("QR - ${script.name}")
                                        .setView(iv)
                                        .setPositiveButton("বন্ধ", null)
                                        .show()
                                }
                        }
                    }
                    6 -> {
                        startActivity(Intent(this, HeatmapActivity::class.java)
                            .putExtra(HeatmapActivity.EXTRA_ID, script.id))
                    }
                    7 -> {
                        Store.resetScriptStat(this, script.id)
                        toast("হিসাব রিসেট হয়েছে")
                        rebuild()
                    }
                    8 -> {
                        AlertDialog.Builder(this)
                            .setTitle("মুছে ফেলবে?")
                            .setMessage("'${script.name}' চিরতরে মুছে যাবে।")
                            .setPositiveButton("মুছো") { _, _ ->
                                ScheduleHelper.disarm(this, script)
                                WatchHelper.cancel(this, script.id)
                                Store.delete(this, script.id)
                                toast("মুছে ফেলা হয়েছে")
                                rebuild()
                            }
                            .setNegativeButton("না", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun openRecorder(script: Script) {
        if (!canOverlay()) {
            toast("আগে Overlay permission দাও")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!isAccessibilityOn()) {
            toast("আগে Accessibility চালু করো")
            return
        }
        Store.setEditScriptId(this, script.id)
        Store.setSelectedId(this, script.id)
        startService(Intent(this, RecordOverlayService::class.java))
        toast("যে অ্যাপে রেকর্ড করবে সেটা খোলো - নিজের tap/swipe-ই step হয়ে যাবে")
        moveTaskToBack(true)
    }

    private fun openEditor(script: Script) {
        if (!canOverlay()) {
            toast("আগে Overlay permission দাও")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        Store.setEditScriptId(this, script.id)
        startService(Intent(this, EditOverlayService::class.java))
        toast("যে অ্যাপে চালাবে সেটা খোলো - মার্কার টেনে বসিয়ে Done চাপো")
        moveTaskToBack(true)
    }

    private fun openPicker(script: Script, mode: String) {
        if (!canOverlay()) {
            toast("আগে Overlay permission দাও")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!isAccessibilityOn()) {
            toast("আগে Accessibility চালু করো")
            return
        }
        Store.setSelectedId(this, script.id)
        PickerOverlayService.start(this, script.id, mode)
        toast("যে অ্যাপে চালাবে সেটা খোলো - তারপর element-এ আঙুল ছোঁয়াও")
        moveTaskToBack(true)
    }

    /** Shows a short ring highlight where a test tap will land. */
    private fun highlightAt(x: Int, y: Int) {
        if (!canOverlay()) return
        runOnUiThread {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val size = Ui.dp(this, 56)
                val v = android.widget.TextView(this).apply {
                    background = Ui.circle(this@MainActivity, 0x00000000)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(Ui.dp(this@MainActivity, 3), Ui.BRIGHT)
                        setColor(0x22000000)
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x - size / 2
                    this.y = y - size / 2
                }
                wm.addView(v, params)
                v.postDelayed({ try { wm.removeView(v) } catch (e: Exception) {} }, 800)
            } catch (e: Exception) {}
        }
    }

    /** Runs ONE step immediately so the user can verify it before a full run. */
    private fun testStep(step: Step) {
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            toast("আগে Accessibility চালু করো")
            return
        }
        toast("3 সেকেন্ড পর এই একটা step-ই চলবে - টার্গেট অ্যাপে যাও")
        Thread {
            Thread.sleep(3000)
            try {
                when (step.type) {
                    StepType.TAP -> { highlightAt(step.x, step.y); svc.tap(step.x, step.y, step.durationMs) }
                    StepType.DUAL_TAP -> { highlightAt(step.x, step.y); svc.dualTap(step.x, step.y, step.x2, step.y2) }
                    StepType.SWIPE -> { highlightAt(step.x2, step.y2); svc.swipe(step.x, step.y, step.x2, step.y2, step.durationMs) }
                    StepType.PINCH -> svc.pinch(step.x, step.y, step.x2, step.y2, step.durationMs)
                    StepType.PATH -> {
                        val pts = step.text.split(";").mapNotNull { pair ->
                            val parts = pair.split(",")
                            val a = parts.getOrNull(0)?.trim()?.toIntOrNull()
                            val b = parts.getOrNull(1)?.trim()?.toIntOrNull()
                            if (a != null && b != null) intArrayOf(a, b) else null
                        }
                        if (pts.size >= 2) svc.tracePath(pts, step.durationMs)
                    }
                    StepType.SYSTEM -> svc.globalAction(step.x)
                    StepType.TYPE_TEXT -> svc.typeText(step.text)
                    StepType.TEXT_TAP -> {
                        val p = svc.findText(step.text, step.region)
                        runOnUiThread {
                            if (p != null) {
                                highlightAt(p[0], p[1])
                                Thread { Thread.sleep(300); svc.tap(p[0], p[1], 60) }.start()
                                toast("পাওয়া গেছে: \"${step.text}\"")
                            } else toast("পাওয়া যায়নি: \"${step.text}\"")
                        }
                    }
                    StepType.WAIT_TEXT, StepType.CONDITION -> {
                        val found = svc.findText(step.text, step.region) != null
                        runOnUiThread { toast(if (found) "এখন স্ক্রিনে আছে: \"${step.text}\"" else "এখন স্ক্রিনে নেই: \"${step.text}\"") }
                    }
                    StepType.NUMBER_TARGET -> {
                        val n = svc.findNumber(step.text, step.region)
                        runOnUiThread { toast("এখনকার সংখ্যা: ${n ?: "পাওয়া যায়নি"} (টার্গেট ${step.x})") }
                    }
                    StepType.IMAGE_TAP -> {
                        val frame = ScreenCaptureService.snapshot()
                        val file = java.io.File(step.imagePath)
                        if (frame == null) {
                            runOnUiThread { toast("Test-এর জন্য আগে একবার capture চালু করে script চালাও") }
                        } else if (file.exists()) {
                            val tpl = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            val found = if (tpl != null) ImageSearch.find(frame, tpl, step.region) else null
                            runOnUiThread {
                                if (found != null) {
                                    highlightAt(found[0], found[1])
                                    toast("ছবি পাওয়া গেছে")
                                } else toast("ছবি পাওয়া যায়নি")
                            }
                        }
                    }
                    else -> runOnUiThread { toast("এই step ধরন Test দিয়ে যাচাই হয় না - পুরো script চালিয়ে দেখো") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Test হয়নি") }
            }
        }.start()
    }


    /**
     * Dedicated full-tab editor page for ONE script (v5.1 rework):
     * slim header, run settings first, then steps, add-steps by category,
     * and script management at the bottom - everything grouped by category.
     */
    private fun buildScriptEditorPage(script: Script) {
        // ---- Header: back, name, stats, and the three main actions ----
        val top = Ui.card(this)
        val st = Store.scriptStats(this, script.id)
        top.addView(Ui.secondary(this,
            "${script.steps.size} ধাপ · চলেছে ${st[0]} বার · সম্পূর্ণ ${st[1]} · ব্যর্থ ${st[2]}" +
                if (st[3] > 0) " · শেষ ব্যর্থ ধাপ ${st[3]}" else ""))
        top.addView(btnRow(
            smallBtn("পয়েন্ট বসাও") { openEditor(script) },
            smallBtn("রেকর্ড করো") { openRecorder(script) },
            smallBtn("Element বেছে ধাপ") { openPicker(script, "") }
        ))
        content.addView(top)

        buildStepEditor(script)
        buildAddSteps(script)
        buildScriptManage(script)
        buildRunSettings(script)
    }

    /** Category: চালানোর সেটিং - loop/timer/delay, behaviour, auto-start. */
    private fun buildRunSettings(script: Script) {
        val card = Ui.card(this)
        val rsHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rsTitle = Ui.cardTitle(this, "চালানোর সেটিং")
        rsTitle.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        rsHead.addView(rsTitle)
        rsHead.addView(iconChip(if (runSettingsOpen) "▴" else "▾") {
            runSettingsOpen = !runSettingsOpen; rebuild()
        })
        card.addView(rsHead)
        card.addView(Ui.secondary(this,
            "${script.loopText()} · ${script.timerText()} · শুরু ${script.startDelaySec}s পরে"))
        if (!runSettingsOpen) {
            content.addView(card)
            return
        }
        card.addView(Ui.label(this, "সময়"))
        card.addView(btnRow(
            smallBtn("লুপ: ${script.loopText()}") {
                editTextDialog("কতবার লুপ (0 = অসীম)", script.loopCount.toString(), numeric = true) { v ->
                    script.loopCount = (v.toIntOrNull() ?: 0).coerceIn(0, 100000)
                    Store.upsert(this, script); rebuild()
                }
            },
            smallBtn("টাইমার: ${script.timerText()}") {
                editTextDialog("Global timer (মিনিট, 0 = নেই)", script.globalMinutes.toString(), numeric = true) { v ->
                    script.globalMinutes = (v.toIntOrNull() ?: 0).coerceIn(0, 100000)
                    Store.upsert(this, script); rebuild()
                }
            },
            smallBtn("শুরু দেরি: ${script.startDelaySec}s") {
                editTextDialog("Start-এর পর দেরি (সেকেন্ড)", script.startDelaySec.toString(), numeric = true) { v ->
                    script.startDelaySec = (v.toIntOrNull() ?: 0).coerceIn(0, 3600)
                    Store.upsert(this, script); rebuild()
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("সময়-জানালা: ${windowLabel(script)}") {
                if (script.timeStartMin >= 0) {
                    confirmDialog("সময়-জানালা সরাবে?", "script যেকোনো সময় চলতে পারবে।") {
                        script.timeStartMin = -1; script.timeEndMin = -1
                        Store.upsert(this, script); rebuild()
                    }
                } else {
                    editTextDialog("শুরু (ঘণ্টা 0-23)", "8", numeric = true) { sh ->
                        editTextDialog("শুরু মিনিট", "0", numeric = true) { sm ->
                            editTextDialog("শেষ (ঘণ্টা 0-23)", "22", numeric = true) { eh ->
                                editTextDialog("শেষ মিনিট", "0", numeric = true) { em ->
                                    script.timeStartMin = ((sh.toIntOrNull() ?: 8).coerceIn(0, 23)) * 60 + (sm.toIntOrNull() ?: 0).coerceIn(0, 59)
                                    script.timeEndMin = ((eh.toIntOrNull() ?: 22).coerceIn(0, 23)) * 60 + (em.toIntOrNull() ?: 0).coerceIn(0, 59)
                                    Store.upsert(this, script); rebuild()
                                }
                            }
                        }
                    }
                }
            },
            smallBtn("দিনে সর্বোচ্চ: ${if (script.dailyMaxRuns > 0) "${script.dailyMaxRuns} বার" else "সীমা নেই"}") {
                editTextDialog("দিনে সর্বোচ্চ কতবার চলতে পারবে (0 = সীমা নেই)", script.dailyMaxRuns.toString(), numeric = true) { v ->
                    script.dailyMaxRuns = (v.toIntOrNull() ?: 0).coerceIn(0, 10000)
                    Store.upsert(this, script); rebuild()
                }
            }
        ))
        card.addView(Ui.label(this, "আচরণ"))
        card.addView(btnRow(
            smallBtn("Human-like: ${if (script.humanLike) "চালু" else "বন্ধ"}") {
                script.humanLike = !script.humanLike
                Store.upsert(this, script); rebuild()
            },
            smallBtn("কাজ-বিরতি: ${if (script.workMin > 0) "${script.workMin}মি চলা / ${script.restMin}মি বিরতি" else "বন্ধ"}") {
                editTextDialog("কাজের মিনিট (0 = বন্ধ)", script.workMin.toString(), numeric = true) { wv ->
                    editTextDialog("বিরতির মিনিট", script.restMin.toString(), numeric = true) { rv ->
                        script.workMin = (wv.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        script.restMin = (rv.toIntOrNull() ?: 0).coerceIn(0, 10000)
                        Store.upsert(this, script); rebuild()
                    }
                }
            },
            smallBtn("পেজ-শেষ ধরা: ${if (script.endDetect) "চালু" else "বন্ধ"}") {
                script.endDetect = !script.endDetect
                Store.upsert(this, script); rebuild()
            }
        ))
        card.addView(btnRow(
            smallBtn("শতাংশ-পয়েন্ট: ${if (script.percentPoints) "চালু" else "বন্ধ"}") {
                script.percentPoints = !script.percentPoints
                Store.upsert(this, script); rebuild()
            }
        ))
        card.addView(Ui.label(this, "নিজে চালু (অটো)"))
        card.addView(btnRow(
            smallBtn("সময়মতো: ${if (script.scheduleEnabled) "%02d:%02d".format(script.scheduleHour, script.scheduleMinute) else "বন্ধ"}") {
                if (script.scheduleEnabled) {
                    confirmDialog("সময়মতো চালু বন্ধ", "প্রতিদিনের auto-start বন্ধ হবে।") {
                        script.scheduleEnabled = false
                        ScheduleHelper.disarm(this, script)
                        Store.upsert(this, script); rebuild()
                    }
                } else {
                    editTextDialog("ঘণ্টা (0-23)", script.scheduleHour.toString(), numeric = true) { hv ->
                        val hour = (hv.toIntOrNull() ?: 8).coerceIn(0, 23)
                        editTextDialog("মিনিট (0-59)", script.scheduleMinute.toString(), numeric = true) { mv ->
                            script.scheduleHour = hour
                            script.scheduleMinute = (mv.toIntOrNull() ?: 0).coerceIn(0, 59)
                            val dayNames = arrayOf("রবি", "সোম", "মঙ্গল", "বুধ", "বৃহস্পতি", "শুক্র", "শনি")
                            val checkedDays = BooleanArray(7) { i -> script.scheduleDays and (1 shl i) != 0 }
                            AlertDialog.Builder(this)
                                .setTitle("কোন কোন দিন চলবে?")
                                .setMultiChoiceItems(dayNames, checkedDays) { _, which, isChecked ->
                                    checkedDays[which] = isChecked
                                }
                                .setPositiveButton("সেভ") { _, _ ->
                                    var mask = 0
                                    checkedDays.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
                                    script.scheduleDays = if (mask == 0) 127 else mask
                                    script.scheduleEnabled = true
                                    Store.upsert(this, script)
                                    ScheduleHelper.arm(this, script)
                                    toast("সময় ঠিক হলো %02d:%02d".format(script.scheduleHour, script.scheduleMinute))
                                    rebuild()
                                }
                                .setNegativeButton("বাতিল", null)
                                .show()
                        }
                    }
                }
            },
            smallBtn("ট্রিগার: ${triggerLabel(script)}") { pickTrigger(script) },
            smallBtn("পাহারা: ${if (script.watchIntervalMin > 0) "প্রতি ${script.watchIntervalMin} মিনিটে" else "বন্ধ"}") {
                editTextDialog("কত মিনিট পরপর চেক করবে (0 = বন্ধ)", script.watchIntervalMin.toString(), numeric = true) { v ->
                    script.watchIntervalMin = (v.toIntOrNull() ?: 0).coerceIn(0, 100000)
                    Store.upsert(this, script)
                    WatchHelper.arm(this, script)
                    toast(if (script.watchIntervalMin > 0) "পাহারা চালু - শব্দ পেলে alert আসবে" else "পাহারা বন্ধ")
                    rebuild()
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("আরও ট্রিগার (WiFi/ব্যাটারি/হেডফোন/স্ক্রিন/Notification)") { moreTriggers(script) }
        ))
        card.addView(Ui.infoRow(this, "Human-like চালু থাকলে tap-এর জায়গায় সামান্য random নড়াচড়া আর delay-তে +-15% বদল হয়। সময়মতো চালু best effort - চালুর সময় Accessibility On থাকতে হবে।"))
        content.addView(card)
    }

    /** Category: Step তালিকা - each step with its own controls + inline editors. */
    private fun buildStepEditor(script: Script) {
        val card = Ui.card(this)
        card.addView(Ui.cardTitle(this, "Step তালিকা (${script.steps.size})"))
        card.addView(miniRow(
            miniBtn("Undo") {
                if (Store.undoOnce(this, script.id)) {
                    toast("আগের অবস্থায় ফিরলো")
                    rebuild()
                } else toast("Undo করার মতো আগের সংস্করণ নেই")
            },
            miniBtn("ইতিহাস") {
                val hist = Store.history(this, script.id)
                if (hist.isEmpty()) {
                    toast("এখনো কোনো পুরনো সংস্করণ নেই")
                } else {
                    val labels = hist.map {
                        java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(it.first))
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("কোন সংস্করণ ফিরিয়ে আনবে?")
                        .setItems(labels) { _, which ->
                            try {
                                val restored = Script.fromJson(JSONObject(hist[which].second))
                                Store.upsert(this, restored)
                                Store.setSelectedId(this, restored.id)
                                toast("ফিরিয়ে আনা হলো")
                                rebuild()
                            } catch (e: Exception) { toast("ফিরিয়ে আনা যায়নি") }
                        }
                        .show()
                }
            },
            miniBtn("Pre-flight") {
                PreflightOverlayService.start(this)
                toast("টার্গেট অ্যাপে গিয়ে 'এখন চেক করো' চাপো")
                moveTaskToBack(true)
            },
            miniBtn("Setup কপি") {
                val others = Store.getScripts(this).filter { it.id != script.id && it.steps.isNotEmpty() }
                if (others.isEmpty()) {
                    toast("কপি করার মতো অন্য script নেই")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("কোন script-এর পয়েন্ট setup নেবে?")
                        .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                            confirmDialog("Setup বদলে যাবে", "এই script-এর Tap/Swipe/Dual stepগুলো '${others[which].name}'-এর setup দিয়ে বদলে যাবে (বাকি ধাপ থাকবে)।") {
                                val keep = script.steps.filter {
                                    it.type != StepType.TAP && it.type != StepType.SWIPE && it.type != StepType.DUAL_TAP
                                }
                                val copied = others[which].steps.filter {
                                    it.type == StepType.TAP || it.type == StepType.SWIPE || it.type == StepType.DUAL_TAP
                                }
                                script.steps.clear()
                                script.steps.addAll(copied)
                                script.steps.addAll(keep)
                                Store.upsert(this, script)
                                rebuild()
                            }
                        }
                        .show()
                }
            }
        ))
        if (script.steps.isEmpty()) {
            card.addView(Ui.body(this, "কোনো step নেই। উপরে 'পয়েন্ট বসাও' চেপে স্ক্রিনে মার্কার বসাও, বা নিচ থেকে ধাপ যোগ করো।"))
        }
        script.steps.forEachIndexed { idx, step ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(this@MainActivity, Ui.RAISED, 14, stroke = true)
                setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4),
                    Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(this@MainActivity, 6) }
            }
            val isOpen = expandedStep == idx
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val handle = android.widget.TextView(this).apply {
                text = "≡"
                setTextColor(Ui.MUTED)
                textSize = 17f
                setPadding(Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 6),
                    Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6))
            }
            line.addView(handle)
            val rowLabel = android.widget.TextView(this).apply {
                text = "${idx + 1} · ${shortDescribe(step)}"
                setTextColor(Ui.TEXT)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { expandedStep = if (isOpen) -1 else idx; rebuild() }
            }
            line.addView(rowLabel)
            val rowTiming = android.widget.TextView(this).apply {
                text = stepTiming(step)
                setTextColor(Ui.MUTED)
                textSize = 12f
                setOnClickListener { expandedStep = if (isOpen) -1 else idx; rebuild() }
            }
            line.addView(rowTiming)
            val chev = android.widget.TextView(this).apply {
                text = if (isOpen) " \u25BE" else " \u25B8"
                setTextColor(Ui.MUTED)
                textSize = 13f
            }
            line.addView(chev)
            box.addView(line)
            if (isOpen) {
            val thumbFile = java.io.File(filesDir, "thumbs/" + script.id + "_" + idx + ".png")
            if ((step.type == StepType.TAP || step.type == StepType.DRAG_DROP) && thumbFile.exists()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    val iv = android.widget.ImageView(this)
                    iv.setImageBitmap(bmp)
                    iv.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72))
                    box.addView(iv)
                }
            }
            box.addView(miniRow(
                miniBtn("▶ এই ধাপ থেকে") {
                    if (isAccessibilityOn()) {
                        Store.setSelectedId(this, script.id)
                        RunnerService.startFrom(this, script.id, idx)
                        moveTaskToBack(true)
                    } else toast("আগে Accessibility চালু করো")
                },
                miniBtn("Test") { testStep(step) },
                miniBtn("উপরে") {
                    if (idx > 0) {
                        val tmp = script.steps[idx - 1]
                        script.steps[idx - 1] = step
                        script.steps[idx] = tmp
                        expandedStep = idx - 1
                        Store.upsert(this, script); rebuild()
                    }
                },
                miniBtn("নিচে") {
                    if (idx < script.steps.size - 1) {
                        val tmp = script.steps[idx + 1]
                        script.steps[idx + 1] = step
                        script.steps[idx] = tmp
                        expandedStep = idx + 1
                        Store.upsert(this, script); rebuild()
                    }
                },
                miniBtn("মোছো") {
                    script.steps.removeAt(idx)
                    expandedStep = -1
                    Store.upsert(this, script); rebuild()
                }
            ))
            box.addView(miniRow(
                miniBtn("Delay: ${if (step.delayMaxMs > 0) "${step.delayMinMs}-${step.delayMaxMs}ms" else "${step.delayAfterMs}ms"}") {
                    editTextDialog("অপেক্ষা ms, বা range যেমন 800-1500", step.delayAfterMs.toString(), numeric = false) { v ->
                        val range = v.split("-").map { it.trim().toLongOrNull() }
                        if (range.size == 2 && range[0] != null && range[1] != null && range[1]!! > range[0]!!) {
                            script.steps[idx] = step.copy(
                                delayMinMs = range[0]!!.coerceIn(0, 3600000),
                                delayMaxMs = range[1]!!.coerceIn(0, 3600000),
                                delayAfterMs = range[0]!!)
                        } else {
                            val ms = v.trim().toLongOrNull() ?: return@editTextDialog
                            script.steps[idx] = step.copy(
                                delayAfterMs = ms.coerceIn(0, 3600000), delayMinMs = 0, delayMaxMs = 0)
                        }
                        Store.upsert(this, script); rebuild()
                    }
                },
                miniBtn("পুনরাবৃত্তি: ${step.repeat} বার") {
                    editTextDialog("এই step কতবার চলবে", step.repeat.toString(), numeric = true) { v ->
                        script.steps[idx] = step.copy(repeat = (v.toIntOrNull() ?: 1).coerceIn(1, 999))
                        Store.upsert(this, script); rebuild()
                    }
                }
            ))
            if (step.type == StepType.SWIPE || step.type == StepType.DRAG_DROP ||
                step.type == StepType.THREE_FINGER || step.type == StepType.PATH ||
                step.type == StepType.DUAL_TAP || step.type == StepType.PINCH
            ) {
                box.addView(miniRow(
                    miniBtn("সময় (কতক্ষণ চলবে): ${step.durationMs}ms") {
                        editTextDialog("এই ধাপ কতক্ষণ ধরে চলবে (ms)", step.durationMs.toString(), numeric = true) { v ->
                            val ms = v.toLongOrNull() ?: return@editTextDialog
                            script.steps[idx] = step.copy(durationMs = ms.coerceIn(50, 60000))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            // Inline per-type editors, right under their own step.
            if (step.type == StepType.TAP) {
                box.addView(miniRow(
                    miniBtn("ধরে রাখা: ${step.durationMs}ms") {
                        editTextDialog("Tap ধরে রাখা (ms) - বড় মান = long press", step.durationMs.toString(), numeric = true) { v ->
                            val ms = v.toLongOrNull() ?: return@editTextDialog
                            script.steps[idx] = step.copy(durationMs = ms.coerceIn(1, 10000))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            if (step.type == StepType.TEXT_TAP || step.type == StepType.WAIT_TEXT ||
                step.type == StepType.CONDITION || step.type == StepType.NUMBER_TARGET ||
                step.type == StepType.HOLD_UNTIL || step.type == StepType.WHILE_START
            ) {
                box.addView(miniRow(
                    miniBtn("শব্দ: ${step.text}") {
                        editTextDialog("শব্দ", step.text, numeric = false) { v ->
                            if (v.isNotEmpty()) {
                                script.steps[idx] = step.copy(text = v)
                                Store.upsert(this, script); rebuild()
                            }
                        }
                    },
                    miniBtn("এলাকা: ${Region.name(step.region)}") {
                        chooseRegion { region ->
                            script.steps[idx] = step.copy(region = region)
                            Store.upsert(this, script); rebuild()
                        }
                    },
                    miniBtn("খোঁজার সময়: ${step.timeoutSec}s") {
                        editTextDialog("সর্বোচ্চ কত সেকেন্ড খুঁজবে", step.timeoutSec.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(timeoutSec = (v.toIntOrNull() ?: 10).coerceIn(1, 3600))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            if (step.type == StepType.NUMBER_TARGET) {
                box.addView(miniRow(
                    miniBtn("টার্গেট সংখ্যা: ${step.x}") {
                        editTextDialog("স্ক্রিনের সংখ্যা এত হলে থামবে/এগোবে", step.x.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(x = (v.toIntOrNull() ?: 0).coerceIn(0, 2000000000))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            if (step.type == StepType.PINCH) {
                box.addView(miniRow(
                    miniBtn("Pinch দূরত্ব: ${step.x2} থেকে ${step.y2}") {
                        editTextDialog("শুরু,শেষ দূরত্ব px (কমা দিয়ে)", "${step.x2},${step.y2}", numeric = false) { v ->
                            val parts = v.split(",").map { it.trim().toIntOrNull() }
                            if (parts.size == 2 && parts[0] != null && parts[1] != null) {
                                script.steps[idx] = step.copy(x2 = parts[0]!!, y2 = parts[1]!!)
                                Store.upsert(this, script); rebuild()
                            }
                        }
                    }
                ))
            }
            if (step.type == StepType.SWIPE) {
                box.addView(miniRow(
                    miniBtn("বাঁকা পথ: ${if (step.curved) "চালু" else "বন্ধ"}") {
                        script.steps[idx] = step.copy(curved = !step.curved)
                        Store.upsert(this, script); rebuild()
                    }
                ))
            }
            if (step.type == StepType.COLOR_IF) {
                box.addView(miniRow(
                    miniBtn("রঙ মিললে ধাপ: ${step.x2}") {
                        editTextDialog("রঙ মিললে কোন ধাপে যাবে (0 = পরের ধাপ)", step.x2.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(x2 = (v.toIntOrNull() ?: 0).coerceAtLeast(0))
                            Store.upsert(this, script); rebuild()
                        }
                    },
                    miniBtn("না মিললে ধাপ: ${step.delayMinMs}") {
                        editTextDialog("রঙ না মিললে কোন ধাপে যাবে (0 = পরের ধাপ)", step.delayMinMs.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(delayMinMs = (v.toLongOrNull() ?: 0).coerceAtLeast(0))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            if (step.type == StepType.FINGERPRINT || step.type == StepType.IMAGE_CONDITION) {
                box.addView(miniRow(
                    miniBtn("মিললে ধাপ: ${step.x}") {
                        editTextDialog("মিললে/পেলে কোন ধাপে যাবে (0 = পরের ধাপ)", step.x.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(x = (v.toIntOrNull() ?: 0).coerceAtLeast(0))
                            Store.upsert(this, script); rebuild()
                        }
                    },
                    miniBtn("না মিললে ধাপ: ${step.x2}") {
                        editTextDialog("না মিললে কোন ধাপে যাবে (0 = পরের ধাপ)", step.x2.toString(), numeric = true) { v ->
                            script.steps[idx] = step.copy(x2 = (v.toIntOrNull() ?: 0).coerceAtLeast(0))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                ))
            }
            }
            handle.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        box.tag = ev.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val startY = box.tag as? Float ?: ev.rawY
                        box.translationY = ev.rawY - startY
                        box.bringToFront()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = box.translationY
                        box.translationY = 0f
                        val rowH = box.height.coerceAtLeast(1)
                        val shift = Math.round(dy / rowH)
                        val target = (idx + shift).coerceIn(0, script.steps.size - 1)
                        if (target != idx) {
                            val movedStep = script.steps.removeAt(idx)
                            script.steps.add(target, movedStep)
                            Store.upsert(this, script)
                            toast("Step ${idx + 1} থেকে ${target + 1}-এ গেল")
                        }
                        rebuild()
                        true
                    }
                    else -> false
                }
            }
            card.addView(box)
        }
        content.addView(card)
    }

    /** Category: নতুন ধাপ যোগ - grouped by kind. */
    private fun buildAddSteps(script: Script) {
        val card = Ui.card(this)
        card.addView(Ui.primaryButton(this, if (addStepsOpen) "− ধাপ যোগ" else "+ ধাপ যোগ") {
            addStepsOpen = !addStepsOpen; rebuild()
        })
        if (!addStepsOpen) {
            content.addView(card)
            return
        }
        card.addView(Ui.label(this, "পয়েন্ট ও জেসচার"))
        card.addView(btnRow(
            smallBtn("+ Element বেছে") { openPicker(script, "") },
            smallBtn("+ Pinch") {
                val m = resources.displayMetrics
                script.steps.add(Step(
                    StepType.PINCH, m.widthPixels / 2, m.heightPixels / 2,
                    x2 = 400, y2 = 120, durationMs = 400))
                Store.upsert(this, script)
                toast("Pinch step যোগ হলো (স্ক্রিনের মাঝখানে)")
                rebuild()
            },
            smallBtn("+ আঁকা/রেকর্ড") { openRecorder(script) }
        ))
        card.addView(Ui.label(this, "খোঁজা ও অপেক্ষা"))
        card.addView(btnRow(
            smallBtn("+ Text tap") { addSearchStep(script, StepType.TEXT_TAP) },
            smallBtn("+ Wait text") { addSearchStep(script, StepType.WAIT_TEXT) },
            smallBtn("+ Image tap") {
                pendingImageScriptId = script.id
                pendingImageCondition = false
                templatePicker.launch(arrayOf("image/*"))
            }
        ))
        card.addView(btnRow(
            smallBtn("+ রঙ বদল অপেক্ষা") { openPicker(script, "color") },
            smallBtn("+ চেপে ধরা") { openPicker(script, "hold") },
            smallBtn("+ স্ক্রিন স্থির অপেক্ষা") {
                script.steps.add(Step(StepType.WAIT_STABLE, timeoutSec = 20))
                Store.upsert(this, script)
                toast("স্ক্রিন স্থির অপেক্ষা যোগ হলো (চালানোর সময় capture লাগবে)")
                rebuild()
            }
        ))
        card.addView(btnRow(
            smallBtn("+ সংখ্যা টার্গেট") {
                editTextDialog("লেবেল (যে লেখার পাশে সংখ্যা; খালি রাখলে যেকোনো সংখ্যা)", "", numeric = false) { label ->
                    editTextDialog("টার্গেট সংখ্যা", "100", numeric = true) { v ->
                        chooseRegion { region ->
                            script.steps.add(Step(
                                StepType.NUMBER_TARGET, text = label, region = region,
                                x = (v.toIntOrNull() ?: 100), timeoutSec = 30))
                            Store.upsert(this, script)
                            rebuild()
                        }
                    }
                }
            },
            smallBtn("+ রিদম tap") {
                editTextDialog("তালের pattern (একক ব্যবধান, কমা দিয়ে) যেমন 1,1,2,1", "1,1,1,1", numeric = false) { pat ->
                    editTextDialog("এক এককের সময় (ms)", "400", numeric = true) { uv ->
                        val unit = (uv.toLongOrNull() ?: 400).coerceIn(50, 10000)
                        val m = resources.displayMetrics
                        val gaps = pat.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (gaps.isNotEmpty()) {
                            gaps.forEach { g ->
                                script.steps.add(Step(
                                    StepType.TAP, m.widthPixels / 2, m.heightPixels / 2,
                                    durationMs = 50, delayAfterMs = unit * g))
                            }
                            Store.upsert(this, script)
                            toast("রিদমের ${gaps.size}টি tap যোগ হলো (মাঝখানে) - পয়েন্ট বসিয়ে নাও")
                            rebuild()
                        }
                    }
                }
            }
        ))
        card.addView(Ui.label(this, "শর্ত ও লজিক"))
        card.addView(btnRow(
            smallBtn("+ শর্ত (if)") { addSearchStep(script, StepType.CONDITION) },
            smallBtn("+ যদি (IF)") {
                editTextDialog("শর্ত: হয় শব্দ, নয়তো variable হিসাব (যেমন coins>=100)", "", numeric = false) { cond ->
                    if (cond.isNotEmpty()) {
                        editTextDialog("শর্ত সত্য হলে কোন ধাপে যাবে (নম্বর)", "1", numeric = true) { tv ->
                            editTextDialog("মিথ্যা হলে কোন ধাপে যাবে (নম্বর, 0 = পরের ধাপ)", "0", numeric = true) { fv ->
                                val isVar = cond.contains(">") || cond.contains("<") || cond.contains("=")
                                script.steps.add(
                                    if (isVar) Step(StepType.IF, text2 = cond, x = tv.toIntOrNull() ?: 1, x2 = fv.toIntOrNull() ?: 0)
                                    else Step(StepType.IF, text = cond, x = tv.toIntOrNull() ?: 1, x2 = fv.toIntOrNull() ?: 0)
                                )
                                Store.upsert(this, script); rebuild()
                            }
                        }
                    }
                }
            },
            smallBtn("+ Variable") {
                editTextDialog("Variable-এর নাম (যেমন coins)", "", numeric = false) { name ->
                    if (name.isNotEmpty()) {
                        editTextDialog("কোন লেবেলের পাশের সংখ্যা পড়বে (খালি = যেকোনো সংখ্যা)", "", numeric = false) { label ->
                            script.steps.add(Step(StepType.SET_VAR, text = name, text2 = label, timeoutSec = 5))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("+ লাফ (GOTO)") {
                editTextDialog("কোন ধাপে লাফ দেবে (নম্বর)", "1", numeric = true) { v ->
                    script.steps.add(Step(StepType.GOTO, x = (v.toIntOrNull() ?: 1).coerceAtLeast(1)))
                    Store.upsert(this, script); rebuild()
                }
            },
            smallBtn("+ Random লাফ") {
                editTextDialog("কত % সম্ভাবনায় লাফ দেবে", "50", numeric = true) { pv ->
                    editTextDialog("লাফ দিয়ে কোন ধাপে যাবে (নম্বর)", "1", numeric = true) { tv ->
                        script.steps.add(Step(
                            StepType.RANDOM,
                            x = (pv.toIntOrNull() ?: 50).coerceIn(0, 100),
                            x2 = (tv.toIntOrNull() ?: 1).coerceAtLeast(1)))
                        Store.upsert(this, script); rebuild()
                    }
                }
            },
            smallBtn("+ লেবেল") {
                editTextDialog("লেবেলের নাম (GOTO এখানে আসতে পারবে)", "", numeric = false) { v ->
                    if (v.isNotEmpty()) {
                        script.steps.add(Step(StepType.LABEL, text = v))
                        Store.upsert(this, script); rebuild()
                    }
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("+ লুপ শুরু") {
                editTextDialog("ভিতরের stepগুলো কতবার ঘুরবে (0 = অসীম)", "3", numeric = true) { v ->
                    script.steps.add(Step(StepType.LOOP_START, x2 = (v.toIntOrNull() ?: 3).coerceIn(0, 100000)))
                    Store.upsert(this, script)
                    rebuild()
                }
            },
            smallBtn("+ লুপ শেষ") {
                script.steps.add(Step(StepType.LOOP_END))
                Store.upsert(this, script)
                rebuild()
            },
            smallBtn("+ শর্ত-লুপ") {
                editTextDialog("শর্ত: শব্দ অথবা variable হিসাব (যেমন coins<500)", "", numeric = false) { cond ->
                    if (cond.isNotEmpty()) {
                        val isVar = cond.contains(">") || cond.contains("<") || cond.contains("=")
                        script.steps.add(
                            if (isVar) Step(StepType.WHILE_START, text2 = cond)
                            else Step(StepType.WHILE_START, text = cond)
                        )
                        script.steps.add(Step(StepType.LOOP_END))
                        Store.upsert(this, script)
                        toast("শর্ত-লুপ যোগ হলো - মাঝখানে ধাপ বসাও")
                        rebuild()
                    }
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("+ ছবি-শর্ত") {
                pendingImageScriptId = script.id
                pendingImageCondition = true
                templatePicker.launch(arrayOf("image/*"))
            },
            smallBtn("+ রঙ-শর্ত") { openPicker(script, "colorif") },
            smallBtn("+ ফিঙ্গারপ্রিন্ট") {
                val frame = ScreenCaptureService.snapshot()
                if (frame == null) {
                    toast("আগে capture চালু করে একবার script চালাও")
                } else {
                    val w = frame.width; val h = frame.height
                    val pts = listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4)
                    val spec = pts.joinToString(";") { (x, y) ->
                        val c = frame.getPixel(x, y)
                        "$x,$y,#" + String.format("%06X", 0xFFFFFF and c)
                    }
                    script.steps.add(Step(StepType.FINGERPRINT, text = spec, timeoutSec = 5))
                    Store.upsert(this, script)
                    toast("ফিঙ্গারপ্রিন্ট যোগ হলো - ধাপের নিচে সত্য/মিথ্যা ধাপ বসাও")
                    rebuild()
                }
            }
        ))
        card.addView(Ui.label(this, "ডেটা ও সিস্টেম"))
        card.addView(btnRow(
            smallBtn("+ System বাটন") {
                val names = arrayOf("Back", "Home", "Recents")
                AlertDialog.Builder(this)
                    .setTitle("কোন বাটন?")
                    .setItems(names) { _, which ->
                        script.steps.add(Step(StepType.SYSTEM, x = which))
                        Store.upsert(this, script)
                        toast("System step যোগ হলো")
                        rebuild()
                    }
                    .show()
            },
            smallBtn("+ লেখা লিখবে") {
                editTextDialog("যা লিখবে (আগে লেখার ঘরে tap করে cursor বসাতে হবে)", "", numeric = false) { v ->
                    if (v.isNotEmpty()) {
                        script.steps.add(Step(StepType.TYPE_TEXT, text = v))
                        Store.upsert(this, script)
                        rebuild()
                    }
                }
            },
            smallBtn("+ অন্য script") {
                val others = Store.getScripts(this).filter { it.id != script.id }
                if (others.isEmpty()) {
                    toast("আর কোনো script নেই")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("কোন script চালাবে?")
                        .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                            script.steps.add(Step(StepType.RUN_SCRIPT, text = others[which].id))
                            Store.upsert(this, script)
                            rebuild()
                        }
                        .show()
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("+ Key") {
                val names = arrayOf("ভলিউম +", "ভলিউম -", "Media play/pause", "Media next", "Media previous")
                AlertDialog.Builder(this)
                    .setTitle("কোন key?")
                    .setItems(names) { _, which ->
                        script.steps.add(Step(StepType.KEY_EVENT, x = which + 1))
                        Store.upsert(this, script); rebuild()
                    }
                    .show()
            },
            smallBtn("+ Clipboard রাখো") {
                editTextDialog("Clipboard-এ যা রাখবে ({var} চলবে)", "", numeric = false) { v ->
                    if (v.isNotEmpty()) {
                        script.steps.add(Step(StepType.CLIPBOARD_SET, text = v))
                        Store.upsert(this, script); rebuild()
                    }
                }
            },
            smallBtn("+ Clipboard পড়ো") {
                editTextDialog("Clipboard-এর সংখ্যা কোন variable-এ যাবে", "", numeric = false) { v ->
                    if (v.isNotEmpty()) {
                        script.steps.add(Step(StepType.CLIPBOARD_GET, text = v))
                        Store.upsert(this, script); rebuild()
                    }
                }
            }
        ))
        card.addView(btnRow(
            smallBtn("+ ফাইলে লেখো") {
                editTextDialog("ফাইলের নাম (AutoTap ফোল্ডারে .txt জমবে)", "log", numeric = false) { fname ->
                    if (fname.isNotEmpty()) {
                        editTextDialog("প্রতি লাইনে যা লিখবে ({var} চলবে)", "", numeric = false) { line ->
                            script.steps.add(Step(StepType.FILE_WRITE, text = fname, text2 = line))
                            Store.upsert(this, script); rebuild()
                        }
                    }
                }
            },
            smallBtn("+ HTTP ডাক") {
                editTextDialog("URL (https://...)", "", numeric = false) { url ->
                    if (url.isNotEmpty()) {
                        script.steps.add(Step(StepType.HTTP_CALL, text = url, text2 = "GET"))
                        Store.upsert(this, script); rebuild()
                    }
                }
            }
        ))
        card.addView(Ui.infoRow(this, "Text tap অ্যাপের লেখা খোঁজে। Element বেছে নিলে স্ক্রিনের আসল বাটনে আঙুল ছোঁয়ালেই step তৈরি হয়। রঙ/ছবি ধাপে চালানোর সময় screen capture permission চাইবে। লেখা লেখার ধাপে {coins}-এর মতো লিখলে variable-এর মান বসে যাবে।"))
        content.addView(card)
    }

    /** Category: Script পরিচালনা - rename / copy / share / delete. */
    private fun buildScriptManage(script: Script) {
        val card = Ui.card(this)
        card.addView(btnRow(
            smallBtn("নাম") {
                editTextDialog("script-এর নাম", script.name, numeric = false) { name ->
                    if (name.isNotEmpty()) {
                        script.name = name
                        Store.upsert(this, script)
                        rebuild()
                    }
                }
            },
            smallBtn("কপি") {
val merged = Script.fromJson(JSONObject(script.toJson().toString()).apply {
                    put("id", Script.newId()); put("name", script.name + " কপি")
                })
                Store.upsert(this, merged)
                toast("কপি তৈরি হলো")
                rebuild()
            },
            smallBtn("শেয়ার") {
                AlertDialog.Builder(this)
                    .setTitle("শেয়ার")
                    .setItems(arrayOf("Export (JSON ফাইল)", "QR ছবি", "Heatmap দেখো")) { _, which ->
                        when (which) {
                            0 -> {
                                pendingExport = script
                                exportLauncher.launch("${script.name}.json")
                            }
                            1 -> {
                                val text = script.toJson().toString()
                                if (text.length > 1800) {
                                    toast("Script বড় - QR-তে হবে না, Export ব্যবহার করো")
                                } else {
                                    val bmp = QrShare.encode(text)
                                if (bmp == null) {
                                    toast("Scriptটা QR-এর জন্য বড় - ফাইল Export ব্যবহার করো")
                                } else {
                                    val iv = android.widget.ImageView(this)
                                    iv.setImageBitmap(bmp)
                                    iv.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24))
                                    iv.setBackgroundColor(android.graphics.Color.WHITE)
                                    AlertDialog.Builder(this)
                                        .setTitle("QR - ${script.name}")
                                        .setView(iv)
                                        .setPositiveButton("বন্ধ", null)
                                        .show()
                                }
                                }
                            }
                            else -> startActivity(Intent(this, HeatmapActivity::class.java)
                                .putExtra(HeatmapActivity.EXTRA_ID, script.id))
                        }
                    }
                    .show()
            },
            smallBtn("মুছো") {
                AlertDialog.Builder(this)
                    .setTitle("মুছে ফেলবে?")
                    .setMessage("'${script.name}' চিরতরে মুছে যাবে।")
                    .setPositiveButton("মুছো") { _, _ ->
                        ScheduleHelper.disarm(this, script)
                        WatchHelper.cancel(this, script.id)
                        Store.delete(this, script.id)
                        editingScriptId = null
                        toast("মুছে ফেলা হয়েছে")
                        rebuild()
                    }
                    .setNegativeButton("না", null)
                    .show()
            }
        ))
        card.addView(btnRow(
            smallBtn(if (script.favorite) "★ Star সরাও" else "☆ Star দাও") {
                script.favorite = !script.favorite
                Store.upsert(this, script)
                publishShortcuts()
                rebuild()
            },
            smallBtn("হিসাব রিসেট") {
                Store.resetScriptStat(this, script.id)
                toast("হিসাব রিসেট হয়েছে")
                rebuild()
            }
        ))
        content.addView(card)
    }

    private fun windowLabel(script: Script): String {
        if (script.timeStartMin < 0 || script.timeEndMin < 0) return "বন্ধ"
        return "%02d:%02d-%02d:%02d".format(
            script.timeStartMin / 60, script.timeStartMin % 60,
            script.timeEndMin / 60, script.timeEndMin % 60)
    }

    /** v5 environment triggers for one script (WiFi / battery / headset / screen-on / notification). */
    private fun moreTriggers(script: Script) {
        val items = arrayOf(
            "WiFi নাম: ${script.triggerWifi.ifEmpty { "বন্ধ" }}",
            "ব্যাটারি: ${if (script.triggerBatteryLevel >= 0) "${script.triggerBatteryLevel}% ${if (script.triggerBatteryBelow) "নিচে নামলে" else "উপরে উঠলে"}" else "বন্ধ"}",
            "হেডফোন লাগালে: ${if (script.triggerHeadset) "চালু" else "বন্ধ"}",
            "স্ক্রিন অন হলে: ${if (script.triggerScreenOn) "চালু" else "বন্ধ"}",
            "Notification: ${script.triggerNotifPkg.ifEmpty { "বন্ধ" }}"
        )
        AlertDialog.Builder(this)
            .setTitle("আরও ট্রিগার")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editTextDialog("কোন WiFi-তে যুক্ত হলে চলবে (নাম; খালি = বন্ধ)", script.triggerWifi, numeric = false) { v ->
                        script.triggerWifi = v.trim()
                        Store.upsert(this, script)
                        TriggerService.start(this)
                        rebuild()
                    }
                    1 -> editTextDialog("ব্যাটারি % (খালি = বন্ধ)", if (script.triggerBatteryLevel >= 0) script.triggerBatteryLevel.toString() else "", numeric = true) { v ->
                        val lvl = v.toIntOrNull()
                        if (lvl == null) {
                            script.triggerBatteryLevel = -1
                            Store.upsert(this, script); rebuild()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("কখন চলবে?")
                                .setItems(arrayOf("এই % -এর নিচে নামলে", "এই % -এ উঠলে")) { _, dir ->
                                    script.triggerBatteryLevel = lvl.coerceIn(1, 100)
                                    script.triggerBatteryBelow = dir == 0
                                    Store.upsert(this, script)
                                    TriggerService.start(this)
                                    rebuild()
                                }
                                .show()
                        }
                    }
                    2 -> {
                        script.triggerHeadset = !script.triggerHeadset
                        Store.upsert(this, script)
                        TriggerService.start(this)
                        rebuild()
                    }
                    3 -> {
                        script.triggerScreenOn = !script.triggerScreenOn
                        Store.upsert(this, script)
                        TriggerService.start(this)
                        rebuild()
                    }
                    4 -> {
                        val apps = installedApps()
                        AlertDialog.Builder(this)
                            .setTitle("কোন অ্যাপের notification এলে চলবে?")
                            .setItems((listOf("বন্ধ") + apps.map { it.first }).toTypedArray()) { _, sel ->
                                if (sel == 0) {
                                    script.triggerNotifPkg = ""
                                    script.triggerNotifText = ""
                                    Store.upsert(this, script); rebuild()
                                } else {
                                    script.triggerNotifPkg = apps[sel - 1].second
                                    editTextDialog("Notification-এ এই লেখা থাকতে হবে (খালি = যেকোনো)", script.triggerNotifText, numeric = false) { tv ->
                                        script.triggerNotifText = tv
                                        Store.upsert(this, script)
                                        val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
                                        if (!enabled.contains(packageName)) {
                                            toast("Notification access লাগবে - সেটিংসে গিয়ে AutoTap-কে দাও")
                                            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                                        }
                                        rebuild()
                                    }
                                }
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    // ---------- টেমপ্লেট ----------

    private fun buildTemplates() {
        val builderCard = Ui.card(this)
        builderCard.addView(Ui.cardTitle(this, "বাংলায় লিখলেই script"))
        builderCard.addView(Ui.infoRow(this,
            "যা চাও plain ভাষায় লেখো - যেমন: Facebook স্ক্রল করো প্রতি 5 সেকেন্ডে, \"Claim\" লেখা এলে tap করো, 30 মিনিট চলুক। অ্যাপ নিজে step বানিয়ে দেবে (offline, AI সার্ভার লাগে না)।"))
        val input = EditText(this).apply {
            hint = "এখানে লেখো..."
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            minLines = 2
        }
        builderCard.addView(input)
        builderCard.addView(Ui.primaryButton(this, "Script বানাও") {
            val m = resources.displayMetrics
            val built = Templates.fromDescription(input.text.toString(), m.widthPixels, m.heightPixels)
            if (built == null) {
                toast("বুঝতে পারিনি - 'স্ক্রল' বা \"শব্দ\" সহ আরেকটু পরিষ্কার করে লেখো")
            } else {
                editTextDialog("script-এর নাম", "আমার script", numeric = false) { name ->
                    if (name.isNotEmpty()) built.name = name
                    Store.upsert(this, built)
                    Store.setSelectedId(this, built.id)
                    toast("তৈরি! স্ক্রিপ্ট ট্যাবে গিয়ে Start করো")
                    tab = 1
                    recreate()
                }
            }
        })
        content.addView(builderCard)

        val tplCard = Ui.card(this)
        tplCard.addView(Ui.cardTitle(this, "Ready-made টেমপ্লেট"))
        tplCard.addView(Ui.secondary(this, "এক ট্যাপে তৈরি script - পরে চাইলে step বদলে নিতে পারবে।"))
        val m = resources.displayMetrics
        Templates.all(m.widthPixels, m.heightPixels).forEach { tpl ->
            tplCard.addView(Ui.body(this, tpl.name))
            tplCard.addView(Ui.infoRow(this, tpl.desc))
            tplCard.addView(Ui.secondaryButton(this, "এই টেমপ্লেট ব্যবহার করো") {
                val s = tpl.build(m.widthPixels, m.heightPixels)
                Store.upsert(this, s)
                Store.setSelectedId(this, s.id)
                toast("টেমপ্লেট তৈরি: ${s.name}")
                tab = 1
                recreate()
            })
        }
        content.addView(tplCard)
    }

    // ---------- সেটিংস ----------

    private fun buildSettings() {
        if (settingsPage == 0) {
            buildSettingsHub()
            return
        }
        when (settingsPage) {
            1 -> settingsRunGroup()
            2 -> settingsTriggerGroup()
            3 -> settingsSafetyGroup()
            4 -> settingsConnectGroup()
            5 -> settingsAboutGroup()
        }
    }

    /** Settings hub (mock design): five topic cards; a tap opens only that group. */
    private fun buildSettingsHub() {
        fun hubRow(icon: String, title: String, sub: String, page: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(this@MainActivity, Ui.CARD, 16, stroke = true)
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10),
                    Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(this@MainActivity, 10) }
                setOnClickListener { settingsPage = page; rebuild() }
            }
            val ic = android.widget.TextView(this).apply {
                text = icon
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Ui.BRIGHT)
                background = Ui.rounded(this@MainActivity, Ui.RAISED, 999)
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(this@MainActivity, 42), Ui.dp(this@MainActivity, 42))
            }
            row.addView(ic)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(Ui.dp(this@MainActivity, 10), 0, 0, 0)
            }
            col.addView(Ui.body(this, title))
            col.addView(Ui.secondary(this, sub))
            row.addView(col)
            row.addView(android.widget.TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(Ui.MUTED)
            })
            content.addView(row)
        }
        hubRow("▶", "চালানো ও গতি", "Panel ও bubble · Human-like · কাজ-বিরতি · পেজ-শেষ", 1)
        hubRow("⏰", "ট্রিগার ও সময়", "সময়মতো চালু · সাপ্তাহিক · পাহারা · অটো ট্রিগার", 2)
        hubRow("✓", "নিরাপত্তা", "সর্বোচ্চ রান · ব্যাটারি গার্ড · সুরক্ষিত অ্যাপ · PIN · Calibration", 3)
        hubRow("⇄", "বাইরের সংযোগ", "বাইরের API · PC editor · QR · Shizuku · Backup", 4)
        hubRow("ⓘ", "অ্যাপ সম্পর্কে", "সংস্করণ · হিসাব · সীমাবদ্ধতা", 5)
    }

    private fun settingsRunGroup() {
        val panel = Ui.card(this)
        panel.addView(Ui.cardTitle(this, "Panel ও bubble"))
        panel.addView(Ui.settingRow(this, "রূপ: ${if (Store.panelForm(this) == 1) "Bubble" else "বাক্স"}",
            Ui.valueButton(this, "বদলাও") {
                Store.setPanelForm(this, if (Store.panelForm(this) == 1) 0 else 1)
                rebuild()
            }))
        panel.addView(Ui.settingRow(this, "ছোট সাইজ", Ui.toggleSwitch(this, Store.panelSmall(this)) {
            Store.setPanelSmall(this, it)
        }))
        panel.addView(Ui.settingRow(this, "শুরুতে গুটিয়ে রাখা", Ui.toggleSwitch(this, Store.panelCollapsedDefault(this)) {
            Store.setPanelCollapsedDefault(this, it)
        }))
        panel.addView(Ui.settingRow(this, "জায়গা lock", Ui.toggleSwitch(this, Store.panelLocked(this)) {
            Store.setPanelLocked(this, it)
        }))
        panel.addView(Ui.sliderRow(this, "স্বচ্ছতা", 40, 100, Store.panelOpacity(this), { "$it%" }) {
            Store.setPanelOpacity(this, it)
            rebuild()
        })
        panel.addView(Ui.sliderRow(this, "নিজে লুকাবে (সেকেন্ড, 0=না)", 0, 60, Store.panelAutoHideSec(this), { if (it == 0) "না" else "${it}s" }) {
            Store.setPanelAutoHideSec(this, it)
            rebuild()
        })
        panel.addView(Ui.sliderRow(this, "Bubble সাইজ", 40, 96, Store.bubbleSizeDp(this), { "${it}dp" }) {
            Store.setBubbleSizeDp(this, it)
            if (Store.dockEnabled(this)) {
                DockService.stop(this)
                DockService.start(this)
            }
            rebuild()
        })
        content.addView(panel)

        val setupCard = Ui.card(this)
        setupCard.addView(Ui.cardTitle(this, "স্ক্রিন setup"))
        setupCard.addView(Ui.settingRow(this, "Marker আকার: ${arrayOf("ছোট", "মাঝারি", "বড়")[Store.markerSize(this)]}",
            Ui.valueButton(this, "বদলাও") {
                Store.setMarkerSize(this, (Store.markerSize(this) + 1) % 3)
                rebuild()
            }))
        setupCard.addView(Ui.settingRow(this, "Toolbar পাশ: ${arrayOf("মাঝে", "বামে", "ডানে")[Store.toolbarSide(this)]}",
            Ui.valueButton(this, "বদলাও") {
                Store.setToolbarSide(this, (Store.toolbarSide(this) + 1) % 3)
                rebuild()
            }))
        setupCard.addView(Ui.sliderRow(this, "Toolbar স্কেল", 70, 160, (Store.overlayScale(this) * 100).toInt(), { "$it%" }) {
            Store.setOverlayScale(this, it / 100f)
            rebuild()
        })
        setupCard.addView(Ui.settingRow(this, "Grid-এ আটকানো (snap)", Ui.toggleSwitch(this, Store.gridSnap(this)) {
            Store.setGridSnap(this, it)
        }))
        content.addView(setupCard)

        val engine = Ui.card(this)
        engine.addView(Ui.cardTitle(this, "চালানো (উন্নত)"))
        engine.addView(Ui.settingRow(this, "Tap যাচাই + আবার চেষ্টা", Ui.toggleSwitch(this, Store.tapVerify(this)) {
            Store.setTapVerify(this, it)
        }))
        engine.addView(Ui.sliderRow(this, "আবার চেষ্টা (বার)", 0, 5, Store.tapRetry(this), { "$it বার" }) {
            Store.setTapRetry(this, it)
            rebuild()
        })
        engine.addView(Ui.settingRow(this, "আটকে-যাওয়া ধরা (stuck)", Ui.toggleSwitch(this, Store.stuckGuard(this)) {
            Store.setStuckGuard(this, it)
        }))
        engine.addView(Ui.sliderRow(this, "কয় ধাপে আটকে ধরবে", 3, 15, Store.stuckLimit(this), { "$it ধাপ" }) {
            Store.setStuckLimit(this, it)
            rebuild()
        })
        engine.addView(Ui.settingRow(this, "Precision timing", Ui.toggleSwitch(this, Store.precisionTiming(this)) {
            Store.setPrecisionTiming(this, it)
        }))
        engine.addView(Ui.settingRow(this, "ঝাঁকালে stop", Ui.toggleSwitch(this, Store.shakeStop(this)) {
            Store.setShakeStop(this, it)
        }))
        engine.addView(Ui.settingRow(this, "রানের ভিডিও রাখা", Ui.toggleSwitch(this, Store.runVideo(this)) {
            Store.setRunVideo(this, it)
        }))
        engine.addView(Ui.sliderRow(this, "ছবি মেলানোর কঠোরতা", 60, 99, (Store.templateTolerance(this) * 100).toInt(), { "$it%" }) {
            Store.setTemplateTolerance(this, it / 100f)
            rebuild()
        })
        content.addView(engine)

        val feedback = Ui.card(this)
        feedback.addView(Ui.cardTitle(this, "শব্দ, কম্পন, কল"))
        feedback.addView(Ui.settingRow(this, "শব্দ পেলে ছোট শব্দ", Ui.toggleSwitch(this, Store.soundOnFound(this)) {
            Store.setSoundOnFound(this, it)
        }))
        feedback.addView(Ui.settingRow(this, "রান শেষে শব্দ", Ui.toggleSwitch(this, Store.soundOnFinish(this)) {
            Store.setSoundOnFinish(this, it)
        }))
        feedback.addView(Ui.settingRow(this, "রান শেষে কম্পন", Ui.toggleSwitch(this, Store.vibrateOnFinish(this)) {
            Store.setVibrateOnFinish(this, it)
        }))
        feedback.addView(Ui.settingRow(this, "কল এলে নিজে pause", Ui.toggleSwitch(this, Store.callPauseEnabled(this)) {
            Store.setCallPauseEnabled(this, it)
            if (it && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 3)
            }
        }))
        content.addView(feedback)
    }

    private fun settingsTriggerGroup() {
        val trig = Ui.card(this)
        trig.addView(Ui.cardTitle(this, "ট্রিগার ও অটোমেশন"))
        trig.addView(Ui.secondary(this,
            "Trigger সার্ভিস: ${if (TriggerService.instance != null) "চলছে" else "বন্ধ (অ্যাপ খুললেই চালু হয়)"}"))
        trig.addView(Ui.secondaryButton(this, "Notification access দাও") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        trig.addView(Ui.settingRow(this, "বাইরের API (Tasker/intent)", Ui.toggleSwitch(this, Store.apiEnabled(this)) {
            Store.setApiEnabled(this, it)
        }))
        trig.addView(Ui.infoRow(this, "API চালু থাকলে অন্য automation অ্যাপ com.autotap.app.api.START broadcast পাঠিয়ে (extra: id বা name) script চালাতে পারবে, STOP দিয়ে থামাতে পারবে।"))
        content.addView(trig)
    }

    private fun settingsSafetyGroup() {
        val safety = Ui.card(this)
        safety.addView(Ui.cardTitle(this, "সুরক্ষা"))
        safety.addView(Ui.sliderRow(this, "সর্বোচ্চ রান (মিনিট, 0=সীমা নেই)", 0, 240, Store.safetyCapMinutes(this), { if (it == 0) "সীমা নেই" else "$it মিনিট" }) {
            Store.setSafetyCapMinutes(this, it)
            rebuild()
        })
        safety.addView(Ui.settingRow(this, "ব্যাটারি 15%-এর নিচে বন্ধ", Ui.toggleSwitch(this, Store.batteryGuard(this)) {
            Store.setBatteryGuard(this, it)
        }))
        safety.addView(Ui.secondaryButton(this, "সুরক্ষিত অ্যাপ বাছো (${Store.blockedPackages(this).size}টি)") {
            val apps = installedApps()
            val blocked = Store.blockedPackages(this).toMutableSet()
            val checked = BooleanArray(apps.size) { blocked.contains(apps[it].second) }
            AlertDialog.Builder(this)
                .setTitle("যে অ্যাপে script চলবে না")
                .setMultiChoiceItems(apps.map { it.first }.toTypedArray(), checked) { _, which, isChecked ->
                    if (isChecked) blocked.add(apps[which].second) else blocked.remove(apps[which].second)
                }
                .setPositiveButton("সেভ") { _, _ ->
                    Store.setBlockedPackages(this, blocked)
                    rebuild()
                }
                .setNegativeButton("বাতিল", null)
                .show()
        })
        safety.addView(Ui.infoRow(this, "সুরক্ষিত অ্যাপ সামনে থাকলে script নিজে বন্ধ হয়ে যাবে, ভুলেও tap করবে না (যেমন ব্যাংকিং অ্যাপ)।"))
        content.addView(safety)

        val lockCard = Ui.card(this)
        lockCard.addView(Ui.cardTitle(this, "অ্যাপ lock"))
        lockCard.addView(Ui.settingRow(this, "PIN lock: ${if (Store.appPin(this).isEmpty()) "নেই" else "আছে"}",
            Ui.valueButton(this, if (Store.appPin(this).isEmpty()) "PIN বসাও" else "সরাও") {
                if (Store.appPin(this).isEmpty()) {
                    editTextDialog("নতুন PIN (সংখ্যা)", "", numeric = true) { v ->
                        if (v.length >= 4) {
                            Store.setAppPin(this, v)
                            toast("PIN বসানো হলো")
                            rebuild()
                        } else toast("কমপক্ষে 4 সংখ্যার PIN দাও")
                    }
                } else {
                    confirmDialog("PIN সরাবে?", "অ্যাপ lock খুলে যাবে।") {
                        Store.setAppPin(this, "")
                        rebuild()
                    }
                }
            }))
        content.addView(lockCard)

        val calib = Ui.card(this)
        calib.addView(Ui.cardTitle(this, "Calibration"))
        calib.addView(Ui.secondary(this, "এখনকার offset: (${Store.calibDx(this)}, ${Store.calibDy(this)}) px"))
        calib.addView(Ui.secondaryButton(this, "Calibration wizard চালাও") {
            if (!canOverlay()) {
                toast("আগে Overlay permission দাও")
            } else {
                CalibrateService.start(this)
                moveTaskToBack(true)
            }
        })
        calib.addView(Ui.secondaryButton(this, "Offset রিসেট") {
            Store.setCalib(this, 0, 0)
            rebuild()
        })
        content.addView(calib)
    }

    private fun settingsConnectGroup() {
        val shizuku = Ui.card(this)
        shizuku.addView(Ui.cardTitle(this, "Shizuku (উন্নত input)"))
        shizuku.addView(Ui.secondary(this,
            when {
                ShizukuTap.hasPermission() -> "অবস্থা: চালু ও অনুমতি আছে"
                ShizukuTap.binderAlive() -> "অবস্থা: Shizuku চলছে, অনুমতি বাকি"
                else -> "অবস্থা: Shizuku চলছে না (অ্যাপ থেকে চালু করো)"
            }))
        shizuku.addView(Ui.settingRow(this, "Shizuku দিয়ে tap পাঠাও", Ui.toggleSwitch(this, Store.shizukuEnabled(this)) {
            Store.setShizukuEnabled(this, it)
        }))
        shizuku.addView(Ui.secondaryButton(this, "Shizuku অনুমতি চাও") {
            ShizukuTap.requestPermission()
            toast("Shizuku অ্যাপে অনুমতি দাও")
            rebuild()
        })
        content.addView(shizuku)

        val dock = Ui.card(this)
        dock.addView(Ui.cardTitle(this, "Quick dock"))
        dock.addView(Ui.settingRow(this, "Dock bubble", Ui.toggleSwitch(this, Store.dockEnabled(this)) {
            Store.setDockEnabled(this, it)
            if (it) DockService.start(this) else DockService.stop(this)
        }))
        dock.addView(Ui.infoRow(this, "চালু থাকলে স্ক্রিনের ধারে ছোট bubble থাকবে - যেকোনো অ্যাপ থেকে প্রিয় (star) script চালু/বন্ধ করা যাবে। Bubble-এর সাইজ উপরের Panel কার্ডে।"))
        content.addView(dock)

        val pc = Ui.card(this)
        pc.addView(Ui.cardTitle(this, "PC থেকে script সাজাও"))
        val ip = PcEditorService.localIp()
        pc.addView(Ui.secondary(this,
            if (PcEditorService.serverRunning) "চালু আছে - PC ব্রাউজারে খোলো: http://${ip ?: "?"}:${PcEditorService.PORT}/?pin=${Store.pcPin(this)} (একই WiFi থাকতে হবে)"
            else "বন্ধ আছে। চালু করলে একই WiFi-র PC ব্রাউজার থেকে বড় স্ক্রিনে script JSON সম্পাদনা করা যাবে। PIN ছাড়া খুলবে না।"))
        pc.addView(Ui.secondaryButton(this, if (PcEditorService.serverRunning) "PC editor বন্ধ করো" else "PC editor চালু করো") {
            if (PcEditorService.serverRunning) PcEditorService.stop(this) else PcEditorService.start(this)
            rebuild()
        })
        content.addView(pc)

        val backupCard = Ui.card(this)
        backupCard.addView(Ui.cardTitle(this, "Backup (স্বয়ংক্রিয়)"))
        val lastBk = Backup.lastBackupAt(this)
        backupCard.addView(Ui.body(this, if (lastBk > 0)
            "শেষ backup: " + java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(lastBk))
            else "এখনো backup হয়নি"))
        backupCard.addView(Ui.infoRow(this, "প্রতিটা বদলে সব script + ছবির template নিজে থেকে backup হয় (Downloads/AutoTap ফোল্ডারে)। আনইনস্টল করে আবার ইনস্টল করলেও script নিজে থেকে ফিরে আসবে।"))
        backupCard.addView(Ui.secondaryButton(this, "এখনই backup করো") {
            Backup.writeAuto(this)
            toast("Backup হয়ে গেছে")
            rebuild()
        })
        backupCard.addView(btnRow(
            smallBtn("সব backup Export") {
                pendingExportText = Backup.buildBackupJson(this)
                exportTextLauncher.launch("autotap-backup.json")
            },
            smallBtn("Backup Import") { importLauncher.launch(arrayOf("application/json")) }
        ))
        content.addView(backupCard)
    }

    private fun settingsAboutGroup() {
        val about = Ui.card(this)
        about.addView(Ui.cardTitle(this, "সম্পর্কে"))
        about.addView(Ui.body(this, "AutoTap v5.3.0"))
        about.addView(Ui.infoRow(this,
            "কোনো ads নেই, কোনো account নেই, কোনো data বাইরে যায় না। Script বন্ধ থাকলে অ্যাপ কোনো wake lock ধরে না, ব্যাটারিও খায় না - foreground service শুধু script চললেই থাকে।\n\nনোট: এই সংস্করণে OCR নেই - অ্যাপ ছোট রাখতে সরিয়ে দেওয়া হয়েছে; পরে Ultimate সংস্করণে ফিরবে।\n\nসীমাবদ্ধতা: ব্যাংকিং ও secure স্ক্রিনে এবং কিছু গেমের anti-cheat-এ অটো tap কাজ নাও করতে পারে। Trigger সার্ভিস ফোনের battery সেভারে বন্ধ হয়ে গেলে অ্যাপ একবার খুললেই আবার চালু হয়।"))
        content.addView(about)
    }
}
