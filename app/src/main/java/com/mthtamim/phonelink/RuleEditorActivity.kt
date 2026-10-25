package com.mthtamim.phonelink

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual rule editor (v2.17.2): the old Spinner pickers are gone.
 * Every trigger / constraint / action is an inset card; tapping its
 * type chip opens a grouped, searchable picker over the same
 * AutoCatalog lists, and the param field lives inside the card.
 * Capabilities are identical to the spinner editor — any number of
 * triggers (OR), constraints with an AND/OR mode and a per-item NOT
 * toggle, actions with an optional per-action condition — and the
 * saved rule JSON is byte-for-byte the same shape as before, so the
 * Automation engine and existing rules are untouched.
 */
class RuleEditorActivity : LocalizedActivity() {

    private lateinit var etName: EditText
    private lateinit var etCat: EditText
    private lateinit var trigContainer: LinearLayout
    private lateinit var consContainer: LinearLayout
    private lateinit var actContainer: LinearLayout
    private lateinit var cgConsMode: ChipGroup
    private var ruleId: String = ""

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- picker model ----------------

    private sealed class PickItem {
        data class Header(val label: String) : PickItem()
        data class Entry(val type: String, val label: String) : PickItem()
    }

    /** Group anchors: when the catalog walk reaches an anchor type,
     *  the items after it belong to that group (the catalog lists are
     *  already ordered in thematic rounds). */
    private fun groupAnchors(kind: String): List<Pair<String, Int>> = when (kind) {
        "t" -> listOf(
            "time_at" to R.string.pg_time,
            "battery_below" to R.string.pg_battery,
            "wifi_connected" to R.string.pg_conn,
            "screen_on" to R.string.pg_device,
            "app_launched" to R.string.pg_apps,
            "sms_received" to R.string.pg_msg,
            "geofence_in" to R.string.pg_loc,
            "battery_full" to R.string.pg_battery,
            "volume_up" to R.string.pg_device,
            "battery_saver_on" to R.string.pg_battery,
            "mobile_data_on" to R.string.pg_conn,
            "ui_click" to R.string.pg_apps,
            "mqtt_message" to R.string.pg_conn,
            "applock_failed" to R.string.pg_apps)
        "c" -> listOf(
            "time_range" to R.string.pg_time,
            "battery_above" to R.string.pg_battery,
            "wifi_ssid" to R.string.pg_conn,
            "location_in" to R.string.pg_loc,
            "screen" to R.string.pg_state,
            "music_playing" to R.string.pg_media,
            "date_range" to R.string.pg_time,
            "keyguard_locked" to R.string.pg_state,
            "mobile_data" to R.string.pg_conn,
            "notif_present" to R.string.pg_msg)
        else -> listOf(
            "notify_peer" to R.string.pg_notify,
            "torch" to R.string.pg_control,
            "http" to R.string.pg_files,
            "set_var" to R.string.pg_flow,
            "media_control" to R.string.pg_media,
            "gesture_tap" to R.string.pg_device,
            "call_number" to R.string.pg_comm,
            "loop_start" to R.string.pg_flow,
            "reboot_device" to R.string.pg_system,
            "hotspot_toggle" to R.string.pg_control,
            "lock_app" to R.string.pg_apps)
    }

    private fun typesFor(kind: String): List<String> = when (kind) {
        "t" -> AutoCatalog.triggers
        "c" -> AutoCatalog.constraints
        else -> AutoCatalog.actions
    }

    /** Full grouped item list for a kind, optionally filtered by a
     *  search query (matched against the label and the type id). */
    private fun pickItems(kind: String, query: String): List<PickItem> {
        val anchors = groupAnchors(kind).toMap()
        val q = query.trim().lowercase()
        val out = mutableListOf<PickItem>()
        var groupRes = R.string.pg_more
        var pendingHeader: PickItem.Header? = null
        for (type in typesFor(kind)) {
            // v2.18: if_else is added through its dedicated card
            // button (structured editor), not the raw picker.
            // v2.20: focuslock_control gets the same treatment,
            // and so do the Study Guard triggers (their card
            // builds the param from pickers, never free text).
            // v2.21: autotap_control gets the same treatment.
            if (kind == "a" && (type == "if_else" ||
                type == "focuslock_control" ||
                type == "autotap_control")) continue
            if (kind == "t" && (type == "study_guard_continuous" ||
                type == "study_guard_daily" ||
                type == "screen_on_continuous")) continue
            anchors[type]?.let { groupRes = it }
            val label = AutoCatalog.label(this, kind, type)
            if (q.isNotEmpty() && !label.lowercase().contains(q) &&
                !type.lowercase().contains(q)) continue
            val header = PickItem.Header(getString(groupRes))
            if (pendingHeader == null || pendingHeader != header) {
                pendingHeader = header
                out.add(header)
            }
            out.add(PickItem.Entry(type, label))
        }
        return out
    }

