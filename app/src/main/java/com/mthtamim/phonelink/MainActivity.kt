package com.mthtamim.phonelink

import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : LocalizedActivity() {

    private lateinit var tvMode: TextView
    private lateinit var tvRelay: TextView
    private lateinit var tvDelivery: TextView
    private lateinit var tvQueueInfo: TextView
    private lateinit var tvQuietInfo: TextView
    private lateinit var tvDigestInfo: TextView
    private lateinit var tvRoutineInfo: TextView
    private lateinit var tvSafety: TextView
    private lateinit var tvPeerStatus: TextView
    private lateinit var tvPinInfo: TextView
    private lateinit var tvAdminInfo: TextView
    private lateinit var tvGeoInfo: TextView
    private lateinit var tvRetentionInfo: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvPermNotif: TextView
    private lateinit var tvPermSms: TextView
    private lateinit var tvPermLoc: TextView
    private lateinit var tvPermBattery: TextView
    private lateinit var btnFixPerms: Button
    private lateinit var tvPairCode: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvSenderSeen: TextView
    private lateinit var tvPermSummary: TextView
    private lateinit var lvInbox: ListView
    private lateinit var etSearch: EditText

    private lateinit var pageHome: View
    private lateinit var pageInbox: View
    private lateinit var pageSettings: View
    private lateinit var pageConfig: View
    private var settingsOpen = false
    private lateinit var bottomNav: BottomNavigationView

    private var inboxItems: List<History.Item> = emptyList()
    private var inboxReceiver: BroadcastReceiver? = null
    private var inboxCat: String = "ALL"

    // v2.11 voice commands (system speech recognizer)
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val texts = result.data?.getStringArrayListExtra(
            android.speech.RecognizerIntent.EXTRA_RESULTS)
        val heard = texts?.firstOrNull().orEmpty()
        if (heard.isNotEmpty()) runVoiceCommand(heard)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra("code")?.trim().orEmpty()
            applyPairCode(code)
            // v2.15.0: a scan counts for the qr_scanned trigger no
            // matter which screen launched the scanner.
            if (code.isNotEmpty()) Automation.onEvent(this, "qr_scanned",
                org.json.JSONObject().put("title", code).put("body", code))
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFile(uri)
    }

    private val contactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) sendContactCard(uri)
    }

    private val wallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(getString(R.string.file_uploading))
            thread {
                var ok = false
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        var total = 0
                        var overflow = false
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            total += n
                            if (total > 5 * 1024 * 1024) { overflow = true; break }
                            buf.write(chunk, 0, n)
                        }
                        if (overflow) null else buf.toByteArray()
                    }
                    if (bytes != null) {
                        ok = Relay.postFile(applicationContext, "wallpaper.jpg",
                            bytes, "image/jpeg", wallpaper = true)
                    }
                } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    toast(getString(if (ok) R.string.toast_wallpaper_sent else R.string.file_failed))
                }
            }
        }
    }

    private fun sendContactCard(contactUri: Uri) {
        thread {
            var name = ""
            var number = ""
            try {
                contentResolver.query(contactUri,
                    arrayOf(android.provider.ContactsContract.Contacts._ID,
                        android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getString(0)
                        name = c.getString(1) ?: ""
                        contentResolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(id), null)?.use { p ->
                            if (p.moveToFirst()) number = p.getString(0) ?: ""
                        }
                    }
                }
            } catch (_: Exception) { }
            Handler(Looper.getMainLooper()).post {
                if (number.isNotEmpty()) {
                    Relay.send(this, Relay.TYPE_NOTIF, "👤 $name", number)
                    toast(getString(R.string.toast_contact_sent))
                } else {
                    toast(getString(R.string.toast_contact_failed))
                }
            }
        }
    }

    // ------------- v2.4 launchers -------------
    private val cfgExportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                var ok = false
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(SettingsTransfer.export(applicationContext)
                            .toByteArray(Charsets.UTF_8))
                    }
                    ok = true
                } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    toast(getString(if (ok) R.string.toast_cfg_exported
                        else R.string.toast_cfg_failed))
                }
            }
        }
    }

    private val cfgImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread {
                var ok = false
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.readText().orEmpty()
                    ok = SettingsTransfer.import(applicationContext, text)
                } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    toast(getString(if (ok) R.string.toast_cfg_imported
                        else R.string.toast_cfg_failed))
                    if (ok) recreate()
                }
            }
        }
    }

    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(buildInboxCsv().toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private val pdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        buildInboxPdf().writeTo(out)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private val screenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenShareService::class.java)
                .putExtra("resultCode", result.resultCode)
                .putExtra("data", result.data)
            ContextCompat.startForegroundService(this, intent)
            toast(getString(R.string.toast_screen_session))
        }
    }

    private var pendingBackupUri: Uri? = null

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            pendingBackupUri = uri
            val input = EditText(this).apply {
                hint = getString(R.string.hint_backup_password)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.backup_password_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    writeBackup(input.text.toString())
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun writeBackup(password: String) {
        val uri = pendingBackupUri ?: return
        pendingBackupUri = null
        thread {
            var ok = false
            try {
                var text = History.exportJson(applicationContext)
                if (password.isNotEmpty()) text = BackupCrypto.encrypt(text, password)
                contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                ok = true
            } catch (_: Exception) { }
            Handler(Looper.getMainLooper()).post {
                toast(getString(if (ok) R.string.toast_backup_done else R.string.toast_backup_failed))
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                var text = ""
                try {
                    text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.readText().orEmpty()
                } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    when {
                        text.isEmpty() -> toast(getString(R.string.toast_backup_failed))
                        BackupCrypto.isEncrypted(text) -> {
                            val input = EditText(this).apply {
                                hint = getString(R.string.hint_backup_password)
                                inputType = InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                            }
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.backup_password_title))
                                .setView(input)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val plain = BackupCrypto.decrypt(
                                        text, input.text.toString())
                                    if (plain == null) {
                                        toast(getString(R.string.toast_wrong_password))
                                    } else {
                                        finishRestore(plain)
                                    }
                                }
                                .setNegativeButton(getString(R.string.btn_cancel), null)
                                .show()
                        }
                        else -> finishRestore(text)
                    }
                }
            }
        }
    }

    private fun finishRestore(text: String) {
        thread {
            val count = try { History.importJson(applicationContext, text) }
                catch (_: Exception) { -1 }
            Handler(Looper.getMainLooper()).post {
                if (count >= 0) {
                    refreshInbox()
                    toast(getString(R.string.toast_restore_done, count))
                } else {
                    toast(getString(R.string.toast_backup_failed))
                }
            }
        }
    }

    private fun applyPairCode(code: String) {
        if (code.length < 8) { toast(getString(R.string.toast_code_short)); return }
        Config.setPairCode(this, code)
        refreshPairCode()
        LinkService.restart(this)
        toast(getString(R.string.toast_code_set))
    }

    private fun sendFile(uri: Uri) {
        toast(getString(R.string.file_uploading))
        thread {
            var ok = false
            var name = "file"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: "file"
                }
                val mime = contentResolver.getType(uri) ?: ""
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 60 * 1024 * 1024) throw IllegalStateException("too big")
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: throw IllegalStateException("unreadable")
                // v2.11: OCR — text found in a sent photo rides along
                // as its own inbox item on the other phone.
                if (mime.startsWith("image/")) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size)
                        if (bmp != null) Ocr.scanBitmap(applicationContext, bmp) { text ->
                            Relay.send(applicationContext, Relay.TYPE_INFO,
                                "🔤 ছবির লেখা — $name", text)
                        }
                    } catch (_: Exception) { }
                }
                ok = if (bytes.size > 4_500_000) {
                    Chunks.sendLarge(applicationContext, name, bytes)
                } else {
                    Relay.postFile(applicationContext, name, bytes, mime)
                }
            } catch (_: Exception) { ok = false }
            Handler(Looper.getMainLooper()).post {
                toast(getString(if (ok) R.string.file_sent else R.string.file_failed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Share-to-PhoneLink: another app shared text/a link with us.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotEmpty()) {
                Relay.send(applicationContext, Relay.TYPE_CLIP,
                    getString(R.string.share_title), shared)
                toast(getString(R.string.toast_shared_sent))
            }
            finish()
            return
        }

        // App lock gate.
        if (Config.appLockOn(this) && !SessionState.unlocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }

        // v2.4 simple mode: land on the big-button screen instead
        // (long-press there comes back here with the bypass extra).
        if (Config.simpleMode(this) &&
            intent?.getBooleanExtra("simple_bypass", false) != true) {
            startActivity(Intent(this, SimpleActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        pageHome = findViewById(R.id.pageHome)
        pageInbox = findViewById(R.id.pageInbox)
        pageSettings = findViewById(R.id.pageSettings)
        pageConfig = findViewById(R.id.pageConfig)
        bottomNav = findViewById(R.id.bottomNav)

        tvMode = findViewById(R.id.tvMode)
        tvRelay = findViewById(R.id.tvRelay)
        tvDelivery = findViewById(R.id.tvDelivery)
        tvQueueInfo = findViewById(R.id.tvQueueInfo)
        tvQuietInfo = findViewById(R.id.tvQuietInfo)
        tvDigestInfo = findViewById(R.id.tvDigestInfo)
        tvRoutineInfo = findViewById(R.id.tvRoutineInfo)
        tvSafety = findViewById(R.id.tvSafety)
        tvPeerStatus = findViewById(R.id.tvPeerStatus)
        tvPinInfo = findViewById(R.id.tvPinInfo)
        tvAdminInfo = findViewById(R.id.tvAdminInfo)
        tvGeoInfo = findViewById(R.id.tvGeoInfo)
        tvRetentionInfo = findViewById(R.id.tvRetentionInfo)
        tvStats = findViewById(R.id.tvStats)
        tvPermNotif = findViewById(R.id.tvPermNotif)
        tvPermSms = findViewById(R.id.tvPermSms)
        tvPermLoc = findViewById(R.id.tvPermLoc)
        tvPermBattery = findViewById(R.id.tvPermBattery)
        btnFixPerms = findViewById(R.id.btnFixPerms)
        tvPairCode = findViewById(R.id.tvPairCode)
        tvQueue = findViewById(R.id.tvQueue)
        tvSenderSeen = findViewById(R.id.tvSenderSeen)
        tvPermSummary = findViewById(R.id.tvPermSummary)
        lvInbox = findViewById(R.id.lvInbox)
        etSearch = findViewById(R.id.etSearch)

        val etPairCode = findViewById<EditText>(R.id.etPairCode)
        val etDeviceName = findViewById<EditText>(R.id.etDeviceName)
        val etExcluded = findViewById<EditText>(R.id.etExcluded)
        val etBlocked = findViewById<EditText>(R.id.etBlocked)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etBackupServer = findViewById<EditText>(R.id.etBackupServer)
        val etPriority = findViewById<EditText>(R.id.etPriority)
        val etAlertWords = findViewById<EditText>(R.id.etAlertWords)
        val etAutoReply = findViewById<EditText>(R.id.etAutoReply)
        val etTgToken = findViewById<EditText>(R.id.etTgToken)
        val etTgChat = findViewById<EditText>(R.id.etTgChat)

        etDeviceName.setText(Config.deviceName(this))
        etPriority.setText(Config.priorityRaw(this))
        etAlertWords.setText(Config.alertWordsRaw(this))
        etAutoReply.setText(Config.autoReplyText(this))
        etTgToken.setText(Config.tgToken(this))
        etTgChat.setText(Config.tgChat(this))
        etServer.setText(Config.serverUrl(this))
        etBackupServer.setText(Config.backupServerUrl(this))
        etExcluded.setText(getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE)
            .getString("excluded_apps", ""))
        etBlocked.setText(getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE)
            .getString("blocked_words", ""))
        refreshPairCode()

        // ----- bottom navigation -----
        bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        val startTab = savedInstanceState?.getInt("tab", R.id.navHome) ?: R.id.navHome
        if (startTab != R.id.navHome) bottomNav.selectedItemId = startTab else showPage(R.id.navHome)

        // ----- pairing -----
        findViewById<Button>(R.id.btnApplyCode).setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length < 8) { toast(getString(R.string.toast_code_short)); return@setOnClickListener }
            Config.setPairCode(this, code)
            refreshPairCode()
            LinkService.restart(this)
            toast(getString(R.string.toast_code_set))
        }
        findViewById<Button>(R.id.btnNewCode).setOnClickListener {
            getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE).edit().remove("pair_code").apply()
            // clear stream watermarks so the new topic starts clean
            getSharedPreferences("phonelink_state", Context.MODE_PRIVATE).edit().clear().apply()
            Config.pairCode(this)
            refreshPairCode()
            LinkService.restart(this)
            toast(getString(R.string.toast_new_code))
        }
        findViewById<Button>(R.id.btnSaveName).setOnClickListener {
            Config.setDeviceName(this, etDeviceName.text.toString().ifBlank { Build.MODEL ?: "Phone" })
            toast(getString(R.string.toast_name_saved))
        }
        findViewById<Button>(R.id.btnShowQr).setOnClickListener {
            startActivity(Intent(this, QrActivity::class.java))
        }
        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }

        // ----- modes & service -----
        bindModeSwitch(R.id.swSender, Config.isSender(this)) { Config.setSender(this, it); LinkService.restart(this) }
        bindModeSwitch(R.id.swReceiver, Config.isReceiver(this)) { Config.setReceiver(this, it); LinkService.restart(this) }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            if (!Config.isSender(this) && !Config.isReceiver(this)) {
                toast(getString(R.string.toast_pick_mode)); return@setOnClickListener
            }
            // First time only: if permissions are missing, send him to the
            // wizard before starting. Later starts never nag again.
            if (PermChecks.missingCount(this) > 0 && !Config.wizardShown(this)) {
                Config.setWizardShown(this)
                toast(getString(R.string.toast_perm_needed))
                startActivity(Intent(this, PermissionsActivity::class.java))
                return@setOnClickListener
            }
            LinkService.start(this); refreshStatus(); toast(getString(R.string.toast_service_starting))
        }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            LinkService.stop(this); toast(getString(R.string.toast_service_stopped))
        }

        // ----- permissions entry points -----
        btnFixPerms.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        findViewById<Button>(R.id.btnOpenPerms).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        // ----- feature toggles -----
        bindToggle(R.id.swSms, "fwd_sms", Config.fwdSms(this))
        bindToggle(R.id.swNotif, "fwd_notif", Config.fwdNotif(this))
        bindToggle(R.id.swCalls, "fwd_calls", Config.fwdCalls(this))
        bindToggle(R.id.swBattery, "fwd_battery", Config.fwdBattery(this))
        bindToggle(R.id.swApps, "fwd_apps", Config.fwdApps(this))
        bindToggle(R.id.swSystem, "fwd_system", Config.fwdSystem(this))
        bindToggle(R.id.swLocCmd, "allow_location", Config.allowLocation(this))
        bindToggle(R.id.swRingCmd, "allow_ring", Config.allowRing(this))

        findViewById<Button>(R.id.btnSaveFilters).setOnClickListener {
            Config.setExcludedApps(this, etExcluded.text.toString())
            Config.setBlockedWords(this, etBlocked.text.toString())
            toast(getString(R.string.toast_filters_saved))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Relay.send(this, Relay.TYPE_TEST, getString(R.string.test_title),
                getString(R.string.test_body, Config.deviceName(this), SystemReceiver.batteryPercent(this)))
            toast(getString(R.string.toast_test_sent))
            refreshStatus()
        }
        findViewById<Button>(R.id.btnTgTopics).setOnClickListener { showTgTopicsDialog() }
        findViewById<Button>(R.id.btnSaveServer).setOnClickListener {
            var url = etServer.text.toString().trim()
            if (url.isEmpty()) url = "https://ntfy.sh"
            if (!url.startsWith("http")) url = "https://$url"
            Config.setServerUrl(this, url)
            var backup = etBackupServer.text.toString().trim()
            if (backup.isNotEmpty() && !backup.startsWith("http")) backup = "https://$backup"
            Config.setBackupServerUrl(this, backup)
            Config.setTelegram(this, etTgToken.text.toString(), etTgChat.text.toString())
            LinkService.restart(this)
            toast(getString(R.string.toast_server_saved))
        }

        // ----- remote commands (receiver side) -----
        findViewById<Button>(R.id.btnCmdPing).setOnClickListener { Relay.sendCommand(this, "PING"); toast(getString(R.string.toast_cmd_ping)) }
        findViewById<Button>(R.id.btnCmdBattery).setOnClickListener { Relay.sendCommand(this, "BATTERY"); toast(getString(R.string.toast_cmd_battery)) }
        findViewById<Button>(R.id.btnCmdLocation).setOnClickListener { Relay.sendCommand(this, "LOCATION"); toast(getString(R.string.toast_cmd_location)) }
        findViewById<Button>(R.id.btnCmdRing).setOnClickListener { Relay.sendCommand(this, "RING"); toast(getString(R.string.toast_cmd_ring)) }
        findViewById<Button>(R.id.btnCmdStopRing).setOnClickListener { Relay.sendCommand(this, "STOP_RING"); toast(getString(R.string.toast_cmd_stop_ring)) }
        findViewById<Button>(R.id.btnCmdFlashOn).setOnClickListener { Relay.sendCommand(this, "FLASH_ON"); toast(getString(R.string.toast_cmd_flash_on)) }
        findViewById<Button>(R.id.btnCmdFlashOff).setOnClickListener { Relay.sendCommand(this, "FLASH_OFF"); toast(getString(R.string.toast_cmd_flash_off)) }
        findViewById<Button>(R.id.btnCmdInfo).setOnClickListener { Relay.sendCommand(this, "INFO"); toast(getString(R.string.toast_cmd_info)) }
        findViewById<Button>(R.id.btnCmdScreen).setOnClickListener { showScreenDialog() }

        // ----- quick send -----
        findViewById<Button>(R.id.btnSendClipboard).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                toast(getString(R.string.toast_clip_empty))
            } else {
                Relay.send(this, Relay.TYPE_CLIP, getString(R.string.clip_title), text)
                toast(getString(R.string.toast_clip_sent))
            }
        }
        findViewById<Button>(R.id.btnSendFile).setOnClickListener {
            fileLauncher.launch(arrayOf("*/*"))
        }

        // ----- inbox -----
        findViewById<Button>(R.id.btnRefreshInbox).setOnClickListener { refreshInbox() }
        findViewById<Button>(R.id.btnClearInbox).setOnClickListener {
            History.clear(this); refreshInbox(); toast(getString(R.string.toast_inbox_cleared))
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderInbox() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        lvInbox.setOnItemClickListener { _, _, position, _ ->
            val item = filteredInbox().getOrNull(position) ?: return@setOnItemClickListener
            when {
                item.code.isNotEmpty() -> copyToClipboard(item.code)
                item.type == Relay.TYPE_CALL && item.extra.startsWith("tel:") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL,
                            Uri.parse("tel:" + item.extra.removePrefix("tel:"))))
                    } catch (_: Exception) { copyToClipboard(item.body) }
                }
                item.type == Relay.TYPE_FILE && item.extra.isNotEmpty() -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.extra)))
                    } catch (_: Exception) { copyToClipboard(item.extra) }
                }
                else -> copyToClipboard("${item.title}\n${item.body}")
            }
        }
        lvInbox.setOnItemLongClickListener { _, _, position, _ ->
            val item = filteredInbox().getOrNull(position) ?: return@setOnItemLongClickListener false
            showItemMenu(item)
            true
        }
        findViewById<ChipGroup>(R.id.cgCats).setOnCheckedStateChangeListener { _, ids ->
            inboxCat = when (ids.firstOrNull()) {
                R.id.chipSms -> "SMS"
                R.id.chipCalls -> "CALL"
                R.id.chipNotif -> "NOTIF"
                R.id.chipBattery -> "BATTERY"
                R.id.chipOther -> "OTHER"
                R.id.chipStar -> "STAR"
                R.id.chipArchived -> "ARCHIVED"
                else -> "ALL"
            }
            renderInbox()
        }
        findViewById<Button>(R.id.btnExportInbox).setOnClickListener {
            val items = filteredInbox()
            if (items.isEmpty()) { toast(getString(R.string.inbox_empty)); return@setOnClickListener }
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            val text = items.joinToString("\n\n") {
                "[${it.type}] ${it.title}\n${it.body}\n— ${it.from} • ${fmt.format(Date(it.time))}"
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(share, getString(R.string.btn_export_inbox)))
        }

        // ----- v2 toggles: network / security / flash / encryption -----
        bindToggle(R.id.swNetwork, "fwd_network", Config.fwdNetwork(this))
        bindToggle(R.id.swSecurity, "fwd_security", Config.fwdSecurity(this))
        bindToggle(R.id.swFlashCmd, "allow_flash", Config.allowFlash(this))
        bindToggle(R.id.swEncryption, "e2ee_on", Config.encryptionOn(this))

        // ----- app lock -----
        val swAppLock = findViewById<CompoundButton>(R.id.swAppLock)
        swAppLock.isChecked = Config.appLockOn(this)
        swAppLock.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Config.pinHash(this).isEmpty()) {
                    showSetPinDialog { ok -> if (!ok) swAppLock.isChecked = false }
                } else {
                    Config.setAppLockOn(this, true)
                }
            } else {
                Config.setAppLockOn(this, false)
            }
        }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener {
            showSetPinDialog { }
        }

        // ----- offline queue management -----
        findViewById<Button>(R.id.btnFlushQueue).setOnClickListener {
            thread {
                Relay.flushQueue(applicationContext)
                runOnUiThread { refreshStatus(); toast(getString(R.string.toast_queue_flushed)) }
            }
        }
        findViewById<Button>(R.id.btnClearQueue).setOnClickListener {
            Relay.clearQueue(this); refreshStatus(); toast(getString(R.string.toast_queue_cleared))
        }
        findViewById<Button>(R.id.btnViewQueue).setOnClickListener {
            val items = Relay.queueItems(this)
            if (items.isEmpty()) { toast(getString(R.string.queue_info, 0)); return@setOnClickListener }
            val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val labels = items.map {
                "${it.optString("type")} — ${it.optString("title")} (${fmt.format(Date(it.optLong("time")))})"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sec_queue))
                .setItems(labels, null)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // ----- smart control (v2.1) -----
        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        val swQuiet = findViewById<CompoundButton>(R.id.swQuiet)
        swQuiet.isChecked = Config.quietOn(this)
        swQuiet.setOnCheckedChangeListener { _, checked ->
            Config.setToggle(this, "quiet_on", checked); refreshStatus()
        }
        findViewById<Button>(R.id.btnQuietTimes).setOnClickListener {
            val start = Config.quietStart(this)
            TimePickerDialog(this, { _, sh, sm ->
                val end = Config.quietEnd(this)
                TimePickerDialog(this, { _, eh, em ->
                    Config.setQuietWindow(this, sh * 60 + sm, eh * 60 + em)
                    refreshStatus()
                }, end / 60, end % 60, true).show()
            }, start / 60, start % 60, true).show()
        }
        findViewById<Button>(R.id.btnDigest).setOnClickListener {
            val options = intArrayOf(0, 30, 60, 120, 240)
            val labels = options.map {
                if (it == 0) getString(R.string.digest_off)
                else getString(R.string.digest_every, it)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.digest_label))
                .setItems(labels) { _, which ->
                    Config.setDigestMinutes(this, options[which]); refreshStatus()
                }
                .show()
        }
        findViewById<Button>(R.id.btnSaveSmart).setOnClickListener {
            Config.setPriorityRaw(this, etPriority.text.toString())
            Config.setAlertWordsRaw(this, etAlertWords.text.toString())
            Config.setAutoReplyText(this, etAutoReply.text.toString())
            toast(getString(R.string.toast_filters_saved))
        }
        bindToggle(R.id.swSmsTrigger, "sms_trigger", Config.smsTriggerOn(this))

        // ----- more features (v2.1) -----
        bindToggle(R.id.swTts, "tts_codes", Config.ttsCodes(this))
        bindToggle(R.id.swSmsReply, "allow_sms_reply", Config.allowSmsReply(this))
        bindToggle(R.id.swSnap, "allow_snap", Config.allowSnap(this))
        bindToggle(R.id.swGeo, "geo_on", Config.geoOn(this))

        // ----- v2.2 toggles & actions -----
        bindToggle(R.id.swPresence, "fwd_presence", Config.fwdPresence(this))
        bindToggle(R.id.swMedia, "fwd_media", Config.fwdMedia(this))
        bindToggle(R.id.swTtsCalls, "tts_calls", Config.ttsCalls(this))
        bindToggle(R.id.swAutoReply, "auto_reply_on", Config.autoReplyOn(this))
        bindToggle(R.id.swLowLoc, "low_loc_on", Config.lowLocOn(this))
        val swRoutine = findViewById<CompoundButton>(R.id.swRoutine)
        swRoutine.isChecked = Config.routineOn(this)
        swRoutine.setOnCheckedChangeListener { _, checked ->
            Config.setToggle(this, "routine_on", checked); refreshStatus()
        }
        findViewById<Button>(R.id.btnRoutineTime).setOnClickListener {
            val cur = Config.routineMinutes(this)
            TimePickerDialog(this, { _, h, m ->
                Config.setRoutineMinutes(this, h * 60 + m); refreshStatus()
            }, cur / 60, cur % 60, true).show()
        }
        findViewById<Button>(R.id.btnVoice).setOnClickListener {
            startActivity(Intent(this, VoiceActivity::class.java))
        }
        findViewById<Button>(R.id.btnCmdStatus).setOnClickListener {
            Relay.sendCommand(this, "STATUS"); toast(getString(R.string.toast_cmd_status))
        }
        findViewById<Button>(R.id.btnProfiles).setOnClickListener { showProfilesDialog() }
        findViewById<Button>(R.id.btnSetGeo).setOnClickListener {
            toast(getString(R.string.toast_geo_fetching))
            LocationHelper.fetchCoords(this,
                { lat, lng ->
                    Config.setGeoCenter(this, lat, lng)
                    runOnUiThread { refreshStatus(); toast(getString(R.string.toast_geo_set)) }
                },
                { runOnUiThread { toast(getString(R.string.toast_geo_failed)) } })
        }
        bindToggle(R.id.swDailySummary, "daily_summary_on", Config.dailySummaryOn(this))
        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { toast(getString(R.string.toast_no_usage_settings)) }
        }

        // ----- data & statistics (v2.1) -----
        findViewById<Button>(R.id.btnResetStats).setOnClickListener {
            Stats.reset(this); refreshStatus(); toast(getString(R.string.toast_stats_reset))
        }
        findViewById<Button>(R.id.btnRetention).setOnClickListener {
            val options = intArrayOf(0, 7, 30)
            val labels = options.map {
                if (it == 0) getString(R.string.retention_forever)
                else getString(R.string.retention_days, it)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_retention))
                .setItems(labels) { _, which ->
                    Config.setInboxKeepDays(this, options[which]); refreshStatus()
                }
                .show()
        }
        findViewById<Button>(R.id.btnBackupInbox).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "phonelink-inbox-backup.json")
            }
            backupLauncher.launch(intent)
        }
        findViewById<Button>(R.id.btnRestoreInbox).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            restoreLauncher.launch(intent)
        }

        // ----- devices, SOS, new commands (v2.1) -----
        findViewById<Button>(R.id.btnDevices).setOnClickListener { showDevicesDialog() }
        findViewById<Button>(R.id.btnSos).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.sos_title))
                .setMessage(getString(R.string.sos_confirm))
                .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                    Relay.send(this, Relay.TYPE_SYSTEM, "🆘 SOS — সাহায্য দরকার",
                        "${Config.deviceName(this)} থেকে জরুরি SOS পাঠানো হয়েছে।")
                    LocationHelper.fetchAndSend(this)
                    // v2.11: external emergency numbers get a plain SMS too
                    val sosNums = Config.sosNumbers(this)
                    if (sosNums.isNotEmpty() && checkSelfPermission(
                            android.Manifest.permission.SEND_SMS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val name = Config.deviceName(this)
                        LocationHelper.fetchCoords(this, { lat, lng ->
                            sendSosSms(sosNums,
                                "🆘 SOS: $name সাহায্য চাইছে। লোকেশন: " +
                                    "https://maps.google.com/?q=$lat,$lng")
                        }, {
                            sendSosSms(sosNums, "🆘 SOS: $name সাহায্য চাইছে।")
                        })
                    }
                    toast(getString(R.string.toast_sos_sent))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        findViewById<Button>(R.id.btnCmdSnap).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "SNAP", null,
                    org.json.JSONObject().put("pin", digest))
                toast(getString(R.string.toast_cmd_snap))
            }
        }
        // ----- v2.11 remote additions -----
        findViewById<Button>(R.id.btnCmdLs).setOnClickListener {
            showTextPrompt(getString(R.string.cmd_ls), "/sdcard/Download") { path ->
                Relay.sendCommand(this, "LS", path)
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnCmdGetFile).setOnClickListener {
            askPin { digest ->
                showTextPrompt(getString(R.string.cmd_getfile), "") { path ->
                    Relay.sendCommand(this, "GETFILE", null,
                        org.json.JSONObject().put("pin", digest).put("arg", path))
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
        }
        findViewById<Button>(R.id.btnCmdCallLog).setOnClickListener {
            Relay.sendCommand(this, "CALLLOG"); toast(getString(R.string.toast_cmd_sent))
        }
        findViewById<Button>(R.id.btnCmdContacts).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "CONTACTS", null,
                    org.json.JSONObject().put("pin", digest))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnCmdUsage).setOnClickListener {
            Relay.sendCommand(this, "USAGE"); toast(getString(R.string.toast_cmd_sent))
        }
        findViewById<Button>(R.id.btnCmdStorage).setOnClickListener {
            Relay.sendCommand(this, "STORAGE"); toast(getString(R.string.toast_cmd_sent))
        }
        findViewById<Button>(R.id.btnCmdNetTest).setOnClickListener {
            Relay.sendCommand(this, "NETTEST"); toast(getString(R.string.toast_cmd_sent))
        }
        findViewById<Button>(R.id.btnCmdRestartSvc).setOnClickListener {
            Relay.sendCommand(this, "RESTART"); toast(getString(R.string.toast_cmd_sent))
        }
        findViewById<Button>(R.id.btnCmdListen).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "LISTEN", null,
                    org.json.JSONObject().put("pin", digest).put("minutes", 3))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnCmdListen).setOnLongClickListener {
            Relay.sendCommand(this, "STOPLISTEN")
            toast(getString(R.string.toast_cmd_sent)); true
        }
        findViewById<Button>(R.id.btnCmdCamPrev).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "CAMPREV", null,
                    org.json.JSONObject().put("pin", digest).put("count", 4))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnCmdLiveView).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "LIVEVIEW", null,
                    org.json.JSONObject().put("pin", digest))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnCmdLiveView).setOnLongClickListener {
            Relay.sendCommand(this, "STOPLIVE")
            toast(getString(R.string.toast_cmd_sent)); true
        }
        findViewById<Button>(R.id.btnCmdUrl).setOnClickListener {
            showTextPrompt(getString(R.string.cmd_url), "https://") { url ->
                Relay.sendCommand(this, "OPENURL", url)
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnFocusGuard).setOnClickListener { showFocusDialog() }
        findViewById<Button>(R.id.btnBroadcast).setOnClickListener {
            showTextPrompt(getString(R.string.btn_broadcast), "") { text ->
                Relay.send(this, Relay.TYPE_INFO,
                    "📢 " + getString(R.string.btn_broadcast) + " — " +
                        Config.deviceName(this), text)
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnPtt).setOnClickListener {
            startActivity(Intent(this, VoiceActivity::class.java).putExtra("ptt", true))
        }
        findViewById<Button>(R.id.btnVoiceCmd).setOnClickListener {
            try {
                val ri = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
                        getString(R.string.voicecmd_prompt))
                speechLauncher.launch(ri)
            } catch (_: Exception) { toast(getString(R.string.voicecmd_unavailable)) }
        }
        findViewById<Button>(R.id.btnExpenses).setOnClickListener {
            startActivity(Intent(this, ExpensesActivity::class.java))
        }
        bindToggle(R.id.swAutoBackup, "auto_backup_on", Config.autoBackupOn(this))
        findViewById<Button>(R.id.btnAccent).setOnClickListener {
            val names = arrayOf("emerald", "blue", "purple", "amber", "rose")
            val labels = arrayOf(
                getString(R.string.accent_emerald), getString(R.string.accent_blue),
                getString(R.string.accent_purple), getString(R.string.accent_amber),
                getString(R.string.accent_rose))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_accent))
                .setItems(labels) { _, w ->
                    Config.setAccent(this, names[w]); recreate()
                }
                .show()
        }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            toast(getString(R.string.update_checking))
            Update.check(this, true) { newer, url ->
                if (newer != null) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_found, newer))
                        .setMessage(getString(R.string.update_found_msg))
                        .setPositiveButton(getString(R.string.btn_open)) { _, _ ->
                            if (!url.isNullOrEmpty()) try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) { }
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else toast(getString(R.string.update_latest))
            }
        }
        val swWeb = findViewById<CompoundButton>(R.id.swWebInbox)
        swWeb.isChecked = Config.webInboxOn(this)
        swWeb.setOnCheckedChangeListener { _, checked ->
            Config.setToggle(this, "web_inbox_on", checked)
            if (checked && Config.webPin(this).length < 4) {
                toast(getString(R.string.web_pin_needed))
            }
            WebInbox.restart(this)
        }
        findViewById<Button>(R.id.btnWebPin).setOnClickListener {
            showTextPrompt(getString(R.string.btn_web_pin),
                Config.webPin(this)) { pin ->
                if (pin.length >= 4) {
                    Config.setWebPin(this, pin)
                    WebInbox.restart(this)
                    toast(getString(R.string.web_url, ownIp(), WebInbox.PORT, pin))
                } else toast(getString(R.string.toast_pin_short))
            }
        }
        findViewById<Button>(R.id.btnPanicPin).setOnClickListener {
            showTextPrompt(getString(R.string.btn_panic_pin), "") { pin ->
                if (pin.length >= 4) {
                    Config.setPanicPinHash(this, Crypto.pinHash(pin))
                    toast(getString(R.string.toast_saved))
                } else toast(getString(R.string.toast_pin_short))
            }
        }
        findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etSosNumbers).setText(Config.sosNumbers(this).joinToString(", "))
        findViewById<Button>(R.id.btnSaveSos).setOnClickListener {
            val v = findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.etSosNumbers).text.toString()
            Config.setSosNumbers(this, v)
            toast(getString(R.string.toast_saved))
        }
        // v2.11: once-a-day silent update check
        if (System.currentTimeMillis() - Config.updateLastCheck(this) >
            24 * 3600_000L) Update.check(this, false)
        findViewById<Button>(R.id.btnCmdSummary).setOnClickListener {
            Relay.sendCommand(this, "SUMMARY"); toast(getString(R.string.toast_cmd_summary))
        }
        wireV24()

        // ----- v2.3 wiring -----
        findViewById<Button>(R.id.btnCmdLock).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "LOCK", null,
                    org.json.JSONObject().put("pin", digest))
                toast(getString(R.string.toast_cmd_lock))
            }
        }
        findViewById<Button>(R.id.btnCmdRemote).setOnClickListener { showRemotePanel() }
        findViewById<Button>(R.id.btnMode).setOnClickListener { showModeDialog() }
        findViewById<Button>(R.id.btnCmdSpeak).setOnClickListener { showSpeakDialog() }
        findViewById<Button>(R.id.btnCmdAlarm).setOnClickListener { showAlarmDialog() }
        findViewById<Button>(R.id.btnCmdRinger).setOnClickListener { showRingerDialog() }
        findViewById<Button>(R.id.btnCmdGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<Button>(R.id.btnCmdRecentCalls).setOnClickListener {
            Relay.sendCommand(this, "LIST_RECENT", null,
                org.json.JSONObject().put("kind", "calls"))
            toast(getString(R.string.toast_list_sent))
        }
        findViewById<Button>(R.id.btnCmdRecentSms).setOnClickListener {
            Relay.sendCommand(this, "LIST_RECENT", null,
                org.json.JSONObject().put("kind", "sms"))
            toast(getString(R.string.toast_list_sent))
        }
        findViewById<Button>(R.id.btnCmdNote).setOnClickListener { showNoteDialog() }
        findViewById<Button>(R.id.btnMediaPlay).setOnClickListener {
            Relay.sendCommand(this, "MEDIA", null, org.json.JSONObject().put("action", "playpause"))
        }
        findViewById<Button>(R.id.btnMediaNext).setOnClickListener {
            Relay.sendCommand(this, "MEDIA", null, org.json.JSONObject().put("action", "next"))
        }
        findViewById<Button>(R.id.btnMediaVolUp).setOnClickListener {
            Relay.sendCommand(this, "MEDIA", null, org.json.JSONObject().put("action", "volup"))
        }
        findViewById<Button>(R.id.btnMediaVolDown).setOnClickListener {
            Relay.sendCommand(this, "MEDIA", null, org.json.JSONObject().put("action", "voldown"))
        }
        findViewById<Button>(R.id.btnSiren).setOnClickListener { showSirenDialog() }
        findViewById<Button>(R.id.btnSchedule).setOnClickListener { showScheduleDialog() }
        findViewById<Button>(R.id.btnTemplates).setOnClickListener { showTemplatesDialog() }
        findViewById<Button>(R.id.btnContactCard).setOnClickListener {
            contactLauncher.launch(null)
        }
        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            wallpaperLauncher.launch(arrayOf("image/*"))
        }
        findViewById<EditText>(R.id.etMyStatus).setText(Config.myStatus(this))
        findViewById<Button>(R.id.btnSaveStatus).setOnClickListener {
            Config.setMyStatus(this, findViewById<EditText>(R.id.etMyStatus).text.toString())
            toast(getString(R.string.toast_status_saved))
        }

        // ----- security card -----
        bindToggle(R.id.swGuard, "guard_on", Config.guardOn(this))
        bindToggle(R.id.swIntruder, "intruder_on", Config.intruderOn(this))
        // v2.11.1: hide the launcher icon (with a safety briefing first)
        val swHide = findViewById<CompoundButton>(R.id.swHideIcon)
        swHide.isChecked = Config.iconHidden(this)
        swHide.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.hide_title))
                    .setMessage(getString(R.string.hide_msg))
                    .setPositiveButton(getString(R.string.hide_yes)) { _, _ ->
                        Config.setIconHidden(this, true)
                        toast(getString(R.string.toast_hidden))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        swHide.isChecked = false
                    }
                    .setOnCancelListener { swHide.isChecked = false }
                    .show()
            } else {
                Config.setIconHidden(this, false)
                toast(getString(R.string.toast_shown))
            }
        }
        bindToggle(R.id.swAutosave, "autosave_media", Config.autosaveMedia(this))
        findViewById<Button>(R.id.btnSetPin).setOnClickListener { showSetPinDialog() }
        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            if (AdminReceiver.isActive(this)) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) { }
                toast(getString(R.string.admin_deactivate_hint))
            } else {
                val comp = android.content.ComponentName(this, AdminReceiver::class.java)
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_explanation))
                try { startActivity(intent) } catch (_: Exception) {
                    toast(getString(R.string.toast_admin_failed))
                }
            }
        }
        findViewById<Button>(R.id.btnSecLog).setOnClickListener { showSecLogDialog() }
        findViewById<Button>(R.id.btnDataLimit).setOnClickListener {
            val options = intArrayOf(0, 500, 1024, 2048, 5120)
            val labels = options.map {
                if (it == 0) getString(R.string.digest_off)
                else getString(R.string.data_limit_mb, it)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.data_limit_title))
                .setItems(labels) { _, which ->
                    Config.setDataLimitMb(this, options[which])
                    toast(getString(R.string.toast_filters_saved))
                }
                .show()
        }

        // ----- appearance: language & theme -----
        val toggleLang = findViewById<MaterialButtonToggleGroup>(R.id.toggleLang)
        toggleLang.check(if (Config.language(this) == "en") R.id.btnLangEn else R.id.btnLangBn)
        toggleLang.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = if (checkedId == R.id.btnLangEn) "en" else "bn"
            if (target != Config.language(this)) {
                Config.setLanguage(this, target)
                recreate()
            }
        }
        val toggleTheme = findViewById<MaterialButtonToggleGroup>(R.id.toggleTheme)
        toggleTheme.check(
            when (Config.themeMode(this)) {
                "light" -> R.id.btnThemeLight
                "dark" -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
        )
        toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = when (checkedId) {
                R.id.btnThemeLight -> "light"
                R.id.btnThemeDark -> "dark"
                else -> "system"
            }
            if (target != Config.themeMode(this)) {
                Config.setThemeMode(this, target)
                AppCompatDelegate.setDefaultNightMode(
                    when (target) {
                        "light" -> AppCompatDelegate.MODE_NIGHT_NO
                        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
        }

        refreshStatus()
        refreshInbox()

        // First launch ever: if anything is missing, open the permission
        // wizard once. The flag is set when shown, so it never nags again.
        if (!Config.wizardShown(this) && PermChecks.missingCount(this) > 0) {
            Config.setWizardShown(this)
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", bottomNav.selectedItemId)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshInbox()
        Automation.checkClipboard(this)
        // v2.4 NFC tag trigger (foreground dispatch).
        try {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
            if (adapter != null && adapter.isEnabled) {
                val pi = android.app.PendingIntent.getActivity(this, 0,
                    Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                adapter.enableForegroundDispatch(this, pi, null, null)
            }
        } catch (_: Exception) { }
        // v2.4 clipboard auto-sync: a new clip while the app is open
        // is forwarded to the other phone right away.
        if (Config.clipSyncOn(this)) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)
                    ?.toString().orEmpty()
                val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
                if (text.isNotEmpty() &&
                    text != prefs.getString("last_clip_sent", "")) {
                    prefs.edit().putString("last_clip_sent", text).apply()
                    Relay.send(this, Relay.TYPE_CLIP,
                        getString(R.string.clip_title_auto), text)
                }
            } catch (_: Exception) { }
        }
        if (pageInbox.visibility == View.VISIBLE) markInboxSeen()
        inboxReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refreshInbox(); refreshStatus() }
        }
        val filter = IntentFilter(LinkService.ACTION_NEW_ITEM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(inboxReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(inboxReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            android.nfc.NfcAdapter.getDefaultAdapter(this)
                ?.disableForegroundDispatch(this)
        } catch (_: Exception) { }
        inboxReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        inboxReceiver = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED,
            android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED,
            android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                Automation.onEvent(this, "nfc_tag", org.json.JSONObject())
                toast(getString(R.string.toast_nfc))
            }
        }
    }

    // ------------- v2.4 wiring -------------

    // ------------- v2.4.1 sub-screens -------------
    // Everything that used to pile onto Home/Settings now opens as a
    // full-screen sub-screen (overlay) with its own title and back.

    private val subGroups = listOf(
        R.id.groupRemote, R.id.groupQuick,
        R.id.panelGeneral, R.id.panelForward, R.id.panelSmart,
        R.id.panelSecurity, R.id.panelRelay, R.id.panelData,
        R.id.panelGroup, R.id.panelFamily
    )

    private fun openSubScreen(groupId: Int, titleRes: Int) {
        findViewById<View>(R.id.subRoot).visibility = View.VISIBLE
        findViewById<View>(R.id.bottomNav).visibility = View.GONE
        findViewById<View>(R.id.toolbar).visibility = View.GONE
        findViewById<TextView>(R.id.tvSubTitle).setText(titleRes)
        for (id in subGroups) {
            findViewById<View>(id).visibility =
                if (id == groupId) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.subScroll).scrollTo(0, 0)
    }

    private fun subScreenOpen(): Boolean =
        findViewById<View>(R.id.subRoot).visibility == View.VISIBLE

    private fun closeSubScreen() {
        findViewById<View>(R.id.subRoot).visibility = View.GONE
        if (!settingsOpen) {
            findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
            findViewById<View>(R.id.toolbar).visibility = View.VISIBLE
        }
    }

    private fun openSettings() {
        settingsOpen = true
        findViewById<View>(R.id.settingsRoot).visibility = View.VISIBLE
        findViewById<View>(R.id.toolbar).visibility = View.GONE
        findViewById<View>(R.id.bottomNav).visibility = View.GONE
    }

    private fun closeSettings() {
        settingsOpen = false
        findViewById<View>(R.id.settingsRoot).visibility = View.GONE
        findViewById<View>(R.id.toolbar).visibility = View.VISIBLE
        findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
    }

    private fun doCheckin() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        Config.setLastCheckinDay(this, today)
        Relay.send(this, Relay.TYPE_INFO, "✅ চেক-ইন",
            "${Config.deviceName(this)} — " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date()))
        LocationHelper.fetchAndSend(this)
        toast(getString(R.string.toast_checkin))
    }

    private fun buildInboxCsv(): String {
        val sb = StringBuilder("Time,Type,From,Title,Body\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"")
            .replace("\n", " ") + "\""
        for (item in History.all(this)) {
            sb.append(esc(fmt.format(java.util.Date(item.time)))).append(',')
                .append(esc(item.type)).append(',').append(esc(item.from)).append(',')
                .append(esc(item.title)).append(',').append(esc(item.body)).append('\n')
        }
        return sb.toString()
    }

    private fun buildInboxPdf(): java.io.ByteArrayOutputStream {
        val doc = android.graphics.pdf.PdfDocument()
        val paint = android.graphics.Paint().apply { textSize = 11f }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val items = History.all(this)
        var pageNum = 1
        var idx = 0
        val perPage = 42
        while (true) {
            val info = android.graphics.pdf.PdfDocument.PageInfo
                .Builder(595, 842, pageNum).create()
            val page = doc.startPage(info)
            var y = 40f
            val end = minOf(idx + perPage, items.size)
            while (idx < end) {
                val it2 = items[idx]
                val line = ("${fmt.format(java.util.Date(it2.time))} " +
                    "[${it2.type}] ${it2.title} — ${it2.body}").take(100)
                page.canvas.drawText(line, 24f, y, paint)
                y += 19f
                idx++
            }
            doc.finishPage(page)
            pageNum++
            if (idx >= items.size) break
        }
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out
    }

    private fun wireV24() {
        // --- sub-screen navigation (v2.4.1) ---
        findViewById<View>(R.id.btnSubBack).setOnClickListener { closeSubScreen() }
        findViewById<View>(R.id.btnSettingsTop).setOnClickListener { openSettings() }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { closeSettings() }
        findViewById<Button>(R.id.menuGeneral).setOnClickListener {
            openSubScreen(R.id.panelGeneral, R.string.cat_general) }
        findViewById<Button>(R.id.menuForward).setOnClickListener {
            openSubScreen(R.id.panelForward, R.string.cat_forward) }
        findViewById<Button>(R.id.menuSmart).setOnClickListener {
            openSubScreen(R.id.panelSmart, R.string.cat_smart) }
        findViewById<Button>(R.id.menuSecurity).setOnClickListener {
            openSubScreen(R.id.panelSecurity, R.string.cat_security) }
        findViewById<Button>(R.id.menuRelay).setOnClickListener {
            openSubScreen(R.id.panelRelay, R.string.cat_relay) }
        findViewById<Button>(R.id.menuData).setOnClickListener {
            openSubScreen(R.id.panelData, R.string.cat_data) }
        findViewById<Button>(R.id.menuGroup).setOnClickListener {
            openSubScreen(R.id.panelGroup, R.string.cat_group) }
        findViewById<Button>(R.id.menuFamily).setOnClickListener {
            openSubScreen(R.id.panelFamily, R.string.cat_family) }
        findViewById<Button>(R.id.menuAutomation).setOnClickListener {
            startActivity(Intent(this, AutomationActivity::class.java)) }
        findViewById<View>(R.id.boxRemote).setOnClickListener {
            openSubScreen(R.id.groupRemote, R.string.btn_menu_remote) }
        findViewById<View>(R.id.boxQuick).setOnClickListener {
            openSubScreen(R.id.groupQuick, R.string.btn_menu_quick) }
        findViewById<View>(R.id.boxAutomation).setOnClickListener {
            startActivity(Intent(this, AutomationActivity::class.java)) }
        // Android Back closes a sub-screen before leaving the app.
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (subScreenOpen()) {
                        closeSubScreen()
                    } else if (settingsOpen) {
                        closeSettings()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

        // --- new switches ---
        fun bindSw(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
            val sw = findViewById<CompoundButton>(id)
            sw.isChecked = initial
            sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        bindSw(R.id.swLan, Config.lanOn(this)) { on ->
            Config.setLanOn(this, on)
            if (on) Lan.discoverAsync(applicationContext)
        }
        bindSw(R.id.swAutoPriority, Config.autoPriorityOn(this)) {
            Config.setAutoPriorityOn(this, it) }
        bindSw(R.id.swClipSync, Config.clipSyncOn(this)) {
            Config.setClipSyncOn(this, it) }
        bindSw(R.id.swLostDark, Config.lostDarkScreen(this)) {
            Config.setLostDarkScreen(this, it) }
        bindSw(R.id.swSimpleMode, Config.simpleMode(this)) {
            Config.setSimpleMode(this, it) }
        bindSw(R.id.swLostLocal, Config.lostOn(this)) {
            LinkService.setLostMode(this, it) }

        // --- family panel ---
        findViewById<EditText>(R.id.etLostContact).setText(Config.lostContact(this))
        findViewById<Button>(R.id.btnSaveLostContact).setOnClickListener {
            Config.setLostContact(this,
                findViewById<EditText>(R.id.etLostContact).text.toString().trim())
            toast(getString(R.string.toast_saved))
        }
        val btnTime = findViewById<Button>(R.id.btnCheckinTime)
        btnTime.setOnClickListener {
            val expect = Config.checkinExpectMin(this)
            val h = if (expect >= 0) expect / 60 else 21
            val m = if (expect >= 0) expect % 60 else 0
            android.app.TimePickerDialog(this, { _, hh, mm ->
                Config.setCheckinExpectMin(this, hh * 60 + mm)
                toast(getString(R.string.toast_checkin_time_set))
            }, h, m, false).show()
        }
        btnTime.setOnLongClickListener {
            Config.setCheckinExpectMin(this, -1)
            toast(getString(R.string.toast_checkin_time_cleared))
            true
        }

        // --- security panel ---
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) { }
        }

        // --- group panel ---
        findViewById<Button>(R.id.btnCommanders).setOnClickListener {
            val peers = Peers.list(this).filter { it.dev.isNotEmpty() }
            if (peers.isEmpty()) { toast(getString(R.string.toast_no_peers)); return@setOnClickListener }
            val current = Config.commanders(this)
            val chosen = current.toMutableSet()
            val checked = BooleanArray(peers.size) { peers[it].dev in current }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_commanders))
                .setMultiChoiceItems(peers.map { it.name }.toTypedArray(), checked) { _, which, on ->
                    if (on) chosen.add(peers[which].dev) else chosen.remove(peers[which].dev)
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Config.setCommanders(this, chosen)
                    toast(getString(R.string.toast_saved))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        findViewById<Button>(R.id.btnPeerModes).setOnClickListener {
            val peers = Peers.list(this).filter { it.dev.isNotEmpty() }
            if (peers.isEmpty()) { toast(getString(R.string.toast_no_peers)); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_peer_modes))
                .setItems(peers.map { it.name }.toTypedArray()) { _, which ->
                    val peer = peers[which]
                    val modes = arrayOf("normal", "mute", "digest")
                    val labels = arrayOf(
                        getString(R.string.peer_mode_normal),
                        getString(R.string.peer_mode_mute),
                        getString(R.string.peer_mode_digest))
                    AlertDialog.Builder(this)
                        .setTitle(peer.name)
                        .setItems(labels) { _, mi ->
                            Config.setPeerMode(this, peer.dev, modes[mi])
                            toast(getString(R.string.toast_saved))
                        }
                        .show()
                }
                .show()
        }
        findViewById<Button>(R.id.btnBtShare).setOnClickListener {
            startActivity(Intent(this, BtActivity::class.java)) }
        bindToggle(R.id.swSmsFallback, "sms_fallback_on", Config.smsFallbackOn(this))
        findViewById<EditText>(R.id.etFallbackNumber).setText(Config.smsFallbackNumber(this))
        findViewById<EditText>(R.id.etSmtpHost).setText(Config.smtpHost(this))
        findViewById<EditText>(R.id.etSmtpPort).setText(Config.smtpPort(this).toString())
        findViewById<EditText>(R.id.etSmtpUser).setText(Config.smtpUser(this))
        findViewById<EditText>(R.id.etSmtpPass).setText(Config.smtpPass(this))
        findViewById<EditText>(R.id.etSmtpFrom).setText(Config.smtpFrom(this))
        findViewById<Button>(R.id.btnSaveSmtp).setOnClickListener {
            Config.setSmtp(this,
                findViewById<EditText>(R.id.etSmtpHost).text.toString().trim(),
                findViewById<EditText>(R.id.etSmtpPort).text.toString().toIntOrNull() ?: 587,
                findViewById<EditText>(R.id.etSmtpUser).text.toString().trim(),
                findViewById<EditText>(R.id.etSmtpPass).text.toString(),
                findViewById<EditText>(R.id.etSmtpFrom).text.toString().trim())
            toast(getString(R.string.toast_saved))
        }
        findViewById<Button>(R.id.btnShizuku).setOnClickListener {
            when {
                !ShizukuHelper.available() ->
                    toast(getString(R.string.shizuku_absent))
                ShizukuHelper.granted() -> {
                    toast(getString(R.string.shizuku_testing))
                    thread {
                        val ok = ShizukuHelper.selfTest()
                        val err = ShizukuHelper.lastError ?: ""
                        runOnUiThread {
                            if (ok) toast(getString(R.string.shizuku_ready))
                            else toast(getString(R.string.shizuku_broken, err))
                        }
                    }
                }
                else -> {
                    ShizukuHelper.request()
                    toast(getString(R.string.shizuku_ask))
                }
            }
        }
        findViewById<Button>(R.id.btnSaveFallback).setOnClickListener {
            Config.setSmsFallbackNumber(this,
                findViewById<EditText>(R.id.etFallbackNumber).text.toString().trim())
            toast(getString(R.string.toast_saved))
        }
        findViewById<Button>(R.id.btnCharts).setOnClickListener {
            startActivity(Intent(this, ChartsActivity::class.java)) }
        findViewById<Button>(R.id.btnExportConfig).setOnClickListener {
            cfgExportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "phonelink-backup.json"))
        }
        findViewById<Button>(R.id.btnImportConfig).setOnClickListener {
            cfgImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<Button>(R.id.btnConfigCode).setOnClickListener {
            val input = EditText(this).apply {
                setText(SettingsTransfer.export(this@MainActivity))
                setSelection(0)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_config_code))
                .setView(input)
                .setPositiveButton(getString(R.string.menu_copy)) { _, _ ->
                    copyToClipboard(input.text.toString()) }
                .setNeutralButton(getString(R.string.btn_import_config)) { _, _ ->
                    val ok = SettingsTransfer.import(this, input.text.toString())
                    toast(getString(if (ok) R.string.toast_cfg_imported
                        else R.string.toast_cfg_failed))
                    if (ok) recreate()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        // --- data panel ---
        findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            csvLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "phonelink-history.csv"))
        }
        findViewById<Button>(R.id.btnExportPdf).setOnClickListener {
            pdfLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "phonelink-history.pdf"))
        }

        // --- home additions ---
        findViewById<Button>(R.id.btnCheckin).setOnClickListener { doCheckin() }
        findViewById<Button>(R.id.btnScreenshot).setOnClickListener {
            askPin { digest ->
                Relay.sendCommand(this, "SCREENSHOT", null,
                    org.json.JSONObject().put("pin", digest))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        findViewById<Button>(R.id.btnScreenShare).setOnClickListener {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            screenLauncher.launch(mgr.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnLostMode).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_lost_mode))
                .setItems(arrayOf(getString(R.string.lost_turn_on),
                    getString(R.string.lost_turn_off))) { _, which ->
                    askPin { digest ->
                        Relay.sendCommand(this,
                            if (which == 0) "LOST_ON" else "LOST_OFF", null,
                            org.json.JSONObject().put("pin", digest))
                        toast(getString(R.string.toast_cmd_sent))
                    }
                }
                .show()
        }
    }

    private fun showPage(itemId: Int) {
        pageHome.visibility = if (itemId == R.id.navHome) View.VISIBLE else View.GONE
        pageInbox.visibility = if (itemId == R.id.navInbox) View.VISIBLE else View.GONE
        pageConfig.visibility = if (itemId == R.id.navConfig) View.VISIBLE else View.GONE
        if (itemId == R.id.navInbox) markInboxSeen()
        closeSubScreen()
    }

    private fun bindModeSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<CompoundButton>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked); refreshStatus() }
    }

    private fun bindToggle(id: Int, key: String, initial: Boolean) {
        val sw = findViewById<CompoundButton>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> Config.setToggle(this, key, checked) }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhoneLink", text))
        toast(getString(R.string.toast_copied))
    }

    private fun showItemMenu(item: History.Item) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(getString(R.string.menu_copy)); actions.add { copyToClipboard("${item.title}\n${item.body}") }
        if (item.code.isNotEmpty()) {
            labels.add(getString(R.string.menu_copy_code)); actions.add { copyToClipboard(item.code) }
        }
        if (item.type == Relay.TYPE_FILE && item.extra.isNotEmpty()) {
            labels.add(getString(R.string.menu_open_link)); actions.add {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.extra))) }
                catch (_: Exception) { copyToClipboard(item.extra) }
            }
        }
        if (item.extra.startsWith("tel:")) {
            val num = item.extra.removePrefix("tel:")
            labels.add(getString(R.string.menu_call_back)); actions.add {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) }
                catch (_: Exception) { }
            }
            labels.add(getString(R.string.menu_send_sms)); actions.add {
                try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))) }
                catch (_: Exception) { }
            }
        }
        labels.add(getString(if (item.pinned) R.string.menu_unpin else R.string.menu_pin))
        actions.add { History.setPinned(this, item, !item.pinned); refreshInbox() }
        labels.add(getString(if (item.starred) R.string.menu_unstar else R.string.menu_star))
        actions.add { History.setStarred(this, item, !item.starred); refreshInbox() }
        labels.add(getString(if (item.archived) R.string.menu_unarchive else R.string.menu_archive))
        actions.add { History.setArchived(this, item, !item.archived); refreshInbox() }
        if (item.type == Relay.TYPE_SMS && item.extra.startsWith("tel:")) {
            labels.add(getString(R.string.menu_reply_sms))
            actions.add { showReplyDialog(item.extra.removePrefix("tel:")) }
        }
        if (item.type == Relay.TYPE_CALL && item.extra.startsWith("tel:")) {
            labels.add(getString(R.string.menu_reply_sms))
            actions.add { showReplyDialog(item.extra.removePrefix("tel:")) }
        }
        if (item.type == Relay.TYPE_NOTIF && item.extra.startsWith("notif:")) {
            labels.add(getString(R.string.menu_reply_notif))
            actions.add { showNotifReplyDialog(item.extra.removePrefix("notif:")) }
            labels.add(getString(R.string.menu_dismiss_remote))
            actions.add {
                Relay.sendCommand(this, "DISMISS", null, org.json.JSONObject()
                    .put("key", item.extra.removePrefix("notif:")))
                toast(getString(R.string.toast_cmd_sent))
            }
        }
        if (item.id.isNotEmpty()) {
            labels.add(getString(R.string.menu_react))
            actions.add { showReactDialog(item) }
            labels.add(getString(R.string.menu_delete_both))
            actions.add {
                History.removeById(this, item.id)
                Relay.sendCommand(this, "DELETE_MSG", null,
                    org.json.JSONObject().put("msgId", item.id))
                refreshInbox()
            }
        }
        if (item.body.isNotEmpty()) {
            labels.add(getString(R.string.menu_quote_reply))
            actions.add { showQuoteDialog(item) }
            labels.add(getString(R.string.menu_translate))
            actions.add { translateItem(item) }
        }
        labels.add(getString(R.string.menu_delete))
        actions.add { History.delete(this, item); refreshInbox(); toast(getString(R.string.toast_item_deleted)) }
        AlertDialog.Builder(this)
            .setTitle(item.title.take(60))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showReplyDialog(number: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_reply_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reply_title, number))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val extra = org.json.JSONObject()
                        .put("number", number).put("text", text)
                    Relay.sendCommand(this, "REPLY_SMS", null, extra)
                    toast(getString(R.string.toast_reply_sent))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showReactDialog(item: History.Item) {
        val emojis = arrayOf("👍", "❤️", "😮", "😂", "🙏", "🔥")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.react_title))
            .setItems(emojis) { _, which ->
                val payload = org.json.JSONObject()
                    .put("v", 2).put("type", "REACT")
                    .put("emoji", emojis[which])
                    .put("title", item.title.take(60))
                    .put("from", Config.deviceName(this))
                    .put("dev", Config.deviceId(this))
                    .put("time", System.currentTimeMillis())
                thread { Relay.post(this, Config.cmdTopic(this), payload) }
                toast(getString(R.string.toast_cmd_sent))
            }
            .show()
    }

    private fun showQuoteDialog(item: History.Item) {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_reply_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quote_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val quote = "💬 «${item.title.take(50)}»"
                    Relay.send(this, Relay.TYPE_NOTIF, quote, text)
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showNotifReplyDialog(notifKey: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_reply_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reply_notif_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val extra = org.json.JSONObject()
                        .put("key", notifKey).put("text", text)
                    Relay.sendCommand(this, "REPLY_NOTIF", null, extra)
                    toast(getString(R.string.toast_reply_sent))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showProfilesDialog() {
        val profiles = Config.profiles(this)
        val labels = mutableListOf<String>()
        profiles.forEach { (name, code) -> labels.add("⭐ $name — $code") }
        labels.add(getString(R.string.profile_add_current))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_title))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < profiles.size) {
                    val code = profiles[which].second
                    Config.setPairCode(this, code)
                    refreshPairCode()
                    LinkService.restart(this)
                    toast(getString(R.string.toast_code_set))
                } else {
                    val input = EditText(this).apply {
                        hint = getString(R.string.hint_profile_name)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.profile_add_current))
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val name = input.text.toString().trim()
                                .ifEmpty { getString(R.string.profile_default_name) }
                            Config.addProfile(this, name, Config.pairCode(this))
                            toast(getString(R.string.toast_profile_saved))
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** Opening the inbox marks its items seen and tells the sender. */
    private fun markInboxSeen() {
        try {
            val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seen_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
            val fresh = History.all(this).map { it.id }
                .filter { it.isNotEmpty() && it !in seen }
            if (fresh.isEmpty()) return
            seen.addAll(fresh)
            while (seen.size > 300) seen.remove(seen.first())
            prefs.edit().putStringSet("seen_ids", seen).apply()
            Relay.sendSeen(this, fresh)
        } catch (_: Exception) { }
    }

    /** Telegram forum-topic ids, one per message category. */
    private fun showTgTopicsDialog() {
        val keys = listOf("sms", "notif", "call", "battery", "other")
        val labelRes = listOf(R.string.label_sms, R.string.label_notif,
            R.string.label_calls, R.string.label_battery, R.string.tg_topic_other)
        val inputs = keys.mapIndexed { i, key ->
            EditText(this).apply {
                hint = getString(labelRes[i]) + " — " +
                    getString(R.string.hint_thread_id)
                inputType = InputType.TYPE_CLASS_NUMBER
                val v = Config.tgThread(this@MainActivity, key)
                if (v > 0) setText(v.toString())
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
            inputs.forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tg_topics_title))
            .setMessage(getString(R.string.tg_topics_help))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                fun num(et: EditText) = et.text.toString().trim().toIntOrNull() ?: 0
                Config.setTgThreads(this,
                    num(inputs[0]), num(inputs[1]), num(inputs[2]),
                    num(inputs[3]), num(inputs[4]))
                toast(getString(R.string.toast_filters_saved))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ---------------- v2.3 dialogs ----------------

    /** Asks for the command PIN and hands back its digest. */
    private fun askPin(onOk: (String) -> Unit) {
        // v2.11: a stored per-device PIN (Devices screen) is used
        // silently when exactly one is on file.
        val stored = Config.storedPeerPins(this)
        if (stored.size == 1) { onOk(stored[0]); return }
        val input = EditText(this).apply {
            hint = getString(R.string.hint_cmd_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cmd_pin_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length >= 4) onOk(Config.digestForPin(pin))
                else toast(getString(R.string.toast_pin_short))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun remoteKeyLabel(key: String): String = getString(when (key) {
        "sender_on" -> R.string.rc_sender
        "receiver_on" -> R.string.rc_receiver
        "fwd_sms" -> R.string.label_sms
        "fwd_notif" -> R.string.label_notif
        "fwd_calls" -> R.string.label_calls
        "fwd_battery" -> R.string.label_battery
        "fwd_security" -> R.string.label_security
        "fwd_system" -> R.string.label_system
        "fwd_presence" -> R.string.fwd_presence
        "fwd_media" -> R.string.fwd_media
        "guard_on" -> R.string.guard_label
        else -> R.string.rc_sender
    })

    private fun showRemotePanel() {
        val peer = Peers.list(this).firstOrNull()
        val keys = Config.remoteKeys()
        val cfgMap = (peer?.cfg ?: "").split(",").mapNotNull { part ->
            val kv = part.split(":")
            if (kv.size == 2) kv[0] to (kv[1] == "1") else null
        }.toMap()
        val labels = keys.map { remoteKeyLabel(it) }.toTypedArray()
        val states = BooleanArray(keys.size) { cfgMap[keys[it]] ?: false }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_title, peer?.name ?: ""))
            .setMultiChoiceItems(labels, states) { _, which, checked ->
                val key = keys[which]
                askPin { digest ->
                    val extra = org.json.JSONObject()
                        .put("key", key).put("value", if (checked) 1 else 0)
                        .put("pin", digest)
                    Relay.sendCommand(this, "SET", null, extra)
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
            .setPositiveButton(getString(R.string.btn_close), null)
            .setNeutralButton(getString(R.string.btn_remote_modes)) { _, _ ->
                showRemoteModes()
            }
            .setNegativeButton(getString(R.string.btn_remote_pause)) { _, _ ->
                Relay.sendCommand(this, "PAUSE", null,
                    org.json.JSONObject().put("minutes", 60))
                toast(getString(R.string.toast_cmd_sent))
            }
            .show()
    }

    private fun showRemoteModes() {
        val modes = arrayOf("sleep", "meeting", "study", "off")
        val labels = arrayOf(
            getString(R.string.mode_sleep), getString(R.string.mode_meeting),
            getString(R.string.mode_study), getString(R.string.mode_off))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_modes_title))
            .setItems(labels) { _, which ->
                askPin { digest ->
                    val extra = org.json.JSONObject()
                        .put("mode", modes[which]).put("pin", digest)
                    Relay.sendCommand(this, "MODE", null, extra)
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
            .show()
    }

    private fun showModeDialog() {
        val modes = arrayOf("sleep", "meeting", "study", "off")
        val labels = arrayOf(
            getString(R.string.mode_sleep), getString(R.string.mode_meeting),
            getString(R.string.mode_study), getString(R.string.mode_off))
        val current = modes.indexOf(Config.modeName(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mode_title))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Config.applyMode(this, modes[which])
                toast(getString(R.string.toast_mode_set, labels[which]))
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun textCommandDialog(titleRes: Int, hintRes: Int, command: String) {
        val input = EditText(this).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Relay.sendCommand(this, command, null,
                        org.json.JSONObject().put("text", text))
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSpeakDialog() =
        textCommandDialog(R.string.speak_title, R.string.hint_speak_text, "SPEAK")

    private fun showNoteDialog() {
        val input = EditText(this).apply {
            setText(Config.sharedNote(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.note_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val text = input.text.toString()
                Config.setSharedNote(this, text)
                val payload = org.json.JSONObject()
                    .put("v", 2).put("type", "NOTE").put("text", text)
                    .put("from", Config.deviceName(this))
                    .put("dev", Config.deviceId(this))
                    .put("time", System.currentTimeMillis())
                thread { Relay.post(this, Config.cmdTopic(this), payload) }
                toast(getString(R.string.toast_note_saved))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSirenDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_siren_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.siren_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                    .ifEmpty { getString(R.string.siren_default) }
                Relay.send(this, Relay.TYPE_URGENT, "🚨 ${getString(R.string.siren_title)}", text)
                toast(getString(R.string.toast_cmd_sent))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAlarmDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_alarm_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.alarm_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mins = input.text.toString().trim().toIntOrNull() ?: 0
                if (mins > 0) {
                    Relay.sendCommand(this, "ALARM", null,
                        org.json.JSONObject().put("minutes", mins)
                            .put("text", getString(R.string.alarm_remote_text)))
                    toast(getString(R.string.toast_cmd_sent))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showRingerDialog() {
        val modes = arrayOf("normal", "vibrate", "silent")
        val labels = arrayOf(
            getString(R.string.ringer_normal), getString(R.string.ringer_vibrate),
            getString(R.string.ringer_silent))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ringer_title))
            .setItems(labels) { _, which ->
                Relay.sendCommand(this, "RINGER", null,
                    org.json.JSONObject().put("mode", modes[which]))
                toast(getString(R.string.toast_cmd_sent))
            }
            .show()
    }

    private fun showScheduleDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_schedule_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.schedule_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_schedule_set)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val now = Calendar.getInstance()
                TimePickerDialog(this, { _, h, m ->
                    val target = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    SchedStore.add(this, target.timeInMillis, text)
                    toast(getString(R.string.toast_schedule_set))
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
            }
            .setNeutralButton(getString(R.string.btn_schedule_list)) { _, _ ->
                showScheduleList()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showScheduleList() {
        val items = SchedStore.list(this)
        if (items.isEmpty()) {
            toast(getString(R.string.schedule_empty))
            return
        }
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val labels = items.map { "${it.text}\n${fmt.format(Date(it.time))}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.schedule_title))
            .setItems(labels) { _, which ->
                SchedStore.remove(this, items[which].id)
                toast(getString(R.string.toast_schedule_removed))
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showTemplatesDialog() {
        val templates = Config.templates(this)
        val labels = mutableListOf<String>()
        templates.forEach { (name, _) -> labels.add(name) }
        labels.add(getString(R.string.template_add))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.templates_title))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < templates.size) {
                    val (name, body) = templates[which]
                    AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage(body)
                        .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                            Relay.send(this, Relay.TYPE_NOTIF, "💬 $name", body)
                            toast(getString(R.string.toast_cmd_sent))
                        }
                        .setNeutralButton(getString(R.string.menu_delete)) { _, _ ->
                            Config.saveTemplates(this,
                                templates.filterIndexed { i, _ -> i != which })
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                } else {
                    val nameInput = EditText(this).apply {
                        hint = getString(R.string.hint_template_name)
                    }
                    val bodyInput = EditText(this).apply {
                        hint = getString(R.string.hint_template_body)
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    val layout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 8, 48, 8)
                        addView(nameInput); addView(bodyInput)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.template_add))
                        .setView(layout)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val n = nameInput.text.toString().trim()
                            val b = bodyInput.text.toString().trim()
                            if (n.isNotEmpty() && b.isNotEmpty()) {
                                Config.saveTemplates(this, templates + (n to b))
                                toast(getString(R.string.toast_profile_saved))
                            }
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
            .show()
    }

    private fun showSetPinDialog() {
        if (Config.cmdPinIsSet(this)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cmd_pin_title))
                .setMessage(getString(R.string.pin_is_set))
                .setPositiveButton(getString(R.string.pin_change)) { _, _ -> pinEntryDialog() }
                .setNeutralButton(getString(R.string.pin_remove)) { _, _ ->
                    Config.clearCmdPin(this)
                    SecLog.add(this, "কমান্ড PIN সরানো হয়েছে")
                    refreshStatus()
                    toast(getString(R.string.toast_pin_removed))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        } else {
            pinEntryDialog()
        }
    }

    private fun pinEntryDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_new_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_set_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length >= 4) {
                    Config.setCmdPin(this, pin)
                    SecLog.add(this, "কমান্ড PIN সেট/বদল হয়েছে")
                    refreshStatus()
                    toast(getString(R.string.toast_pin_set))
                } else {
                    toast(getString(R.string.toast_pin_short))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSecLogDialog() {
        val entries = SecLog.all(this)
        if (entries.isEmpty()) {
            toast(getString(R.string.seclog_empty))
            return
        }
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val labels = entries.map { (t, s) -> "${fmt.format(Date(t))} — $s" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.seclog_title))
            .setItems(labels, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** On-device translation of an inbox item (ML Kit, model downloads once). */
    private fun translateItem(item: History.Item) {
        val text = (item.title + "\n" + item.body).trim().take(900)
        val hasBn = text.any { it.code in 0x0980..0x09FF }
        val src = if (hasBn) com.google.mlkit.nl.translate.TranslateLanguage.BENGALI
            else com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
        val dst = if (hasBn) com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
            else com.google.mlkit.nl.translate.TranslateLanguage.BENGALI
        toast(getString(R.string.toast_translating))
        val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(src).setTargetLanguage(dst).build()
        val client = com.google.mlkit.nl.translate.Translation.getClient(options)
        client.downloadModelIfNeeded()
            .addOnSuccessListener {
                client.translate(text)
                    .addOnSuccessListener { out ->
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.translate_title))
                            .setMessage(out)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        client.close()
                    }
                    .addOnFailureListener {
                        toast(getString(R.string.toast_translate_failed))
                        client.close()
                    }
            }
            .addOnFailureListener {
                toast(getString(R.string.toast_translate_failed))
                client.close()
            }
    }


    /** v2.11: one-line text prompt used by the new remote buttons. */
    private fun showTextPrompt(title: String, initial: String,
                               onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun sendSosSms(numbers: List<String>, text: String) {
        try {
            val sm = android.telephony.SmsManager.getDefault()
            for (n in numbers) {
                try { sm.sendTextMessage(n, null, text, null, null) }
                catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    private fun ownIp(): String {
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val addrs = ifs.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address &&
                        a.isSiteLocalAddress) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }

    /** v2.11: spoken shortcut commands (Bengali + English keywords). */
    private fun runVoiceCommand(heard: String) {
        val t = heard.lowercase()
        fun has(vararg words: String) = words.any { t.contains(it) }
        when {
            has("ring", "রিং", "বাজাও") -> {
                Relay.sendCommand(this, "RING")
                toast(getString(R.string.voicecmd_did, heard, "RING"))
            }
            has("location", "লোকেশন", "অবস্থান", "কোথায়") -> {
                Relay.sendCommand(this, "LOCATION")
                toast(getString(R.string.voicecmd_did, heard, "LOCATION"))
            }
            has("flash", "ফ্ল্যাশ", "লাইট") -> {
                Relay.sendCommand(this, "FLASH_ON")
                toast(getString(R.string.voicecmd_did, heard, "FLASH_ON"))
            }
            has("screenshot", "স্ক্রিনশট", "ছবি তোল") -> {
                askPin { d -> Relay.sendCommand(this, "SNAP", null,
                    org.json.JSONObject().put("pin", d)) }
                toast(getString(R.string.voicecmd_did, heard, "SNAP"))
            }
            has("lock", "লক") -> {
                askPin { d -> Relay.sendCommand(this, "LOCK", null,
                    org.json.JSONObject().put("pin", d)) }
                toast(getString(R.string.voicecmd_did, heard, "LOCK"))
            }
            has("status", "স্ট্যাটাস", "ব্যাটারি") -> {
                Relay.sendCommand(this, "STATUS")
                toast(getString(R.string.voicecmd_did, heard, "STATUS"))
            }
            else -> toast(getString(R.string.voicecmd_unknown, heard))
        }
    }

    /** v2.11 focus guard settings (enforced by A11yService). */
    private fun showFocusDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val sw = android.widget.Switch(this).apply {
            text = getString(R.string.cmd_focus)
            isChecked = Config.focusOn(this@MainActivity)
        }
        val etPkgs = EditText(this).apply {
            hint = getString(R.string.focus_pkgs_hint)
            setText(Config.focusPkgs(this@MainActivity).joinToString(", "))
        }
        val (fs, fe) = Config.focusWindow(this)
        val etWin = EditText(this).apply {
            hint = "HH:MM-HH:MM"
            setText("%02d:%02d-%02d:%02d".format(fs / 60, fs % 60, fe / 60, fe % 60))
        }
        layout.addView(sw); layout.addView(etPkgs); layout.addView(etWin)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cmd_focus))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                Config.setFocusOn(this, sw.isChecked)
                Config.setFocusPkgs(this, etPkgs.text.toString())
                val m = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")
                    .find(etWin.text.toString().trim())
                if (m != null) {
                    Config.setFocusWindow(this,
                        m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt(),
                        m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt())
                }
                toast(getString(R.string.toast_saved))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showDevicesDialog() {
        val peers = Peers.list(this)
        if (peers.isEmpty()) {
            toast(getString(R.string.devices_empty))
            return
        }
        val now = System.currentTimeMillis()
        val labels = peers.map { p ->
            val mins = (now - p.seen) / 60000
            val whenText = when {
                mins < 1 -> getString(R.string.time_just_now)
                mins < 60 -> getString(R.string.time_mins_ago, mins)
                mins < 1440 -> getString(R.string.time_hours_ago, mins / 60)
                else -> getString(R.string.time_days_ago, mins / 1440)
            }
            val batt = if (p.battery >= 0) " • 🔋 ${p.battery}%" else ""
            val status = if (p.status.isNotEmpty()) "\n💬 ${p.status}" else ""
            "${p.name}$batt — $whenText$status"
        }.toMutableList()
        BatteryHist.summary(this)?.let { labels.add(it) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.devices_title))
            .setItems(labels.toTypedArray()) { _, which ->
                val peer = peers.getOrNull(which) ?: return@setItems
                if (peer.dev.isEmpty()) return@setItems
                val has = Config.peerPin(this, peer.dev).isNotEmpty()
                val opts = if (has) arrayOf(
                    getString(R.string.dev_pin_set), getString(R.string.dev_pin_clear))
                    else arrayOf(getString(R.string.dev_pin_set))
                AlertDialog.Builder(this)
                    .setTitle(peer.name)
                    .setItems(opts) { _, w ->
                        if (w == 0) {
                            val input = EditText(this).apply {
                                hint = getString(R.string.hint_cmd_pin)
                                inputType = InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
                            }
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.dev_pin_set))
                                .setView(input)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val pin = input.text.toString().trim()
                                    if (pin.length >= 4) {
                                        Config.setPeerPin(this, peer.dev,
                                            Config.digestForPin(pin))
                                        toast(getString(R.string.toast_saved))
                                    } else toast(getString(R.string.toast_pin_short))
                                }
                                .setNegativeButton(getString(R.string.btn_cancel), null)
                                .show()
                        } else {
                            Config.setPeerPin(this, peer.dev, "")
                            toast(getString(R.string.toast_saved))
                        }
                    }
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showScreenDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_screen_text)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cmd_screen))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Relay.sendCommand(this, "SCREEN", text)
                    toast(getString(R.string.toast_cmd_screen))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSetPinDialog(onDone: (Boolean) -> Unit) {
        val pin1 = EditText(this).apply {
            hint = getString(R.string.hint_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val pin2 = EditText(this).apply {
            hint = getString(R.string.hint_pin_again)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(pin1); addView(pin2)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lock_set_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val a = pin1.text.toString(); val b = pin2.text.toString()
                if (a.length >= 4 && a == b) {
                    Config.setPinHash(this, Crypto.pinHash(a))
                    Config.setAppLockOn(this, true)
                    toast(getString(R.string.toast_pin_set))
                    onDone(true)
                } else {
                    toast(getString(R.string.toast_pin_mismatch))
                    onDone(false)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> onDone(false) }
            .show()
    }

    private fun fmtClock(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun buildStatsText(): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.stats_sent, Stats.sent(this)))
        sb.append(" • ").append(getString(R.string.stats_recv, Stats.recv(this)))
        sb.append(" • ").append(getString(R.string.stats_cmd, Stats.cmds(this)))
        val top = Stats.typeCounts(this, sentSide = true).entries
            .sortedByDescending { it.value }.take(4)
        if (top.isNotEmpty()) {
            sb.append("\n").append(top.joinToString(" • ") { "${it.key}: ${it.value}" })
        }
        return sb.toString()
    }

    private fun refreshPairCode() {
        val code = Config.pairCode(this)
        tvPairCode.text = code.chunked(5).joinToString(" ")
        tvSafety.text = getString(R.string.safety_number, safetyNumber())
    }

    /** 4-digit check number derived from the pair code — same on both
     *  phones when (and only when) both use the same code. */
    private fun safetyNumber(): String {
        return try {
            val topic = Config.dataTopic(this)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(topic.toByteArray(Charsets.UTF_8))
            val n = ((digest[0].toInt() and 0xFF) shl 8 or (digest[1].toInt() and 0xFF)) % 10000
            String.format(Locale.US, "%04d", n)
        } catch (_: Exception) { "----" }
    }

    private fun setPerm(tv: TextView, granted: Boolean) {
        tv.text = getString(if (granted) R.string.status_granted else R.string.status_missing)
        tv.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.status_ok else R.color.status_bad)
        )
    }

    private fun refreshStatus() {
        tvMode.text = getString(
            when {
                Config.isSender(this) && Config.isReceiver(this) -> R.string.mode_both
                Config.isSender(this) -> R.string.mode_sender
                Config.isReceiver(this) -> R.string.mode_receiver
                else -> R.string.mode_none
            }
        )
        setPerm(tvPermNotif, PermChecks.notifAccess(this))
        setPerm(tvPermSms, PermChecks.has(this, android.Manifest.permission.RECEIVE_SMS))
        setPerm(tvPermLoc, PermChecks.has(this, android.Manifest.permission.ACCESS_FINE_LOCATION))
        setPerm(tvPermBattery, PermChecks.batteryIgnored(this))

        val missing = PermChecks.missingCount(this)
        btnFixPerms.visibility = if (missing > 0) View.VISIBLE else View.GONE
        tvPermSummary.text = if (missing == 0) getString(R.string.perm_summary_ok)
        else getString(R.string.perm_summary_missing, missing)

        // Relay connection state
        val okAt = Relay.lastRelayOk(this)
        val latency = Relay.lastLatencyMs(this)
        val latencySuffix = if (latency > 0) " • ${latency}ms" else ""
        if (okAt == 0L) {
            tvRelay.text = getString(R.string.relay_never)
            tvRelay.setTextColor(ContextCompat.getColor(this, R.color.status_bad))
        } else {
            val ageMin = (System.currentTimeMillis() - okAt) / 60000
            if (ageMin < 3) {
                tvRelay.text = getString(R.string.relay_connected) + latencySuffix
                tvRelay.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            } else {
                val whenText = when {
                    ageMin < 60 -> getString(R.string.time_mins_ago, ageMin)
                    ageMin < 1440 -> getString(R.string.time_hours_ago, ageMin / 60)
                    else -> getString(R.string.time_days_ago, ageMin / 1440)
                }
                tvRelay.text = getString(R.string.relay_last, whenText) + latencySuffix
                tvRelay.setTextColor(ContextCompat.getColor(this,
                    if (ageMin < 30) R.color.status_ok else R.color.status_bad))
            }
        }

        // Delivery state
        val pending = Relay.pendingAckCount(this)
        val ackAt = Relay.lastAckTime(this)
        val seenAt = Relay.lastSeenTime(this)
        val seenSuffix = if (seenAt > 0) " • " + getString(R.string.delivery_seen,
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(seenAt))) else ""
        tvDelivery.text = when {
            pending > 0 -> getString(R.string.delivery_pending, pending) + seenSuffix
            ackAt > 0 -> getString(R.string.delivery_last,
                Relay.lastAckTitle(this),
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ackAt))) + seenSuffix
            else -> getString(R.string.delivery_none) + seenSuffix
        }

        tvQueueInfo.text = getString(R.string.queue_info, Relay.queueSize(this))

        // Smart-control / data info lines
        tvQuietInfo.text = getString(R.string.quiet_window,
            fmtClock(Config.quietStart(this)), fmtClock(Config.quietEnd(this)))
        val dm = Config.digestMinutes(this)
        tvDigestInfo.text = if (dm == 0) getString(R.string.digest_off)
            else getString(R.string.digest_every, dm) +
                " • " + getString(R.string.digest_waiting, DigestStore.size(this))
        tvGeoInfo.text = if (Config.geoIsSet(this)) {
            getString(R.string.geo_set, Config.geoLat(this), Config.geoLng(this), Config.geoRadius(this))
        } else getString(R.string.geo_not_set)
        tvRoutineInfo.text = getString(R.string.routine_at, fmtClock(Config.routineMinutes(this)))
        val kd = Config.inboxKeepDays(this)
        tvRetentionInfo.text = if (kd == 0) getString(R.string.retention_forever)
            else getString(R.string.retention_days, kd)
        tvStats.text = buildStatsText()

        // Peer status + security states
        val peer = Peers.list(this).firstOrNull()
        tvPeerStatus.text = when {
            peer == null -> ""
            peer.status.isNotEmpty() -> getString(R.string.peer_status, peer.name, peer.status)
            else -> getString(R.string.peer_seen, peer.name)
        }
        tvPinInfo.text = getString(
            if (Config.cmdPinIsSet(this)) R.string.pin_state_set else R.string.pin_state_unset)
        tvAdminInfo.text = getString(
            if (AdminReceiver.isActive(this)) R.string.admin_state_on else R.string.admin_state_off)

        val q = Relay.queueSize(this)
        if (q > 0) {
            tvQueue.text = getString(R.string.queue_offline, q)
            tvQueue.visibility = View.VISIBLE
        } else {
            tvQueue.visibility = View.GONE
        }

        val seen = Config.lastSenderSeen(this)
        tvSenderSeen.text = if (seen == 0L) getString(R.string.sender_never_seen)
        else {
            val mins = (System.currentTimeMillis() - seen) / 60000
            val whenText = when {
                mins < 1 -> getString(R.string.time_just_now)
                mins < 60 -> getString(R.string.time_mins_ago, mins)
                mins < 1440 -> getString(R.string.time_hours_ago, mins / 60)
                else -> getString(R.string.time_days_ago, mins / 1440)
            }
            getString(R.string.sender_last_seen, Config.lastSenderName(this), whenText)
        }
    }

    private fun refreshInbox() {
        inboxItems = History.all(this)
        renderInbox()
    }

    private fun filteredInbox(): List<History.Item> {
        // v2.11: archived items live behind their own chip; starred
        // items behind the star chip; everything else hides archived.
        val pool = when (inboxCat) {
            "ARCHIVED" -> inboxItems.filter { it.archived }
            "STAR" -> inboxItems.filter { it.starred && !it.archived }
            else -> inboxItems.filter { !it.archived }
        }
        val byCat = when (inboxCat) {
            "SMS" -> pool.filter { it.type == Relay.TYPE_SMS }
            "CALL" -> pool.filter { it.type == Relay.TYPE_CALL }
            "NOTIF" -> pool.filter { it.type == Relay.TYPE_NOTIF }
            "BATTERY" -> pool.filter { it.type == Relay.TYPE_BATTERY }
            "OTHER" -> pool.filter {
                it.type !in setOf(Relay.TYPE_SMS, Relay.TYPE_CALL, Relay.TYPE_NOTIF, Relay.TYPE_BATTERY)
            }
            else -> pool
        }
        val q = etSearch.text.toString().trim().lowercase()
        if (q.isEmpty()) return byCat
        return byCat.filter {
            it.title.lowercase().contains(q) || it.body.lowercase().contains(q) ||
                it.type.lowercase().contains(q) || it.from.lowercase().contains(q)
        }
    }

    private fun renderInbox() {
        // v2.11 panic mode: the inbox pretends to be empty.
        if (Config.panicActive(this)) {
            lvInbox.adapter = InboxAdapter(this, emptyList(),
                getString(R.string.inbox_empty))
            return
        }
        lvInbox.adapter = InboxAdapter(this, filteredInbox(), getString(R.string.inbox_empty))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
