package com.mthtamim.phonelink

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : LocalizedActivity() {

    private lateinit var tvMode: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pageHome = findViewById(R.id.pageHome)
        pageInbox = findViewById(R.id.pageInbox)
        pageSettings = findViewById(R.id.pageSettings)
        bottomNav = findViewById(R.id.bottomNav)

        tvMode = findViewById(R.id.tvMode)
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

        etDeviceName.setText(Config.deviceName(this))
        etServer.setText(Config.serverUrl(this))
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
            LinkService.restart(this)
            toast(getString(R.string.toast_server_saved))
        }

        // ----- remote commands (receiver side) -----
        findViewById<Button>(R.id.btnCmdPing).setOnClickListener { Relay.sendCommand(this, "PING"); toast(getString(R.string.toast_cmd_ping)) }
        findViewById<Button>(R.id.btnCmdBattery).setOnClickListener { Relay.sendCommand(this, "BATTERY"); toast(getString(R.string.toast_cmd_battery)) }
        findViewById<Button>(R.id.btnCmdLocation).setOnClickListener { Relay.sendCommand(this, "LOCATION"); toast(getString(R.string.toast_cmd_location)) }
        findViewById<Button>(R.id.btnCmdRing).setOnClickListener { Relay.sendCommand(this, "RING"); toast(getString(R.string.toast_cmd_ring)) }
        findViewById<Button>(R.id.btnCmdStopRing).setOnClickListener { Relay.sendCommand(this, "STOP_RING"); toast(getString(R.string.toast_cmd_stop_ring)) }

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
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PhoneLink", "${item.title}\n${item.body}"))
            toast(getString(R.string.toast_copied))
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
        val q = etSearch.text.toString().trim().lowercase()
        if (q.isEmpty()) return inboxItems
        return inboxItems.filter {
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
            "$icon [${item.type}] ${item.title}\n${item.body}\n— ${item.from} • ${fmt.format(Date(item.time))}"
        }
        val display = if (rows.isEmpty()) listOf(getString(R.string.inbox_empty)) else rows
        lvInbox.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