    private inner class PickAdapter(var items: List<PickItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): Any = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()
        override fun isEnabled(pos: Int): Boolean = items[pos] is PickItem.Entry
        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val tv = (convert as? TextView) ?: TextView(this@RuleEditorActivity)
            when (val item = items[pos]) {
                is PickItem.Header -> {
                    tv.text = item.label
                    tv.textSize = 12.5f
                    tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                    tv.setTextColor(ContextCompat.getColor(
                        this@RuleEditorActivity, R.color.pl_mint))
                    tv.setPadding(dp(20), dp(14), dp(20), dp(4))
                }
                is PickItem.Entry -> {
                    tv.text = item.label
                    tv.textSize = 15f
                    tv.setTypeface(tv.typeface, android.graphics.Typeface.NORMAL)
                    tv.setTextColor(ContextCompat.getColor(
                        this@RuleEditorActivity, R.color.md_onSurface))
                    tv.setPadding(dp(20), dp(11), dp(20), dp(11))
                }
            }
            return tv
        }
    }

    /** The visual picker: search box + grouped list. [allowNone]
     *  adds the "no condition" entry (action conditions only). */
    private fun showPicker(kind: String, allowNone: Boolean,
                           onPick: (String) -> Unit) {
        val titleRes = when (kind) {
            "t" -> R.string.picker_trigger
            "c" -> if (allowNone) R.string.picker_cond else R.string.picker_constraint
            else -> R.string.picker_action
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        // v2.17.3: picker search matches the preview search box —
        // filled rounded box + leading search icon, no underline.
        val search = EditText(this).apply {
            hint = getString(R.string.picker_search_hint)
            setSingleLine()
            setBackgroundResource(R.drawable.bg_search)
            setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5).toInt())
            dividerHeight = 0
        }
        box.addView(search)
        box.addView(list)
        val noneItem = PickItem.Entry("", getString(R.string.auto_cond_none))
        fun build(q: String): List<PickItem> {
            val items = pickItems(kind, q)
            return if (allowNone && q.isEmpty()) listOf(noneItem) + items else items
        }
        val adapter = PickAdapter(build(""))
        list.adapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(box)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = adapter.items[pos]
            if (item is PickItem.Entry) {
                onPick(item.type)
                dialog.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                adapter.items = build(s?.toString() ?: "")
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        dialog.show()
    }

    // ---------------- editor rows ----------------

    private inner class Row(val kind: String, type: String, param: String) {
        var type: String = type
        val root = LinearLayout(this@RuleEditorActivity)
        val chipType = TextView(this@RuleEditorActivity)
        val etParam = EditText(this@RuleEditorActivity)
        var swNegate: SwitchMaterial? = null
        var condType: String = ""
        var chipCond: TextView? = null
        var etCondParam: EditText? = null
        // v2.21: call_number rows get SIM chips under the number
        // field (picker, never typed). simSel is the SIM source
        // of truth; toJson composes it into the param.
        var simGroup: ChipGroup? = null
        var simSel: String = "default"
        private val simChips = HashMap<Int, String>()

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }

            val line1 = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            chipType.setBackgroundResource(R.drawable.bg_action_ghost)
            chipType.setPadding(dp(12), dp(6), dp(12), dp(6))
            chipType.textSize = 13.5f
            chipType.setTypeface(chipType.typeface, android.graphics.Typeface.BOLD)
            chipType.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            chipType.setOnClickListener {
                showPicker(kind, false) { picked ->
                    this.type = picked
                    refreshChip()
                }
            }
            line1.addView(chipType)
            line1.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                // v2.18: detach from the ACTUAL parent, so rows that
                // live inside a group / If-Else card remove correctly
                // too (top-level rows behave exactly as before).
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(line1)

            etParam.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
            etParam.setText(param)
            etParam.textSize = 13.5f
            etParam.setSingleLine()
            root.addView(etParam)

            if (kind == "c") {
                swNegate = SwitchMaterial(this@RuleEditorActivity).apply {
                    text = getString(R.string.auto_negate)
                    textSize = 13.5f
                }
                root.addView(swNegate)
            }
            if (kind == "a") {
                val line2 = LinearLayout(this@RuleEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, dp(6), 0, 0) }
                }
                val cc = TextView(this@RuleEditorActivity).apply {
                    setBackgroundResource(R.drawable.bg_action_ghost)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    textSize = 12.5f
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                    setOnClickListener {
                        showPicker("c", true) { picked ->
                            condType = picked
                            refreshCondChip()
                        }
                    }
                }
                chipCond = cc
                line2.addView(cc)
                val et = EditText(this@RuleEditorActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins(dp(8), 0, 0, 0) }
                    textSize = 13f
                    hint = getString(R.string.auto_param_hint)
                    setSingleLine()
                }
                etCondParam = et
                line2.addView(et)
                root.addView(line2)
                refreshCondChip()
            }
            refreshChip()
        }

        private fun refreshChip() {
            chipType.text = AutoCatalog.label(this@RuleEditorActivity, kind, type)
            etParam.hint = AutoCatalog.hint(this@RuleEditorActivity, kind, type)
            // v2.18: the App Locker actions take a package name —
            // tapping the param field opens the installed-apps
            // picker instead of the keyboard (same treatment the
            // locker's own screens give package entry).
            if (kind == "a" && (type == "lock_app" || type == "unlock_app")) {
                etParam.isFocusable = false
                etParam.isFocusableInTouchMode = false
                etParam.setOnClickListener { showAppParamPicker() }
            } else {
                etParam.isFocusable = true
                etParam.isFocusableInTouchMode = true
                etParam.setOnClickListener(null)
            }
            // v2.21: call_number rows get SIM chips under the
            // number field (picker, never typed). A loaded param
            // is split back here through the engine's own parser,
            // so the field never shows the "|sim=.." suffix.
            if (kind == "a" && type == "call_number") {
                ensureSimGroup()
                val parsed =
                    Automation.parseCallParam(etParam.text.toString())
                if (etParam.text.toString().contains("|sim="))
                    etParam.setText(parsed.first)
                simSel = parsed.second
                val want = simChips.entries
                    .firstOrNull { it.value == simSel }?.key
                if (want != null) simGroup?.check(want)
                simGroup?.visibility = View.VISIBLE
            } else {
                simGroup?.visibility = View.GONE
            }
        }

        /** Builds the SIM chip row for call_number rows (once). */
        private fun ensureSimGroup() {
            if (simGroup != null) return
            val cg = ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, 0) }
            }
            val defs = listOf(
                "default" to R.string.call_sim_default,
                "sim1" to R.string.call_sim_1,
                "sim2" to R.string.call_sim_2)
            for ((simId, labelRes) in defs) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelRes)
                    isCheckable = true
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) simSel = simId
                    }
                }
                simChips[chip.id] = simId
                cg.addView(chip)
            }
            simGroup = cg
            root.addView(cg, 2)
        }

        /** Installed-apps list for package-valued action params. */
        private fun showAppParamPicker() {
            val apps = AppLocker.launchableApps(this@RuleEditorActivity)
            if (apps.isEmpty()) return
            val labels = apps.map { it.label + "\n" + it.pkg }.toTypedArray()
            AlertDialog.Builder(this@RuleEditorActivity)
                .setTitle(AutoCatalog.label(this@RuleEditorActivity, kind, type))
                .setItems(labels) { _, which ->
                    etParam.setText(apps[which].pkg)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        private fun refreshCondChip() {
            chipCond?.text = if (condType.isEmpty())
                getString(R.string.auto_cond_none)
            else AutoCatalog.label(this@RuleEditorActivity, "c", condType)
        }

        fun toJson(): JSONObject {
            var p = etParam.text.toString().trim()
            // v2.21: call_number saves the SIM chip, never a
            // typed suffix — Default saves the bare number, so
            // old builds read new rules unchanged.
            if (kind == "a" && type == "call_number") {
                val num = Automation.parseCallParam(p).first
                p = if (simSel == "default") num else "$num|sim=$simSel"
            }
            val o = JSONObject().put("type", type).put("param", p)
            if (kind == "c" && swNegate?.isChecked == true)
                o.put("negate", true)
            if (kind == "a" && condType.isNotEmpty()) {
                o.put("cond", JSONObject()
                    .put("type", condType)
                    .put("param",
                        etCondParam?.text?.toString()?.trim() ?: ""))
            }
            return o
        }

        fun loadExtra(o: JSONObject) {
            if (kind == "c") swNegate?.isChecked = o.optBoolean("negate")
            if (kind == "a") {
                val cond = o.optJSONObject("cond") ?: return
                condType = cond.optString("type")
                etCondParam?.setText(cond.optString("param"))
                refreshCondChip()
            }
        }
    }

    // ---------------- v2.18 (WS3): condition group card ----------------

    /** A condition GROUP: its own AND/OR mode chip and its own list
     *  of condition rows (one nesting level). Serializes to a single
     *  constraint item {type:"group", param:"{mode, items}"}; the
     *  engine evaluates it recursively via evalConstraintList. */
    private inner class GroupRow {
        val root = LinearLayout(this@RuleEditorActivity)
        private val inner = LinearLayout(this@RuleEditorActivity)
        val rows = mutableListOf<Row>()
        var modeAny = false
        private val chipMode = TextView(this@RuleEditorActivity)

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = getString(R.string.group_title)
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            chipMode.setBackgroundResource(R.drawable.bg_action_ghost)
            chipMode.setPadding(dp(12), dp(6), dp(12), dp(6))
            chipMode.textSize = 12.5f
            chipMode.setOnClickListener {
                modeAny = !modeAny
                refreshMode()
            }
            head.addView(chipMode)
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)
            inner.orientation = LinearLayout.VERTICAL
            root.addView(inner)
            root.addView(Button(this@RuleEditorActivity).apply {
                text = getString(R.string.auto_add_constraint)
                setOnClickListener { addCond("", "", null) }
            })
            refreshMode()
        }

        private fun refreshMode() {
            chipMode.text = getString(if (modeAny) R.string.auto_cons_any
                else R.string.auto_cons_all)
        }

        fun addCond(type: String, param: String, extra: JSONObject?) {
            val row = Row("c", type.ifEmpty { typesFor("c").first() }, param)
            rows.add(row)
            inner.addView(row.root)
            if (extra != null) row.loadExtra(extra)
        }

        fun toJson(): JSONObject {
            val items = JSONArray()
            for (r in rows) if (r.root.parent != null) items.put(r.toJson())
            val g = JSONObject()
                .put("mode", if (modeAny) "any" else "all")
                .put("items", items)
            return JSONObject().put("type", "group")
                .put("param", g.toString())
        }

        fun load(o: JSONObject) {
            val g = try { JSONObject(o.optString("param")) }
                catch (e: Exception) { return }
            modeAny = g.optString("mode") == "any"
            refreshMode()
            val items = g.optJSONArray("items") ?: return
            for (i in 0 until items.length()) {
                val c = items.optJSONObject(i) ?: continue
                if (c.optString("type") == "group") continue // one level
                addCond(c.optString("type"), c.optString("param"), c)
            }
        }
    }

    // ---------------- v2.18 (WS3): If/Else action card ----------------

    /** The structured If/Else step: a flat condition list (with the
     *  same AND/OR chip) plus then/else action lists, one level deep.
     *  Serializes to one action {type:"if_else", param: JSON
     *  {mode, constraints, then, else}} for the engine interpreter. */
    private inner class IfElseRow {
        val root = LinearLayout(this@RuleEditorActivity)
        val condRows = mutableListOf<Row>()
        val thenRows = mutableListOf<Row>()
        val elseRows = mutableListOf<Row>()
        var modeAny = false
        private val chipMode = TextView(this@RuleEditorActivity)
        private val condBox = LinearLayout(this@RuleEditorActivity)
        private val thenBox = LinearLayout(this@RuleEditorActivity)
        private val elseBox = LinearLayout(this@RuleEditorActivity)

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = AutoCatalog.label(this@RuleEditorActivity,
                    "a", "if_else")
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            chipMode.setBackgroundResource(R.drawable.bg_action_ghost)
            chipMode.setPadding(dp(12), dp(6), dp(12), dp(6))
            chipMode.textSize = 12.5f
            chipMode.setOnClickListener {
                modeAny = !modeAny
                refreshMode()
            }
            head.addView(chipMode)
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)
            section(R.string.ifelse_when, condBox) {
                addCond("", "", null) }
            section(R.string.ifelse_then, thenBox) {
                addThen("", "", null) }
            section(R.string.ifelse_else, elseBox) {
                addElse("", "", null) }
            refreshMode()
        }

        private fun section(labelRes: Int, box: LinearLayout,
                            onAdd: () -> Unit) {
            root.addView(TextView(this@RuleEditorActivity).apply {
                text = getString(labelRes)
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(10), 0, 0)
            })
            box.orientation = LinearLayout.VERTICAL
            root.addView(box)
            root.addView(Button(this@RuleEditorActivity).apply {
                text = getString(
                    if (box === condBox) R.string.auto_add_constraint
                    else R.string.auto_add_action)
                setOnClickListener { onAdd() }
            })
        }

        private fun refreshMode() {
            chipMode.text = getString(if (modeAny) R.string.auto_cons_any
                else R.string.auto_cons_all)
        }

        fun addCond(type: String, param: String, extra: JSONObject?) {
            val row = Row("c", type.ifEmpty { typesFor("c").first() }, param)
            condRows.add(row)
            condBox.addView(row.root)
            if (extra != null) row.loadExtra(extra)
        }

        fun addThen(type: String, param: String, extra: JSONObject?) {
            val row = Row("a",
                type.ifEmpty { typesFor("a").first() }, param)
            thenRows.add(row)
            thenBox.addView(row.root)
            if (extra != null) row.loadExtra(extra)
        }

        fun addElse(type: String, param: String, extra: JSONObject?) {
            val row = Row("a",
                type.ifEmpty { typesFor("a").first() }, param)
            elseRows.add(row)
            elseBox.addView(row.root)
            if (extra != null) row.loadExtra(extra)
        }

        fun toJson(): JSONObject {
            val conds = JSONArray()
            for (r in condRows) if (r.root.parent != null)
                conds.put(r.toJson())
            val then = JSONArray()
            for (r in thenRows) if (r.root.parent != null)
                then.put(r.toJson())
            val els = JSONArray()
            for (r in elseRows) if (r.root.parent != null)
                els.put(r.toJson())
            val spec = JSONObject()
                .put("mode", if (modeAny) "any" else "all")
                .put("constraints", conds)
                .put("then", then)
                .put("else", els)
            return JSONObject().put("type", "if_else")
                .put("param", spec.toString())
        }

        fun load(o: JSONObject) {
            val spec = try { JSONObject(o.optString("param")) }
                catch (e: Exception) { return }
            modeAny = spec.optString("mode") == "any"
            refreshMode()
            val conds = spec.optJSONArray("constraints")
            if (conds != null) for (i in 0 until conds.length()) {
                val c = conds.optJSONObject(i) ?: continue
                if (c.optString("type") == "group") continue
                addCond(c.optString("type"), c.optString("param"), c)
            }
            val then = spec.optJSONArray("then")
            if (then != null) for (i in 0 until then.length()) {
                val a = then.optJSONObject(i) ?: continue
                addThen(a.optString("type"), a.optString("param"), a)
            }
            val els = spec.optJSONArray("else")
            if (els != null) for (i in 0 until els.length()) {
                val a = els.optJSONObject(i) ?: continue
                addElse(a.optString("type"), a.optString("param"), a)
            }
        }
    }

    // ---------------- v2.20: FocusLock control card ----------------

    /** The picker-built FocusLock step: command chips, the
     *  installed-apps picker for the target app, duration chips
     *  with a custom-minutes field, and a preset-name field —
     *  the user never types the contract string. Serializes to
     *  one action {type:"focuslock_control", param:
     *  "cmd=..|app=..|mins=..|preset=.."} with only the keys the
     *  chosen command needs, and parses back through the SAME
     *  parser the engine uses (Automation.parseFocusLockParam),
     *  so the round-trip cannot drift. The token is NOT part of
     *  the param: the engine reads the one-time save at run
     *  time. Like the plain Row, the step can carry a
     *  per-action condition. */
    private inner class FocusLockRow {
        val root = LinearLayout(this@RuleEditorActivity)
        private var cmd: String = "block_app"
        private var appPkg: String = ""
        private var mins: Int? = 60
        // v2.20 Study Guard: mins=day — "দিনের বাকি সময়", the
        // duration chip that blocks until local midnight (only
        // offered for block_app). Mutually exclusive with mins.
        private var minsDay: Boolean = false
        private var dayChip: Chip? = null
        private var condType: String = ""
        private var chipCond: TextView? = null
        private var etCondParam: EditText? = null
        private val cmdChips = HashMap<Int, String>()
        private val durChips = HashMap<Int, Int>()
        private lateinit var cgCmd: ChipGroup
        private lateinit var cgDur: ChipGroup
        private lateinit var tvAppValue: TextView
        private lateinit var etCustomMins: EditText
        private lateinit var etPreset: EditText
        private lateinit var appSection: LinearLayout
        private lateinit var durSection: LinearLayout
        private lateinit var presetSection: LinearLayout
        private var syncing = false

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }

            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = AutoCatalog.label(this@RuleEditorActivity,
                    "a", "focuslock_control")
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)

            // ---- command chips ----
            cgCmd = ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, 0) }
            }
            val cmdDefs = listOf(
                "block_app" to R.string.fl_cmd_block_app,
                "lock_full" to R.string.fl_cmd_lock_full,
                "focus" to R.string.fl_cmd_focus,
                "unblock" to R.string.fl_cmd_unblock)
            for ((cmdId, labelRes) in cmdDefs) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelRes)
                    isCheckable = true
                }
                cmdChips[chip.id] = cmdId
                cgCmd.addView(chip)
                if (cmdId == cmd) cgCmd.check(chip.id)
            }
            root.addView(cgCmd)

            // ---- app picker section ----
            appSection = section(R.string.fl_app_label)
            tvAppValue = TextView(this@RuleEditorActivity).apply {
                setBackgroundResource(R.drawable.bg_action_ghost)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
                setOnClickListener { showAppPicker() }
            }
            appSection.addView(tvAppValue)

            // ---- duration section ----
            durSection = section(R.string.fl_duration_label)
            cgDur = ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            for (v in listOf(15, 30, 60, 120)) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = v.toString()
                    isCheckable = true
                }
                durChips[chip.id] = v
                cgDur.addView(chip)
            }
            // v2.20 Study Guard: the "rest of today" chip —
            // stored as mins=day, resolved at run time. Only
            // visible for block_app (refreshSections).
            dayChip = Chip(this@RuleEditorActivity).apply {
                id = View.generateViewId()
                text = getString(R.string.fl_mins_day)
                isCheckable = true
            }
            cgDur.addView(dayChip)
            durSection.addView(cgDur)
            etCustomMins = EditText(this@RuleEditorActivity).apply {
                hint = getString(R.string.fl_custom_mins_hint)
                textSize = 13.5f
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            durSection.addView(etCustomMins)

            // ---- preset section ----
            presetSection = section(R.string.fl_preset_label)
            etPreset = EditText(this@RuleEditorActivity).apply {
                textSize = 13.5f
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            presetSection.addView(etPreset)

            // ---- per-action condition (same pattern as Row) ----
            val line2 = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            val cc = TextView(this@RuleEditorActivity).apply {
                setBackgroundResource(R.drawable.bg_action_ghost)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                setOnClickListener {
                    showPicker("c", true) { picked ->
                        condType = picked
                        refreshCondChip()
                    }
                }
            }
            chipCond = cc
            line2.addView(cc)
            val et = EditText(this@RuleEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(8), 0, 0, 0) }
                textSize = 13f
                hint = getString(R.string.auto_param_hint)
                setSingleLine()
            }
            etCondParam = et
            line2.addView(et)
            root.addView(line2)

            // ---- listeners (attached last, after the initial
            // chip state above, so setup never self-triggers) ----
            cgCmd.setOnCheckedStateChangeListener { _, checkedIds ->
                if (!syncing) {
                    val picked = checkedIds.firstOrNull()
                        ?.let { cmdChips[it] }
                    if (picked != null && picked != cmd) {
                        cmd = picked
                        // Switching commands resets the duration
                        // to that command's sane default: a plain
                        // number for block/lock, NO override for a
                        // preset (FocusLock's own preset time).
                        mins = if (cmd == "focus") null else 60
                        minsDay = false
                        refreshDurationUi()
                        refreshSections()
                    }
                }
            }
            cgDur.setOnCheckedStateChangeListener { _, checkedIds ->
                if (!syncing) {
                    val id = checkedIds.firstOrNull()
                    when {
                        id != null && id == dayChip?.id -> {
                            minsDay = true
                            syncing = true
                            etCustomMins.setText("")
                            syncing = false
                        }
                        id != null && durChips.containsKey(id) -> {
                            minsDay = false
                            mins = durChips[id]
                            syncing = true
                            etCustomMins.setText("")
                            syncing = false
                        }
                        id == null -> {
                            // Chip unchecked by hand (the custom
                            // field's own path runs under syncing).
                            minsDay = false
                            mins = null
                        }
                    }
                }
            }
            etCustomMins.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val v = s?.toString()?.trim()?.toIntOrNull()
                    if (v != null && v > 0) {
                        mins = v
                        minsDay = false
                        syncing = true
                        cgDur.clearCheck()
                        syncing = false
                    }
                }
                override fun beforeTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
            })

            refreshAppValue()
            refreshDurationUi()
            refreshSections()
            refreshCondChip()
        }

        private fun section(labelRes: Int): LinearLayout {
            val box = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            box.addView(TextView(this@RuleEditorActivity).apply {
                text = getString(labelRes)
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            root.addView(box)
            return box
        }

        private fun refreshSections() {
            appSection.visibility =
                if (cmd == "block_app" || cmd == "unblock")
                    View.VISIBLE else View.GONE
            durSection.visibility =
                if (cmd == "unblock") View.GONE else View.VISIBLE
            presetSection.visibility =
                if (cmd == "focus") View.VISIBLE else View.GONE
            // "Rest of today" only makes sense for a timed block.
            dayChip?.visibility =
                if (cmd == "block_app") View.VISIBLE else View.GONE
        }

        private fun refreshAppValue() {
            tvAppValue.text = when {
                appPkg.isEmpty() -> getString(R.string.fl_pick_app)
                appPkg == Automation.SG_TRIGGER_APP ->
                    getString(R.string.fl_app_trigger)
                else -> AppLocker.appLabel(
                    this@RuleEditorActivity, appPkg)
            }
        }

        private fun refreshDurationUi() {
            syncing = true
            cgDur.clearCheck()
            if (minsDay) {
                dayChip?.let { cgDur.check(it.id) }
                etCustomMins.setText("")
            } else {
                var onChip = false
                for ((id, v) in durChips) {
                    if (mins == v) { cgDur.check(id); onChip = true }
                }
                etCustomMins.setText(
                    if (mins != null && !onChip) mins.toString()
                    else "")
            }
            syncing = false
        }

        /** Installed-apps list — the same picker the App Locker
         *  action params use (name + package, pick by name),
         *  with ONE special first entry (v2.20 Study Guard):
         *  "ট্রিগার করা অ্যাপ" — the app that fired the rule,
         *  resolved by the engine at run time. */
        private fun showAppPicker() {
            val apps = AppLocker.launchableApps(this@RuleEditorActivity)
            val labels = (listOf(getString(R.string.fl_app_trigger)) +
                apps.map { it.label + "\n" + it.pkg }).toTypedArray()
            AlertDialog.Builder(this@RuleEditorActivity)
                .setTitle(getString(R.string.fl_app_label))
                .setItems(labels) { _, which ->
                    appPkg = if (which == 0)
                        Automation.SG_TRIGGER_APP
                    else apps[which - 1].pkg
                    refreshAppValue()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        private fun refreshCondChip() {
            chipCond?.text = if (condType.isEmpty())
                getString(R.string.auto_cond_none)
            else AutoCatalog.label(this@RuleEditorActivity, "c", condType)
        }

        fun toJson(): JSONObject {
            val parts = mutableListOf("cmd=$cmd")
            if ((cmd == "block_app" || cmd == "unblock") &&
                appPkg.isNotBlank())
                parts.add("app=$appPkg")
            if (cmd != "unblock") {
                if (minsDay && cmd == "block_app")
                    parts.add("mins=day")
                else if (mins != null) parts.add("mins=$mins")
            }
            if (cmd == "focus") {
                // '|' and '=' are the param's own separators —
                // they can never appear inside a preset name.
                val p = etPreset.text.toString().trim()
                    .replace("|", " ").replace("=", " ")
                if (p.isNotEmpty()) parts.add("preset=$p")
            }
            val o = JSONObject().put("type", "focuslock_control")
                .put("param", parts.joinToString("|"))
            if (condType.isNotEmpty()) {
                o.put("cond", JSONObject()
                    .put("type", condType)
                    .put("param",
                        etCondParam?.text?.toString()?.trim() ?: ""))
            }
            return o
        }

        fun load(o: JSONObject) {
            val kv = Automation.parseFocusLockParam(o.optString("param"))
            val c = kv["cmd"] ?: "block_app"
            cmd = if (c == "block_app" || c == "lock_full" ||
                c == "focus" || c == "unblock") c else "block_app"
            appPkg = kv["app"] ?: ""
            minsDay = kv["mins"] == "day"
            mins = if (minsDay) null else kv["mins"]?.toIntOrNull()
            syncing = true
            cgCmd.clearCheck()
            for ((id, cmdId) in cmdChips)
                if (cmdId == cmd) cgCmd.check(id)
            syncing = false
            etPreset.setText(kv["preset"] ?: "")
            refreshAppValue()
            refreshDurationUi()
            refreshSections()
            val cond = o.optJSONObject("cond") ?: return
            condType = cond.optString("type")
            etCondParam?.setText(cond.optString("param"))
            refreshCondChip()
        }
    }

    // ---------------- v2.21: AutoTap step card ----------------

    /** The picker-built AutoTap step: command chips (script
     *  চালু / script থামাও) and a script-name field (start
     *  only — stop needs no name). Serializes to one action
     *  {type:"autotap_control", param:"cmd=start|name=.."} or
     *  {type:"autotap_control", param:"cmd=stop"} and parses
     *  back through the SAME parser the engine uses
     *  (Automation.parseFocusLockParam — the same key=value
     *  '|' form), so the round-trip cannot drift. The script
     *  name must match AutoTap's saved script exactly; a
     *  renamed script silently matches nothing on AutoTap's
     *  side. Like the plain Row, the step can carry a
     *  per-action condition. */
    private inner class AutoTapRow {
        val root = LinearLayout(this@RuleEditorActivity)
        private var cmd: String = "start"
        private var condType: String = ""
        private var chipCond: TextView? = null
        private var etCondParam: EditText? = null
        private val cmdChips = HashMap<Int, String>()
        private lateinit var cgCmd: ChipGroup
        private lateinit var etName: EditText
        private lateinit var nameSection: LinearLayout
        private var syncing = false

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }

            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = AutoCatalog.label(this@RuleEditorActivity,
                    "a", "autotap_control")
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)

            // ---- command chips ----
            cgCmd = ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, 0) }
            }
            for ((cmdId, labelRes) in listOf(
                "start" to R.string.atap_cmd_start,
                "stop" to R.string.atap_cmd_stop)) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelRes)
                    isCheckable = true
                }
                cmdChips[chip.id] = cmdId
                cgCmd.addView(chip)
                if (cmdId == cmd) cgCmd.check(chip.id)
            }
            root.addView(cgCmd)

            // ---- script name section (start only) ----
            nameSection = section(R.string.atap_name_label)
            etName = EditText(this@RuleEditorActivity).apply {
                textSize = 13.5f
                setSingleLine()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            nameSection.addView(etName)

            // ---- per-action condition (same pattern as Row) ----
            val line2 = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            val cc = TextView(this@RuleEditorActivity).apply {
                setBackgroundResource(R.drawable.bg_action_ghost)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                setOnClickListener {
                    showPicker("c", true) { picked ->
                        condType = picked
                        refreshCondChip()
                    }
                }
            }
            chipCond = cc
            line2.addView(cc)
            val et = EditText(this@RuleEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(8), 0, 0, 0) }
                textSize = 13f
                hint = getString(R.string.auto_param_hint)
                setSingleLine()
            }
            etCondParam = et
            line2.addView(et)
            root.addView(line2)

            // ---- listeners (attached last, after the initial
            // chip state above, so setup never self-triggers) ----
            cgCmd.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    val picked = ids.firstOrNull()
                        ?.let { cmdChips[it] }
                    if (picked != null && picked != cmd) {
                        cmd = picked
                        refreshSections()
                    }
                }
            }

            refreshSections()
            refreshCondChip()
        }

        private fun section(labelRes: Int): LinearLayout {
            val box = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            box.addView(TextView(this@RuleEditorActivity).apply {
                if (labelRes != 0) text = getString(labelRes)
                textSize = 12.5f
                setTypeface(typeface,
                    android.graphics.Typeface.BOLD)
            })
            root.addView(box)
            return box
        }

        private fun refreshSections() {
            nameSection.visibility =
                if (cmd == "start") View.VISIBLE else View.GONE
        }

        private fun refreshCondChip() {
            chipCond?.text = if (condType.isEmpty())
                getString(R.string.auto_cond_none)
            else AutoCatalog.label(this@RuleEditorActivity, "c", condType)
        }

        fun toJson(): JSONObject {
            val parts = mutableListOf("cmd=$cmd")
            if (cmd == "start") {
                // '|' and '=' are the param's own separators —
                // they can never appear inside a script name.
                val n = etName.text.toString().trim()
                    .replace("|", " ").replace("=", " ")
                if (n.isNotEmpty()) parts.add("name=$n")
            }
            val o = JSONObject().put("type", "autotap_control")
                .put("param", parts.joinToString("|"))
            if (condType.isNotEmpty()) {
                o.put("cond", JSONObject()
                    .put("type", condType)
                    .put("param",
                        etCondParam?.text?.toString()?.trim() ?: ""))
            }
            return o
        }

        fun load(o: JSONObject) {
            val kv = Automation.parseFocusLockParam(
                o.optString("param"))
            val c = kv["cmd"] ?: "start"
            cmd = if (c == "stop") "stop" else "start"
            syncing = true
            cgCmd.clearCheck()
            for ((id, cmdId) in cmdChips)
                if (cmdId == cmd) cgCmd.check(id)
            syncing = false
            etName.setText(kv["name"] ?: "")
            refreshSections()
            val cond = o.optJSONObject("cond") ?: return
            condType = cond.optString("type")
            etCondParam?.setText(cond.optString("param"))
            refreshCondChip()
        }
    }

    // ---------------- v2.20: Study Guard trigger card ----------------

    /** The picker-built Study Guard trigger: mode chips
     *  (একটানা ব্যবহার / দৈনিক সীমা), a study-list chooser
     *  backed by the ONE list manager (StudyGuardUi — the same
     *  manager the Automation screen opens), the installed-apps
     *  picker for the daily single-app mode, duration chips with
     *  a custom-minutes field, warn-before chips, and a Usage
     *  Access hint while that permission is missing. Serializes
     *  to {type:"study_guard_continuous", param:
     *  "list=..|mins=..|warn=.."} or {type:"study_guard_daily",
     *  param:"app=..|limit=..|warn=.." or
     *  "list=..|limit=..|warn=.."}, and parses back through the
     *  SAME parser the engine uses
     *  (Automation.parseStudyGuardParam). With no list yet, the
     *  list field guides creation instead of dead-ending. */
    private inner class StudyGuardRow {
        val root = LinearLayout(this@RuleEditorActivity)
        private var mode: String = "continuous"
        private var listId: String = ""
        private var dailyAppMode: Boolean = false
        private var appPkg: String = ""
        private var mins: Int = 30
        private var warn: Int = 5
        private var syncing = false
        private val modeChips = HashMap<Int, String>()
        private val subChips = HashMap<Int, Boolean>()
        private val durChips = HashMap<Int, Int>()
        private val warnChips = HashMap<Int, Int>()
        private lateinit var tvTitle: TextView
        private lateinit var cgMode: ChipGroup
        private lateinit var cgSub: ChipGroup
        private lateinit var listSection: LinearLayout
        private lateinit var tvListValue: TextView
        private lateinit var appSection: LinearLayout
        private lateinit var tvAppValue: TextView
        private lateinit var tvDurLabel: TextView
        private lateinit var cgDur: ChipGroup
        private lateinit var etCustomMins: EditText
        private lateinit var cgWarn: ChipGroup
        private lateinit var tvPermHint: TextView
        private var durBuiltFor: String = ""

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }

            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            tvTitle = TextView(this@RuleEditorActivity).apply {
                textSize = 13.5f
                setTypeface(typeface,
                    android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            head.addView(tvTitle)
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)

            // ---- mode chips ----
            cgMode = chipGroup()
            for ((m, labelRes) in listOf(
                "continuous" to R.string.sg_mode_continuous,
                "daily" to R.string.sg_mode_daily)) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelRes)
                    isCheckable = true
                }
                modeChips[chip.id] = m
                cgMode.addView(chip)
                if (m == mode) cgMode.check(chip.id)
            }
            root.addView(cgMode)

            // ---- daily sub-mode chips ----
            cgSub = chipGroup()
            for ((appMode, labelRes) in listOf(
                false to R.string.sg_daily_by_list,
                true to R.string.sg_daily_by_app)) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = getString(labelRes)
                    isCheckable = true
                }
                subChips[chip.id] = appMode
                cgSub.addView(chip)
                if (appMode == dailyAppMode) cgSub.check(chip.id)
            }
            root.addView(cgSub)

            // ---- study list section ----
            listSection = section(R.string.sg_list_label)
            tvListValue = TextView(this@RuleEditorActivity).apply {
                setBackgroundResource(R.drawable.bg_action_ghost)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
                setOnClickListener { showListChooser() }
            }
            listSection.addView(tvListValue)
            listSection.addView(
                Button(this@RuleEditorActivity).apply {
                    text = getString(R.string.sg_btn_manage)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(0, dp(6), 0, 0) }
                    setOnClickListener {
                        StudyGuardUi.showManager(
                            this@RuleEditorActivity) {
                            refreshListValue()
                        }
                    }
                })

            // ---- single app section (daily mode) ----
            appSection = section(R.string.fl_app_label)
            tvAppValue = TextView(this@RuleEditorActivity).apply {
                setBackgroundResource(R.drawable.bg_action_ghost)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
                setOnClickListener { showAppPicker() }
            }
            appSection.addView(tvAppValue)

            // ---- duration section ----
            val durSection = section(0)
            tvDurLabel = durSection.getChildAt(0) as TextView
            cgDur = chipGroup()
            durSection.addView(cgDur)
            etCustomMins = EditText(this@RuleEditorActivity).apply {
                hint = getString(R.string.fl_custom_mins_hint)
                textSize = 13.5f
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            durSection.addView(etCustomMins)

            // ---- warn-before section ----
            val warnSection = section(R.string.sg_warn_label)
            cgWarn = chipGroup()
            for ((w, label) in listOf(
                0 to getString(R.string.sg_warn_off),
                2 to "2", 5 to "5", 10 to "10")) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                }
                warnChips[chip.id] = w
                cgWarn.addView(chip)
                if (w == warn) cgWarn.check(chip.id)
            }
            warnSection.addView(cgWarn)

            // ---- usage access hint ----
            tvPermHint = TextView(this@RuleEditorActivity).apply {
                text = getString(R.string.sg_perm_hint)
                textSize = 12.5f
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    startActivity(android.content.Intent(
                        this@RuleEditorActivity,
                        PermissionsActivity::class.java))
                }
            }
            root.addView(tvPermHint)

            // ---- listeners (after initial chip state) ----
            cgMode.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    val picked = ids.firstOrNull()
                        ?.let { modeChips[it] }
                    if (picked != null && picked != mode) {
                        mode = picked
                        refreshSections()
                    }
                }
            }
            cgSub.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    val picked = ids.firstOrNull()
                        ?.let { subChips[it] }
                    if (picked != null && picked != dailyAppMode) {
                        dailyAppMode = picked
                        refreshSections()
                    }
                }
            }
            cgDur.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    val v = ids.firstOrNull()
                        ?.let { durChips[it] }
                    if (v != null) {
                        mins = v
                        syncing = true
                        etCustomMins.setText("")
                        syncing = false
                    }
                }
            }
            etCustomMins.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val v = s?.toString()?.trim()?.toIntOrNull()
                    if (v != null && v > 0) {
                        mins = v
                        syncing = true
                        cgDur.clearCheck()
                        syncing = false
                    }
                }
                override fun beforeTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            cgWarn.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    ids.firstOrNull()?.let { warnChips[it] }
                        ?.let { warn = it }
                }
            }

            listId = Config.studyLists(this@RuleEditorActivity)
                .firstOrNull()?.id ?: ""
            refreshSections()
        }

        private fun chipGroup(): ChipGroup =
            ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, 0) }
            }

        private fun section(labelRes: Int): LinearLayout {
            val box = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            box.addView(TextView(this@RuleEditorActivity).apply {
                if (labelRes != 0) text = getString(labelRes)
                textSize = 12.5f
                setTypeface(typeface,
                    android.graphics.Typeface.BOLD)
            })
            root.addView(box)
            return box
        }

        private fun currentType(): String =
            if (mode == "daily") "study_guard_daily"
            else "study_guard_continuous"

        private fun refreshSections() {
            tvTitle.text = AutoCatalog.label(
                this@RuleEditorActivity, "t", currentType())
            cgSub.visibility = if (mode == "daily")
                View.VISIBLE else View.GONE
            listSection.visibility =
                if (mode == "continuous" || !dailyAppMode)
                    View.VISIBLE else View.GONE
            appSection.visibility =
                if (mode == "daily" && dailyAppMode)
                    View.VISIBLE else View.GONE
            tvDurLabel.text = getString(
                if (mode == "daily") R.string.sg_limit_label
                else R.string.sg_mins_label)
            if (durBuiltFor != mode) buildDurChips()
            refreshListValue()
            refreshAppValue()
            tvPermHint.visibility =
                if (PermChecks.usageAccess(this@RuleEditorActivity))
                    View.GONE else View.VISIBLE
        }

        private fun buildDurChips() {
            durBuiltFor = mode
            syncing = true
            cgDur.clearCheck()
            cgDur.removeAllViews()
            durChips.clear()
            val values = if (mode == "daily")
                listOf(15, 30, 45, 60, 90, 120)
            else listOf(15, 20, 30, 45, 60)
            for (v in values) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = v.toString()
                    isCheckable = true
                }
                durChips[chip.id] = v
                cgDur.addView(chip)
            }
            syncing = false
            refreshDurationUi()
        }

        private fun refreshDurationUi() {
            syncing = true
            cgDur.clearCheck()
            var onChip = false
            for ((id, v) in durChips) {
                if (mins == v) { cgDur.check(id); onChip = true }
            }
            etCustomMins.setText(
                if (!onChip) mins.toString() else "")
            syncing = false
        }

        private fun refreshListValue() {
            val lists = Config.studyLists(this@RuleEditorActivity)
            val cur = lists.firstOrNull { it.id == listId }
            tvListValue.text = when {
                lists.isEmpty() ->
                    getString(R.string.sg_no_list)
                cur != null -> cur.name + " — " +
                    getString(R.string.sg_apps_count, cur.pkgs.size)
                else -> getString(R.string.sg_pick_list)
            }
        }

        private fun refreshAppValue() {
            tvAppValue.text = if (appPkg.isEmpty())
                getString(R.string.fl_pick_app)
            else AppLocker.appLabel(this@RuleEditorActivity, appPkg)
        }

        /** Pick the trigger's list; the last entry starts the
         *  create flow, and with no lists at all the manager
         *  opens directly (it guides creation). */
        private fun showListChooser() {
            val act = this@RuleEditorActivity
            val lists = Config.studyLists(act)
            if (lists.isEmpty()) {
                StudyGuardUi.showManager(act) { refreshListValue() }
                return
            }
            val labels = (lists.map {
                it.name + " — " + getString(
                    R.string.sg_apps_count, it.pkgs.size)
            } + listOf(getString(R.string.sg_new_list))).toTypedArray()
            AlertDialog.Builder(act)
                .setTitle(getString(R.string.sg_list_label))
                .setItems(labels) { _, which ->
                    if (which < lists.size) {
                        listId = lists[which].id
                        refreshListValue()
                    } else {
                        StudyGuardUi.createList(act) { created ->
                            listId = created.id
                            refreshListValue()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        private fun showAppPicker() {
            val act = this@RuleEditorActivity
            val apps = AppLocker.launchableApps(act)
            if (apps.isEmpty()) return
            val labels = apps.map { it.label + "\n" + it.pkg }
                .toTypedArray()
            AlertDialog.Builder(act)
                .setTitle(getString(R.string.fl_app_label))
                .setItems(labels) { _, which ->
                    appPkg = apps[which].pkg
                    refreshAppValue()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }

        fun toJson(): JSONObject {
            val parts = mutableListOf<String>()
            if (mode == "continuous") {
                if (listId.isNotEmpty()) parts.add("list=$listId")
                parts.add("mins=$mins")
            } else {
                if (dailyAppMode) {
                    if (appPkg.isNotEmpty())
                        parts.add("app=$appPkg")
                } else if (listId.isNotEmpty()) {
                    parts.add("list=$listId")
                }
                parts.add("limit=$mins")
            }
            parts.add("warn=$warn")
            return JSONObject().put("type", currentType())
                .put("param", parts.joinToString("|"))
        }

        fun load(o: JSONObject) {
            val kv = Automation.parseStudyGuardParam(
                o.optString("param"))
            mode = if (o.optString("type") == "study_guard_daily")
                "daily" else "continuous"
            listId = kv["list"] ?: ""
            appPkg = kv["app"] ?: ""
            dailyAppMode = appPkg.isNotEmpty()
            mins = (if (mode == "daily") kv["limit"]
                else kv["mins"])?.toIntOrNull() ?: 30
            warn = kv["warn"]?.toIntOrNull() ?: 5
            syncing = true
            cgMode.clearCheck()
            for ((id, m) in modeChips)
                if (m == mode) cgMode.check(id)
            cgSub.clearCheck()
            for ((id, appMode) in subChips)
                if (appMode == dailyAppMode) cgSub.check(id)
            cgWarn.clearCheck()
            for ((id, w) in warnChips)
                if (w == warn) cgWarn.check(id)
            syncing = false
            refreshSections()
        }
    }

    /** v2.21: the screen-rest trigger card — duration chips +
     *  custom minutes + warn-before chips, never free text.
     *  Serializes to one trigger {type:"screen_on_continuous",
     *  param:"mins=..|warn=.."} through the engine's own
     *  parser (Automation.parseScreenRestParam), so the
     *  round-trip cannot drift. No list, no app, no permission
     *  hint — screen state needs none. */
    private inner class ScreenRestRow {
        val root = LinearLayout(this@RuleEditorActivity)
        private var mins: Int = 60
        private var warn: Int = 5
        private var syncing = false
        private val durChips = HashMap<Int, Int>()
        private val warnChips = HashMap<Int, Int>()
        private lateinit var cgDur: ChipGroup
        private lateinit var etCustomMins: EditText
        private lateinit var cgWarn: ChipGroup

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setBackgroundResource(R.drawable.bg_row_card)
            root.setPadding(dp(12), dp(10), dp(12), dp(12))
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }

            val head = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = AutoCatalog.label(this@RuleEditorActivity,
                    "t", "screen_on_continuous")
                textSize = 13.5f
                setTypeface(typeface,
                    android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            head.addView(TextView(this@RuleEditorActivity).apply {
                text = "×"
                textSize = 17f
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    (root.parent as? ViewGroup)?.removeView(root)
                }
            })
            root.addView(head)

            // ---- duration section ----
            val durSection = section(R.string.sr_mins_label)
            cgDur = chipGroup()
            for (v in listOf(30, 60, 90, 120)) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = v.toString()
                    isCheckable = true
                }
                durChips[chip.id] = v
                cgDur.addView(chip)
            }
            durSection.addView(cgDur)
            etCustomMins = EditText(this@RuleEditorActivity).apply {
                hint = getString(R.string.fl_custom_mins_hint)
                textSize = 13.5f
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            durSection.addView(etCustomMins)

            // ---- warn-before section ----
            val warnSection = section(R.string.sg_warn_label)
            cgWarn = chipGroup()
            for ((w, label) in listOf(
                0 to getString(R.string.sg_warn_off),
                2 to "2", 5 to "5", 10 to "10")) {
                val chip = Chip(this@RuleEditorActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                }
                warnChips[chip.id] = w
                cgWarn.addView(chip)
                if (w == warn) cgWarn.check(chip.id)
            }
            warnSection.addView(cgWarn)

            // ---- listeners (after initial chip state) ----
            cgDur.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    val v = ids.firstOrNull()
                        ?.let { durChips[it] }
                    if (v != null) {
                        mins = v
                        syncing = true
                        etCustomMins.setText("")
                        syncing = false
                    }
                }
            }
            etCustomMins.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (syncing) return
                    val v = s?.toString()?.trim()?.toIntOrNull()
                    if (v != null && v > 0) {
                        mins = v
                        syncing = true
                        cgDur.clearCheck()
                        syncing = false
                    }
                }
                override fun beforeTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(
                    s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            cgWarn.setOnCheckedStateChangeListener { _, ids ->
                if (!syncing) {
                    ids.firstOrNull()?.let { warnChips[it] }
                        ?.let { warn = it }
                }
            }
            refreshDurationUi()
        }

        private fun chipGroup(): ChipGroup =
            ChipGroup(this@RuleEditorActivity).apply {
                isSingleSelection = true
                isSelectionRequired = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, 0) }
            }

        private fun section(labelRes: Int): LinearLayout {
            val box = LinearLayout(this@RuleEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(10), 0, 0) }
            }
            box.addView(TextView(this@RuleEditorActivity).apply {
                if (labelRes != 0) text = getString(labelRes)
                textSize = 12.5f
                setTypeface(typeface,
                    android.graphics.Typeface.BOLD)
            })
            root.addView(box)
            return box
        }

        private fun refreshDurationUi() {
            syncing = true
            cgDur.clearCheck()
            var onChip = false
            for ((id, v) in durChips) {
                if (mins == v) { cgDur.check(id); onChip = true }
            }
            etCustomMins.setText(
                if (!onChip) mins.toString() else "")
            syncing = false
        }

        fun toJson(): JSONObject {
            val parts = mutableListOf<String>()
            parts.add("mins=$mins")
            parts.add("warn=$warn")
            return JSONObject().put("type", "screen_on_continuous")
                .put("param", parts.joinToString("|"))
        }

        fun load(o: JSONObject) {
            val kv = Automation.parseScreenRestParam(
                o.optString("param"))
            mins = kv["mins"]?.toIntOrNull() ?: 60
            warn = kv["warn"]?.toIntOrNull() ?: 5
            syncing = true
            cgWarn.clearCheck()
            for ((id, w) in warnChips)
                if (w == warn) cgWarn.check(id)
            syncing = false
            refreshDurationUi()
        }
    }

    private val trigRows = mutableListOf<Row>()
    private val trigGuardRows = mutableListOf<StudyGuardRow>()
    private val trigRestRows = mutableListOf<ScreenRestRow>()
    private val consRows = mutableListOf<Row>()
    private val actRows = mutableListOf<Row>()
    // v2.18: groups live beside the flat constraint rows; action
    // items (plain rows, If/Else cards and — v2.20 — FocusLock
    // cards, v2.21 AutoTap cards) share ONE ordered list so the
    // saved action sequence matches the visual order exactly.
    private val consGroups = mutableListOf<GroupRow>()
    private val actItems = mutableListOf<Any>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)
        etName = findViewById(R.id.etRuleName)
        etCat = findViewById(R.id.etRuleCat)
        trigContainer = findViewById(R.id.trigContainer)
        consContainer = findViewById(R.id.consContainer)
        actContainer = findViewById(R.id.actContainer)
        cgConsMode = findViewById(R.id.cgConsMode)

        findViewById<Button>(R.id.btnAddTrigger).setOnClickListener {
            addRow("t", "", "")
        }
        findViewById<Button>(R.id.btnAddStudyGuard).setOnClickListener {
            addStudyGuardRow(null)
        }
        findViewById<Button>(R.id.btnAddScreenRest).setOnClickListener {
            addScreenRestRow(null)
        }
        findViewById<Button>(R.id.btnAddConstraint).setOnClickListener {
            addRow("c", "", "")
        }
        findViewById<Button>(R.id.btnAddGroup).setOnClickListener {
            addGroupRow(null)
        }
        findViewById<Button>(R.id.btnAddAction).setOnClickListener {
            addRow("a", "", "")
        }
        findViewById<Button>(R.id.btnAddIfElse).setOnClickListener {
            addIfElseRow(null)
        }
        findViewById<Button>(R.id.btnAddFocusLock).setOnClickListener {
            addFocusLockRow(null)
        }
        findViewById<Button>(R.id.btnAddAutoTap).setOnClickListener {
            addAutoTapRow(null)
        }
        findViewById<Button>(R.id.btnTraceRule).setOnClickListener {
            showTrace()
        }
        findViewById<Button>(R.id.btnSaveRule).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTestRule).setOnClickListener {
            val rule = buildRule()
            val data = JSONObject()
                .put("title", rule.optString("name")).put("body", "test")
            Automation.runActions(this, rule, data, 0)
            Toast.makeText(this, getString(R.string.auto_test),
                Toast.LENGTH_SHORT).show()
        }
        findViewById<android.widget.ImageButton>(R.id.btnEditorBack)
            .setOnClickListener { finish() }

        ruleId = intent.getStringExtra("rule_id") ?: ""
        val existing = Automation.rules(this)
            .firstOrNull { it.optString("id") == ruleId }
        if (existing != null) {
            etName.setText(existing.optString("name"))
            etCat.setText(existing.optString("cat"))
            for (trig in Automation.ruleTriggers(existing)) {
                val tt = trig.optString("type")
                if (tt == "study_guard_continuous" ||
                    tt == "study_guard_daily")
                    addStudyGuardRow(trig)
                else if (tt == "screen_on_continuous")
                    addScreenRestRow(trig)
                else addRow("t", tt, trig.optString("param"))
            }
            if (existing.optString("constraints_mode") == "any")
                cgConsMode.check(R.id.chipConsAny)
            val cons = existing.optJSONArray("constraints")
            if (cons != null) for (i in 0 until cons.length()) {
                val c = cons.optJSONObject(i) ?: continue
                if (c.optString("type") == "group") addGroupRow(c)
                else addRow("c", c.optString("type"), c.optString("param"), c)
            }
            val acts = existing.optJSONArray("actions")
            if (acts != null) for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                if (a.optString("type") == "if_else") addIfElseRow(a)
                else if (a.optString("type") == "focuslock_control")
                    addFocusLockRow(a)
                else if (a.optString("type") == "autotap_control")
                    addAutoTapRow(a)
                else addRow("a", a.optString("type"), a.optString("param"), a)
            }
            val btnDelete = findViewById<Button>(R.id.btnDeleteRule)
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                Automation.delete(this, ruleId)
                finish()
            }
            val btnHistory = findViewById<Button>(R.id.btnHistoryRule)
            btnHistory.visibility = View.VISIBLE
            btnHistory.setOnClickListener { showHistory() }
        }
        if (trigRows.isEmpty() && trigGuardRows.isEmpty() &&
            trigRestRows.isEmpty())
            addRow("t", "time_at", "")
        if (actItems.isEmpty()) addRow("a", "notify_peer", "")
        buildCategoryChips(existing?.optString("cat") ?: "")
    }

    /** Category chips: the categories already used by other rules,
     *  plus a Custom chip that just hands the free-text field back
     *  (the field itself stays the single source of truth, exactly
     *  like the old plain text box). */
    private fun buildCategoryChips(current: String) {
        val group = findViewById<ChipGroup>(R.id.cgRuleCats)
        val cats = Automation.rules(this)
            .map { it.optString("cat") }.filter { it.isNotBlank() }
            .distinct().take(6).toMutableList()
        if (current.isNotBlank() && current !in cats) cats.add(0, current)
        var checkedId = View.NO_ID
        for (cat in cats) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = cat
                isCheckable = true
                setOnClickListener { etCat.setText(cat) }
            }
            group.addView(chip)
            if (cat == current) checkedId = chip.id
        }
        val custom = Chip(this).apply {
            id = View.generateViewId()
            text = getString(R.string.ed_custom_cat)
            isCheckable = true
            setOnClickListener {
                etCat.setText("")
                etCat.requestFocus()
            }
        }
        group.addView(custom)
        if (checkedId != View.NO_ID) group.check(checkedId)
    }

    private fun addRow(kind: String, type: String, param: String,
                       extra: JSONObject? = null) {
        val t = type.ifEmpty { typesFor(kind).first() }
        val row = Row(kind, t, param)
        when (kind) {
            "t" -> { trigRows.add(row); trigContainer.addView(row.root) }
            "c" -> { consRows.add(row); consContainer.addView(row.root) }
            else -> { actRows.add(row); actItems.add(row)
                actContainer.addView(row.root) }
        }
        if (extra != null) row.loadExtra(extra)
    }

    private fun addGroupRow(existing: JSONObject?) {
        val g = GroupRow()
        consGroups.add(g)
        consContainer.addView(g.root)
        if (existing != null) g.load(existing) else g.addCond("", "", null)
    }

    private fun addIfElseRow(existing: JSONObject?) {
        val r = IfElseRow()
        actItems.add(r)
        actContainer.addView(r.root)
        if (existing != null) r.load(existing) else {
            r.addCond("", "", null)
            r.addThen("notify_peer", "", null)
        }
    }

    private fun addFocusLockRow(existing: JSONObject?) {
        val r = FocusLockRow()
        actItems.add(r)
        actContainer.addView(r.root)
        if (existing != null) r.load(existing)
    }

    private fun addAutoTapRow(existing: JSONObject?) {
        val r = AutoTapRow()
        actItems.add(r)
        actContainer.addView(r.root)
        if (existing != null) r.load(existing)
    }

    private fun addStudyGuardRow(existing: JSONObject?) {
        val r = StudyGuardRow()
        trigGuardRows.add(r)
        trigContainer.addView(r.root)
        if (existing != null) r.load(existing)
    }

    private fun addScreenRestRow(existing: JSONObject?) {
        val r = ScreenRestRow()
        trigRestRows.add(r)
        trigContainer.addView(r.root)
        if (existing != null) r.load(existing)
    }

    /** v2.18 (WS2): the "কেন চলল না" explainer — evaluates the rule
     *  being edited WITHOUT running it (engine evaluation, off the
     *  UI thread because live reads can block). */
    private fun showTrace() {
        val rule = buildRule()
        Toast.makeText(this, getString(R.string.trace_running),
            Toast.LENGTH_SHORT).show()
        Thread {
            val lines = Automation.traceRule(this, rule)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.trace_title))
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(getString(android.R.string.ok), null)
                    .show()
            }
        }.start()
    }

    /** v2.18 (WS2): previous versions of this rule, newest first;
     *  tapping one asks, then rolls back (which itself snapshots the
     *  current version first, so the rollback is undoable). */
    private fun showHistory() {
        val hist = Automation.ruleHistory(this, ruleId)
        if (hist.isEmpty()) {
            Toast.makeText(this, getString(R.string.history_empty),
                Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm",
            java.util.Locale.getDefault())
        val labels = hist.map {
            fmt.format(java.util.Date(it.first)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_title))
            .setItems(labels) { _, which ->
                AlertDialog.Builder(this)
                    .setMessage(getString(
                        R.string.history_confirm, labels[which]))
                    .setPositiveButton(
                        getString(R.string.btn_rollback)) { _, _ ->
                        if (Automation.rollbackRule(this, ruleId, which)) {
                            Toast.makeText(this,
                                getString(R.string.history_restored),
                                Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun buildRule(): JSONObject {
        // v2.20: plain rows and Study Guard cards serialize into
        // ONE trigger list (plain rows first, then guard cards);
        // v2.21: screen-rest cards join the same list.
        // triggers are OR'd by the engine, so the grouping costs
        // nothing and old rules keep their exact JSON shape.
        val liveTrig = mutableListOf<JSONObject>()
        for (r in trigRows) if (r.root.parent != null)
            liveTrig.add(r.toJson())
        for (g in trigGuardRows) if (g.root.parent != null)
            liveTrig.add(g.toJson())
        for (s in trigRestRows) if (s.root.parent != null)
            liveTrig.add(s.toJson())
        val name = etName.text.toString().trim().ifEmpty {
            if (liveTrig.isNotEmpty())
                AutoCatalog.label(this, "t",
                    liveTrig[0].optString("type"))
            else getString(R.string.auto_title)
        }
        val rule = JSONObject()
            .put("id", ruleId.ifEmpty { "r" + System.currentTimeMillis() })
            .put("name", name)
            .put("cat", etCat.text.toString().trim())
            .put("enabled", true)
        if (liveTrig.isNotEmpty()) {
            rule.put("trigger", liveTrig[0])
            if (liveTrig.size > 1) {
                val extra = JSONArray()
                for (i in 1 until liveTrig.size)
                    extra.put(liveTrig[i])
                rule.put("triggers", extra)
            }
        }
        rule.put("constraints_mode",
            if (cgConsMode.checkedChipId == R.id.chipConsAny) "any" else "all")
        val cons = JSONArray()
        for (row in consRows) if (row.root.parent != null) cons.put(row.toJson())
        // v2.18 (WS3): group cards serialize after the flat rows.
        // Old rules have no groups, so their JSON is unchanged.
        for (g in consGroups) if (g.root.parent != null) cons.put(g.toJson())
        rule.put("constraints", cons)
        val acts = JSONArray()
        // v2.18: one ordered pass over rows AND If/Else cards, so the
        // saved sequence is the visual sequence.
        for (item in actItems) {
            when (item) {
                is Row -> if (item.root.parent != null)
                    acts.put(item.toJson())
                is IfElseRow -> if (item.root.parent != null)
                    acts.put(item.toJson())
                is FocusLockRow -> if (item.root.parent != null)
                    acts.put(item.toJson())
                is AutoTapRow -> if (item.root.parent != null)
                    acts.put(item.toJson())
            }
        }
        rule.put("actions", acts)
        return rule
    }

    private fun save() {
        Automation.upsert(this, buildRule())
        finish()
    }
}
