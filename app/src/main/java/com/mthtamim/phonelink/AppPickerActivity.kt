package com.mthtamim.phonelink

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

/**
 * v2.18 App Locker picker — FULLY custom selection:
 *  - lists EVERY installed package (user apps by default; system
 *    apps appear behind the "Show system apps" toggle),
 *  - search over label + package,
 *  - a manual "add by package name" field for anything the list
 *    does not surface (validated: installed? excluded? duplicate?).
 * Hard safety exclusions never enter the results at all, and manual
 * entry of an excluded package is refused with a clear message;
 * PhoneLink + Settings are refused here too (Protect toggle only).
 * Checkbox toggles apply to the locked set immediately.
 */
class AppPickerActivity : LocalizedActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: PickerAdapter
    private var all: List<AppLocker.AppEntry> = emptyList()
    private var shown: List<AppLocker.AppEntry> = emptyList()
    private var query = ""
    private var showSystem = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        listView = findViewById(R.id.lvPickerApps)
        adapter = PickerAdapter()
        listView.adapter = adapter

        findViewById<View>(R.id.btnPickerBack).setOnClickListener { finish() }

        findViewById<EditText>(R.id.etPickerSearch)
            .addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim()?.lowercase().orEmpty()
                    applyFilter()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })

        findViewById<SwitchMaterial>(R.id.swShowSystem)
            .setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                applyFilter()
            }

        findViewById<View>(R.id.btnManualAdd).setOnClickListener {
            val et = findViewById<EditText>(R.id.etManualPkg)
            manualAdd(et.text.toString().trim())
            et.text.clear()
        }

        thread {
            // Exclusions are filtered BEFORE the list exists, so an
            // excluded package can never appear in the results.
            val apps = AppLocker.allInstalled(this)
                .filter { !AppLocker.pickerExcluded(this, it.pkg) }
            runOnUiThread {
                all = apps
                applyFilter()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLocker.managementOpen = true
    }

    override fun onPause() {
        AppLocker.managementOpen = false
        super.onPause()
    }

    private fun applyFilter() {
        shown = all.filter { e ->
            (showSystem || !e.system) &&
                (query.isEmpty() || e.label.lowercase().contains(query) ||
                    e.pkg.lowercase().contains(query))
        }
        adapter.notifyDataSetChanged()
    }

    /** Manual add-by-package-name, with the refusal ladder. */
    private fun manualAdd(pkg: String) {
        if (pkg.isEmpty()) return
        when {
            AppLocker.hardExcluded(this, pkg) ->
                toast(getString(R.string.alk_err_excluded))
            pkg == packageName || pkg == AppLocker.SETTINGS_PKG ->
                toast(getString(R.string.alk_err_protected))
            !AppLocker.isInstalled(this, pkg) ->
                toast(getString(R.string.alk_err_not_installed))
            AppLocker.isInSet(this, pkg) ->
                toast(getString(R.string.alk_err_already))
            else -> {
                AppLocker.addLocked(this, pkg)
                toast(getString(R.string.alk_added,
                    AppLocker.appLabel(this, pkg)))
                adapter.notifyDataSetChanged()
            }
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(pos: Int): Any = shown[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?,
                             parent: ViewGroup?): View {
            val e = shown[pos]
            val line = (convertView as? LinearLayout) ?: buildRow()
            val iv = line.getChildAt(0) as ImageView
            val texts = line.getChildAt(1) as LinearLayout
            val labelTv = texts.getChildAt(0) as TextView
            val pkgTv = texts.getChildAt(1) as TextView
            val check = line.getChildAt(2) as CheckBox
            val icon = AppLocker.appIcon(this@AppPickerActivity, e.pkg)
            if (icon != null) iv.setImageDrawable(icon)
            else iv.setImageResource(R.drawable.ic_grid)
            labelTv.text = e.label
            pkgTv.text = e.pkg
            check.setOnCheckedChangeListener(null)
            check.isChecked = AppLocker.isInSet(this@AppPickerActivity, e.pkg)
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!AppLocker.addLocked(this@AppPickerActivity, e.pkg)) {
                        check.isChecked = false
                        toast(getString(R.string.alk_err_excluded))
                    }
                } else {
                    AppLocker.removeLocked(this@AppPickerActivity, e.pkg)
                }
            }
            line.setOnClickListener { check.isChecked = !check.isChecked }
            return line
        }

        private fun buildRow(): LinearLayout {
            val line = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(9), dp(12), dp(9))
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            line.addView(ImageView(this@AppPickerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            })
            val texts = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(TextView(this@AppPickerActivity).apply {
                textSize = 15f
                setTextColor(themeOnSurface())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this@AppPickerActivity).apply {
                textSize = 12f
                setTextColor(themeOnSurfaceVariant())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.addView(texts)
            line.addView(CheckBox(this@AppPickerActivity))
            return line
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
