package com.mthtamim.phonelink

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.4 simple mode: a calm, big-button screen for a family member or an
 * emergency-only second phone. SOS, check-in, peer status — nothing
 * else to get lost in. A long-press on the bottom button opens the
 * full app (guarded by the app lock when one is set).
 */
class SimpleActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple)
        LinkService.start(this)

        val tvStatus = findViewById<TextView>(R.id.tvSimpleStatus)
        val peer = Peers.list(this).firstOrNull()
        tvStatus.text = if (peer == null) {
            getString(R.string.simple_no_peer)
        } else {
            val mins = (System.currentTimeMillis() - peer.seen) / 60000
            getString(R.string.simple_peer_line, peer.name,
                if (mins < 2) getString(R.string.simple_now) else "$mins",
                if (peer.battery >= 0) "${peer.battery}%" else "—")
        }

        findViewById<Button>(R.id.btnSimpleSos).setOnClickListener {
            Relay.send(this, Relay.TYPE_URGENT, "🚨 SOS",
                getString(R.string.siren_default))
            LocationHelper.fetchAndSend(this)
            Toast.makeText(this, getString(R.string.toast_sos_sent), Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnSimpleCheckin).setOnClickListener {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            Config.setLastCheckinDay(this, today)
            Relay.send(this, Relay.TYPE_INFO, "✅ চেক-ইন",
                "${Config.deviceName(this)} — " +
                    SimpleDateFormat("HH:mm", Locale.US).format(Date()))
            LocationHelper.fetchAndSend(this)
            Toast.makeText(this, getString(R.string.toast_checkin), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSimpleFull).setOnLongClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("simple_bypass", true))
            true
        }
    }
}
