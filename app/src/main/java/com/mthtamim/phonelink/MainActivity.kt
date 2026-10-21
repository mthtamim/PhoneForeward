package com.mthtamim.phonelink

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPairCode: TextView
    private lateinit var tvQueue: TextView
    private lateinit var tvSenderSeen: TextView
    private lateinit var lvInbox: ListView
    private lateinit var etSearch: EditText

    private var inboxItems: List<History.Item> = emptyList()
    private var inboxReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPairCode = findViewById(R.id.tvPairCode)
        tvQueue = findViewById(R.id.tvQueue)
        tvSenderSeen = findViewById(R.id.tvSenderSeen)
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

        findViewById<Button>(R.id.btnApplyCode).setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length < 8) { toast("কোড কমপক্ষে ৮ অক্ষরের হতে হবে"); return@setOnClickListener }
            Config.setPairCode(this, code)
            refreshPairCode()
            LinkService.restart(this)
            toast("পেয়ার কোড সেট হয়েছে — সার্ভিস রিস্টার্ট হচ্ছে")
        }
        findViewById<Button>(R.id.btnNewCode).setOnClickListener {
            getSharedPreferences("phonelink_cfg", Context.MODE_PRIVATE).edit().remove("pair_code").apply()
            // clear stream watermarks so the new topic starts clean
            getSharedPreferences("phonelink_state", Context.MODE_PRIVATE).edit().clear().apply()
            Config.pairCode(this)
            refreshPairCode()
            LinkService.restart(this)
            toast("নতুন কোড তৈরি হয়েছে — এই কোডটাই আরেক ফোনে দাও")
        }
        findViewById<Button>(R.id.btnSaveName).setOnClickListener {
            Config.setDeviceName(this, etDeviceName.text.toString().ifBlank { android.os.Build.MODEL ?: "Phone" })
            toast("নাম সেভ হয়েছে")
        }

        // Modes
        bindModeSwitch(R.id.swSender, Config.isSender(this)) { Config.setSender(this, it); LinkService.restart(this) }
        bindModeSwitch(R.id.swReceiver, Config.isReceiver(this)) { Config.setReceiver(this, it); LinkService.restart(this) }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            if (!Config.isSender(this) && !Config.isReceiver(this)) {
                toast("আগে Sender বা Receiver — অন্তত একটা মোড চালু করো"); return@setOnClickListener
            }
            LinkService.start(this); refreshStatus(); toast("সার্ভিস চালু হচ্ছে")
        }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            LinkService.stop(this); toast("সার্ভিস বন্ধ করা হয়েছে")
        }

        // Permissions
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            toast("তালিকায় PhoneLink চালু করে দাও")
        }
        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestAllPermissions() }
        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // Feature toggles
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
            toast("ফিল্টার সেভ হয়েছে")
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Relay.send(this, Relay.TYPE_TEST, "🧪 টেস্ট মেসেজ",
                "${Config.deviceName(this)} থেকে টেস্ট — ফরোয়ার্ডিং কাজ করছে। ব্যাটারি: ${SystemReceiver.batteryPercent(this)}%")
            toast("টেস্ট পাঠানো হয়েছে")
            refreshStatus()
        }
        findViewById<Button>(R.id.btnSaveServer).setOnClickListener {
            var url = etServer.text.toString().trim()
            if (url.isEmpty()) url = "https://ntfy.sh"
            if (!url.startsWith("http")) url = "https://$url"
            Config.setServerUrl(this, url)
            LinkService.restart(this)
            toast("সার্ভার সেভ হয়েছে")
        }

        // Remote commands (receiver side)
        findViewById<Button>(R.id.btnCmdPing).setOnClickListener { Relay.sendCommand(this, "PING"); toast("পিং পাঠানো হয়েছে") }
        findViewById<Button>(R.id.btnCmdBattery).setOnClickListener { Relay.sendCommand(this, "BATTERY"); toast("ব্যাটারি কমান্ড পাঠানো হয়েছে") }
        findViewById<Button>(R.id.btnCmdLocation).setOnClickListener { Relay.sendCommand(this, "LOCATION"); toast("লোকেশন কমান্ড পাঠানো হয়েছে") }
        findViewById<Button>(R.id.btnCmdRing).setOnClickListener { Relay.sendCommand(this, "RING"); toast("রিং কমান্ড পাঠানো হয়েছে") }
        findViewById<Button>(R.id.btnCmdStopRing).setOnClickListener { Relay.sendCommand(this, "STOP_RING"); toast("রিং বন্ধের কমান্ড পাঠানো হয়েছে") }

        // Inbox
        findViewById<Button>(R.id.btnRefreshInbox).setOnClickListener { refreshInbox() }
        findViewById<Button>(R.id.btnClearInbox).setOnClickListener {
            History.clear(this); refreshInbox(); toast("ইনবক্স মোছা হয়েছে")
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
            toast("কপি হয়েছে")
        }

        refreshStatus()
        refreshInbox()
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

    private fun bindModeSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked); refreshStatus() }
    }

    private fun bindToggle(id: Int, key: String, initial: Boolean) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> Config.setToggle(this, key, checked) }
    }

    private fun refreshPairCode() {
        val code = Config.pairCode(this)
        tvPairCode.text = code.chunked(5).joinToString(" ")
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("মোড: ")
        sb.append(when {
            Config.isSender(this) && Config.isReceiver(this) -> "Sender + Receiver"
            Config.isSender(this) -> "Sender"
            Config.isReceiver(this) -> "Receiver"
            else -> "কিছুই চালু নেই"
        })
        sb.append("\nনোটিফিকেশন Access: ").append(if (notifAccessGranted()) "✅ দেওয়া আছে" else "❌ দেওয়া হয়নি")
        sb.append("\nSMS Permission: ").append(if (has(Manifest.permission.RECEIVE_SMS)) "✅" else "❌")
        sb.append("\nলোকেশন Permission: ").append(if (has(Manifest.permission.ACCESS_FINE_LOCATION)) "✅" else "❌")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.append("\nBattery Optimization থেকে বাদ: ")
            .append(if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "❌ (বন্ধ করা দরকার)")
        tvStatus.text = sb.toString()

        val q = Relay.queueSize(this)
        tvQueue.text = if (q > 0) "অফলাইন কিউতে $q টা মেসেজ জমা আছে — নেট এলেই পাঠিয়ে দেওয়া হবে।" else ""

        val seen = Config.lastSenderSeen(this)
        tvSenderSeen.text = if (seen == 0L) "Sender ফোনের খবর এখনো পাওয়া যায়নি"
        else {
            val mins = (System.currentTimeMillis() - seen) / 60000
            val whenText = when {
                mins < 1 -> "এইমাত্র"
                mins < 60 -> "$mins মিনিট আগে"
                mins < 1440 -> "${mins / 60} ঘণ্টা আগে"
                else -> "${mins / 1440} দিন আগে"
            }
            "Sender (${Config.lastSenderName(this)}) সর্বশেষ সক্রিয়: $whenText"
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
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH)
        val rows = filteredInbox().map { item ->
            val icon = when (item.type) {
                Relay.TYPE_SMS -> "✉️"; Relay.TYPE_NOTIF -> "🔔"; Relay.TYPE_CALL -> "📞"
                Relay.TYPE_BATTERY -> "🔋"; Relay.TYPE_APP -> "📦"; Relay.TYPE_LOCATION -> "📍"
                Relay.TYPE_SYSTEM -> "⚙️"; Relay.TYPE_TEST -> "🧪"; else -> "•"
            }
            "$icon [${item.type}] ${item.title}\n${item.body}\n— ${item.from} • ${fmt.format(Date(item.time))}"
        }
        lvInbox.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun notifAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun has(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.filter { !has(it) }.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        refreshStatus()
        toast("Permission আপডেট হয়েছে")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
