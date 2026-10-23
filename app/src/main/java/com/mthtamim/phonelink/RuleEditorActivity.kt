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
            if (kind == "a" && type == "if_else") continue
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
            val o = JSONObject().put("type", type)
                .put("param", etParam.text.toString().trim())
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

    private val trigRows = mutableListOf<Row>()
    private val consRows = mutableListOf<Row>()
    private val actRows = mutableListOf<Row>()
    // v2.18: groups live beside the flat constraint rows; action
    // items (plain rows and If/Else cards) share ONE ordered list so
    // the saved action sequence matches the visual order exactly.
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
            for (trig in Automation.ruleTriggers(existing))
                addRow("t", trig.optString("type"), trig.optString("param"))
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
        if (trigRows.isEmpty()) addRow("t", "time_at", "")
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
        val liveTrig = trigRows.filter { it.root.parent != null }
        val name = etName.text.toString().trim().ifEmpty {
            if (liveTrig.isNotEmpty())
                AutoCatalog.label(this, "t", liveTrig[0].type)
            else getString(R.string.auto_title)
        }
        val rule = JSONObject()
            .put("id", ruleId.ifEmpty { "r" + System.currentTimeMillis() })
            .put("name", name)
            .put("cat", etCat.text.toString().trim())
            .put("enabled", true)
        if (liveTrig.isNotEmpty()) {
            rule.put("trigger", liveTrig[0].toJson())
            if (liveTrig.size > 1) {
                val extra = JSONArray()
                for (i in 1 until liveTrig.size)
                    extra.put(liveTrig[i].toJson())
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
