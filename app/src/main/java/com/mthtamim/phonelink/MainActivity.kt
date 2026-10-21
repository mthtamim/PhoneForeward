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
    private lateinit var bottomNav: BottomNavigationView

    private var inboxItems: List<History.Item> = emptyList()
    private var inboxReceiver: BroadcastReceiver? = null
    private var inboxCat: String = "ALL"

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra("code")?.trim().orEmpty()
            applyPairCode(code)
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFile(uri)
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                var ok = false
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(History.exportJson(applicationContext).toByteArray(Charsets.UTF_8))
                    }
                    ok = true
                } catch (_: Exception) { }
                Handler(Looper.getMainLooper()).post {
                    toast(getString(if (ok) R.string.toast_backup_done else R.string.toast_backup_failed))
                }
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            thread {
                var count = -1
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.readText().orEmpty()
                    count = History.importJson(applicationContext, text)
                } catch (_: Exception) { }
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
                        if (total > 5 * 1024 * 1024) throw IllegalStateException("too big")
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: throw IllegalStateException("unreadable")
                ok = Relay.postFile(applicationContext, name, bytes, mime)
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

        setContentView(R.layout.activity_main)

        pageHome = findViewById(R.id.pageHome)
        pageInbox = findViewById(R.id.pageInbox)
        pageSettings = findViewById(R.id.pageSettings)
        bottomNav = findViewById(R.id.bottomNav)

        tvMode = findViewById(R.id.tvMode)
        tvRelay = findViewById(R.id.tvRelay)
        tvDelivery = findViewById(R.id.tvDelivery)
        tvQueueInfo = findViewById(R.id.tvQueueInfo)
        tvQuietInfo = findViewById(R.id.tvQuietInfo)
        tvDigestInfo = findViewById(R.id.tvDigestInfo)
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

        etDeviceName.setText(Config.deviceName(this))
        etPriority.setText(Config.priorityRaw(this))
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
        findViewById<Button>(R.id.btnSaveServer).setOnClickListener {
            var url = etServer.text.toString().trim()
            if (url.isEmpty()) url = "https://ntfy.sh"
            if (!url.startsWith("http")) url = "https://$url"
            Config.setServerUrl(this, url)
            var backup = etBackupServer.text.toString().trim()
            if (backup.isNotEmpty() && !backup.startsWith("http")) backup = "https://$backup"
            Config.setBackupServerUrl(this, backup)
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
        findViewById<Button>(R.id.btnCmdLost).setOnClickListener { Relay.sendCommand(this, "LOST"); toast(getString(R.string.toast_cmd_lost)) }
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
            toast(getString(R.string.toast_filters_saved))
        }
        bindToggle(R.id.swSmsTrigger, "sms_trigger", Config.smsTriggerOn(this))

        // ----- more features (v2.1) -----
        bindToggle(R.id.swTts, "tts_codes", Config.ttsCodes(this))
        bindToggle(R.id.swSmsReply, "allow_sms_reply", Config.allowSmsReply(this))
        bindToggle(R.id.swSnap, "allow_snap", Config.allowSnap(this))
        bindToggle(R.id.swGeo, "geo_on", Config.geoOn(this))
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
                    toast(getString(R.string.toast_sos_sent))
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
        findViewById<Button>(R.id.btnCmdSnap).setOnClickListener {
            Relay.sendCommand(this, "SNAP"); toast(getString(R.string.toast_cmd_snap))
        }
        findViewById<Button>(R.id.btnCmdSummary).setOnClickListener {
            Relay.sendCommand(this, "SUMMARY"); toast(getString(R.string.toast_cmd_summary))
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
        inboxReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        inboxReceiver = null
    }

    private fun showPage(itemId: Int) {
        pageHome.visibility = if (itemId == R.id.navHome) View.VISIBLE else View.GONE
        pageInbox.visibility = if (itemId == R.id.navInbox) View.VISIBLE else View.GONE
        pageSettings.visibility = if (itemId == R.id.navSettings) View.VISIBLE else View.GONE
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
        labels.add(getString(if (item.pinned) R.string.menu_unpin else R.string.menu_pin))
        actions.add { History.setPinned(this, item, !item.pinned); refreshInbox() }
        if (item.type == Relay.TYPE_SMS && item.extra.startsWith("tel:")) {
            labels.add(getString(R.string.menu_reply_sms))
            actions.add { showReplyDialog(item.extra.removePrefix("tel:")) }
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
            "${p.name}$batt — $whenText"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.devices_title))
            .setItems(labels, null)
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
        if (okAt == 0L) {
            tvRelay.text = getString(R.string.relay_never)
            tvRelay.setTextColor(ContextCompat.getColor(this, R.color.status_bad))
        } else {
            val ageMin = (System.currentTimeMillis() - okAt) / 60000
            if (ageMin < 3) {
                tvRelay.text = getString(R.string.relay_connected)
                tvRelay.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
            } else {
                val whenText = when {
                    ageMin < 60 -> getString(R.string.time_mins_ago, ageMin)
                    ageMin < 1440 -> getString(R.string.time_hours_ago, ageMin / 60)
                    else -> getString(R.string.time_days_ago, ageMin / 1440)
                }
                tvRelay.text = getString(R.string.relay_last, whenText)
                tvRelay.setTextColor(ContextCompat.getColor(this,
                    if (ageMin < 30) R.color.status_ok else R.color.status_bad))
            }
        }

        // Delivery state
        val pending = Relay.pendingAckCount(this)
        val ackAt = Relay.lastAckTime(this)
        tvDelivery.text = when {
            pending > 0 -> getString(R.string.delivery_pending, pending)
            ackAt > 0 -> getString(R.string.delivery_last,
                Relay.lastAckTitle(this),
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ackAt)))
            else -> getString(R.string.delivery_none)
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
        val kd = Config.inboxKeepDays(this)
        tvRetentionInfo.text = if (kd == 0) getString(R.string.retention_forever)
            else getString(R.string.retention_days, kd)
        tvStats.text = buildStatsText()

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
        val byCat = when (inboxCat) {
            "SMS" -> inboxItems.filter { it.type == Relay.TYPE_SMS }
            "CALL" -> inboxItems.filter { it.type == Relay.TYPE_CALL }
            "NOTIF" -> inboxItems.filter { it.type == Relay.TYPE_NOTIF }
            "BATTERY" -> inboxItems.filter { it.type == Relay.TYPE_BATTERY }
            "OTHER" -> inboxItems.filter {
                it.type !in setOf(Relay.TYPE_SMS, Relay.TYPE_CALL, Relay.TYPE_NOTIF, Relay.TYPE_BATTERY)
            }
            else -> inboxItems
        }
        val q = etSearch.text.toString().trim().lowercase()
        if (q.isEmpty()) return byCat
        return byCat.filter {
            it.title.lowercase().contains(q) || it.body.lowercase().contains(q) ||
                it.type.lowercase().contains(q) || it.from.lowercase().contains(q)
        }
    }

    private fun renderInbox() {
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val rows = filteredInbox().map { item ->
            val icon = when (item.type) {
                Relay.TYPE_SMS -> "✉️"; Relay.TYPE_NOTIF -> "🔔"; Relay.TYPE_CALL -> "📞"
                Relay.TYPE_BATTERY -> "🔋"; Relay.TYPE_APP -> "📦"; Relay.TYPE_LOCATION -> "📍"
                Relay.TYPE_SYSTEM -> "⚙️"; Relay.TYPE_TEST -> "🧪"; else -> "•"
            }
            val pin = if (item.pinned) "📌 " else ""
            "$pin$icon [${item.type}] ${item.title}\n${item.body}\n— ${item.from} • ${fmt.format(Date(item.time))}"
        }
        val display = if (rows.isEmpty()) listOf(getString(R.string.inbox_empty)) else rows
        lvInbox.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
