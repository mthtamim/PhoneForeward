package com.mthtamim.phonelink

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts

/** v2.7: Bluetooth offline share screen — pick a bonded device, then send
 *  the clipboard or a file; the switch keeps this phone receivable. */
class BtActivity : LocalizedActivity() {

    private var selected: BluetoothDevice? = null
    private lateinit var lv: ListView
    private lateinit var tvMine: TextView

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val dev = selected
        if (uri == null || dev == null) return@registerForActivityResult
        toast(getString(R.string.bt_sending))
        BtShare.sendFile(this, dev, uri) { ok ->
            runOnUiThread { toast(getString(
                if (ok) R.string.bt_sent else R.string.bt_failed)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bt)
        findViewById<View>(R.id.btnBtBack).setOnClickListener { finish() }
        tvMine = findViewById(R.id.tvBtMine)
        lv = findViewById(R.id.lvBtDevices)

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN), 701)
        }

        val ad = BtShare.adapter()
        tvMine.text = try {
            getString(R.string.bt_my_device, ad?.name ?: "—")
        } catch (_: Exception) { getString(R.string.bt_my_device, "—") }

        val sw = findViewById<Switch>(R.id.swBtReceive)
        sw.isChecked = Config.btReceiveOn(this)
        sw.setOnCheckedChangeListener { _, on ->
            Config.setToggle(this, "bt_receive_on", on)
            if (on) BtShare.startServer(this) else BtShare.stopServer()
        }

        findViewById<Button>(R.id.btnBtClip).setOnClickListener {
            val dev = selected
            if (dev == null) {
                toast(getString(R.string.bt_pick_device)); return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (text.isEmpty()) {
                toast(getString(R.string.bt_clip_empty)); return@setOnClickListener
            }
            toast(getString(R.string.bt_sending))
            BtShare.sendText(this, dev, text) { ok ->
                runOnUiThread { toast(getString(
                    if (ok) R.string.bt_sent else R.string.bt_failed)) }
            }
        }
        findViewById<Button>(R.id.btnBtFile).setOnClickListener {
            if (selected == null) {
                toast(getString(R.string.bt_pick_device)); return@setOnClickListener
            }
            pickFile.launch(arrayOf("*/*"))
        }
        renderDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int,
        permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701) renderDevices()
    }

    private fun renderDevices() {
        val devices = BtShare.bonded(this)
        if (devices.isEmpty()) {
            lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                listOf(getString(R.string.bt_no_bonded)))
            return
        }
        val labels = devices.map { d ->
            try { "${d.name ?: "?"} • ${d.address}" } catch (_: Exception) { d.address }
        }
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        lv.setOnItemClickListener { _, _, pos, _ ->
            selected = devices.getOrNull(pos)
            selected?.let {
                toast(getString(R.string.bt_selected,
                    try { it.name ?: it.address } catch (_: Exception) { it.address }))
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
