package com.mthtamim.phonelink

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browses the PEER phone's recent photos (fetched with LIST_PHOTOS) and
 * asks for the full file of any of them (FETCH_PHOTO). The photo itself
 * arrives in the inbox as a normal file message.
 */
class GalleryActivity : LocalizedActivity() {

    data class Photo(val id: Long, val name: String, val date: Long, val size: Long)

    private lateinit var lv: ListView
    private lateinit var tvStatus: TextView
    private var photos: List<Photo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        lv = findViewById(R.id.lvPhotos)
        tvStatus = findViewById(R.id.tvGalleryStatus)
        findViewById<Button>(R.id.btnGalleryRefresh).setOnClickListener {
            Relay.sendCommand(this, "LIST_PHOTOS")
            tvStatus.text = getString(R.string.gallery_loading)
        }
        lv.setOnItemClickListener { _, _, position, _ ->
            val p = photos.getOrNull(position) ?: return@setOnItemClickListener
            val extra = org.json.JSONObject().put("mediaId", p.id)
            Relay.sendCommand(this, "FETCH_PHOTO", null, extra)
            tvStatus.text = getString(R.string.gallery_fetching, p.name)
        }
        Relay.sendCommand(this, "LIST_PHOTOS")
        tvStatus.text = getString(R.string.gallery_loading)
    }

    override fun onResume() {
        super.onResume()
        loadCached()
    }

    private fun loadCached() {
        val prefs = getSharedPreferences("phonelink_state", Context.MODE_PRIVATE)
        val raw = prefs.getString("last_photos", "[]") ?: "[]"
        val at = prefs.getLong("last_photos_at", 0L)
        val list = mutableListOf<Photo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Photo(o.optLong("id"), o.optString("name"),
                    o.optLong("date"), o.optLong("size")))
            }
        } catch (_: Exception) { }
        photos = list
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val labels = list.map { p ->
            val kb = if (p.size > 0) " • ${p.size / 1024} KB" else ""
            "${p.name}\n${fmt.format(Date(p.date))}$kb"
        }
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        if (list.isNotEmpty()) {
            tvStatus.text = getString(R.string.gallery_count, list.size)
        } else if (at == 0L) {
            tvStatus.text = getString(R.string.gallery_loading)
        }
    }
}
