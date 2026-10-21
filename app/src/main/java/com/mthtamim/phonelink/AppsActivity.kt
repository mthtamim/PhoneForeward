package com.mthtamim.phonelink

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Smart Control app picker: choose, app by app, whose notifications this
 * phone forwards — instantly, bundled into the digest, or never. A top
 * switch flips on allowlist mode (only listed choices forward at all).
 * Tap a row to cycle its rule.
 */
class AppsActivity : LocalizedActivity() {

    private data class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var lv: ListView
    private lateinit var adapter: AppsAdapter
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        lv = findViewById(R.id.lvApps)
        adapter = AppsAdapter()
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, position, _ ->
            val e = shown.getOrNull(position) ?: return@setOnItemClickListener
            AppRules.setRule(this, e.pkg, AppRules.next(AppRules.rule(this, e.pkg)))
            adapter.notifyDataSetChanged()
        }

        val swAllow = findViewById<CompoundButton>(R.id.swAllowlist)
        swAllow.isChecked = AppRules.allowlistMode(this)
        swAllow.setOnCheckedChangeListener { _, checked -> AppRules.setAllowlistMode(this, checked) }

        findViewById<EditText>(R.id.etAppSearch).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        thread { loadApps() }
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = LinkedHashMap<String, Entry>()
        try {
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == packageName || found.containsKey(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                found[pkg] = Entry(pkg, label, icon)
            }
        } catch (_: Exception) { }
        val list = found.values.sortedBy { it.label.lowercase() }
        runOnUiThread {
            all = list
            applyFilter()
        }
    }

    private fun applyFilter() {
        shown = if (query.isEmpty()) all else all.filter {
            it.label.lowercase().contains(query) || it.pkg.lowercase().contains(query)
        }
        adapter.notifyDataSetChanged()
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@AppsActivity)
                .inflate(R.layout.item_app_rule, parent, false)
            val e = shown[position]
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(e.icon)
            view.findViewById<TextView>(R.id.tvAppName).text = e.label
            view.findViewById<TextView>(R.id.tvAppPkg).text = e.pkg
            val tvRule = view.findViewById<TextView>(R.id.tvAppRule)
            val rule = AppRules.rule(this@AppsActivity, e.pkg)
            tvRule.text = getString(when (rule) {
                AppRules.INSTANT -> R.string.rule_instant
                AppRules.DIGEST -> R.string.rule_digest
                AppRules.OFF -> R.string.rule_off
                else -> R.string.rule_default
            })
            tvRule.setTextColor(ContextCompat.getColor(this@AppsActivity, when (rule) {
                AppRules.INSTANT -> R.color.status_ok
                AppRules.DIGEST -> R.color.pair_code
                AppRules.OFF -> R.color.status_bad
                else -> R.color.md_outline
            }))
            return view
        }
    }
}
