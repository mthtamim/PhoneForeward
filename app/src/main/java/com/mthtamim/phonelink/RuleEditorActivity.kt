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
            "ui_click" to R.string.pg_apps)
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
            "hotspot_toggle" to R.string.pg_control)
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
                setOnClickListener {
                    when (kind) {
                        "t" -> trigContainer
                        "c" -> consContainer
                        else -> actContainer
                    }.removeView(root)
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

    private val trigRows = mutableListOf<Row>()
    private val consRows = mutableListOf<Row>()
    private val actRows = mutableListOf<Row>()

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
        findViewById<Button>(R.id.btnAddAction).setOnClickListener {
            addRow("a", "", "")
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
                addRow("c", c.optString("type"), c.optString("param"), c)
            }
            val acts = existing.optJSONArray("actions")
            if (acts != null) for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                addRow("a", a.optString("type"), a.optString("param"), a)
            }
            val btnDelete = findViewById<Button>(R.id.btnDeleteRule)
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                Automation.delete(this, ruleId)
                finish()
            }
        }
        if (trigRows.isEmpty()) addRow("t", "time_at", "")
        if (actRows.isEmpty()) addRow("a", "notify_peer", "")
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
            else -> { actRows.add(row); actContainer.addView(row.root) }
        }
        if (extra != null) row.loadExtra(extra)
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
        rule.put("constraints", cons)
        val acts = JSONArray()
        for (row in actRows) if (row.root.parent != null) acts.put(row.toJson())
        rule.put("actions", acts)
        return rule
    }

    private fun save() {
        Automation.upsert(this, buildRule())
        finish()
    }
}
