package com.mthtamim.phonelink

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rule editor (v2.9): any number of triggers (OR — the rule fires when
 * ANY of them matches), constraints with an AND/OR mode and a per-item
 * NOT toggle, and actions with an optional per-action condition.
 * Rows are built programmatically, as before.
 */
class RuleEditorActivity : LocalizedActivity() {

    private lateinit var etName: EditText
    private lateinit var trigContainer: LinearLayout
    private lateinit var spConsMode: Spinner
    private lateinit var consContainer: LinearLayout
    private lateinit var actContainer: LinearLayout
    private var ruleId: String = ""

    private inner class Row(val kind: String, type: String, param: String) {
        val root = LinearLayout(this@RuleEditorActivity)
        val spinner = Spinner(this@RuleEditorActivity)
        val etParam = EditText(this@RuleEditorActivity)
        var cbNegate: CheckBox? = null
        var spCond: Spinner? = null
        var etCondParam: EditText? = null

        init {
            val types = types()
            root.orientation = if (kind == "a")
                LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 4, 0, 4) }
            val line1 = if (kind == "a")
                LinearLayout(this@RuleEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                } else root
            spinner.adapter = ArrayAdapter(this@RuleEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types.map { AutoCatalog.label(this@RuleEditorActivity, kind, it) })
            val idx = types.indexOf(type)
            if (idx >= 0) spinner.setSelection(idx)
            spinner.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            etParam.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            etParam.setText(param)
            etParam.textSize = 13f
            line1.addView(spinner)
            line1.addView(etParam)
            if (kind == "c") {
                cbNegate = CheckBox(this@RuleEditorActivity).apply {
                    text = getString(R.string.auto_negate)
                }
                line1.addView(cbNegate)
            }
            line1.addView(Button(this@RuleEditorActivity).apply {
                text = "✕"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    when (kind) {
                        "t" -> trigContainer
                        "c" -> consContainer
                        else -> actContainer
                    }.removeView(root)
                }
            })
            if (kind == "a") {
                root.addView(line1)
                val line2 = LinearLayout(this@RuleEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val sp = Spinner(this@RuleEditorActivity)
                sp.adapter = ArrayAdapter(this@RuleEditorActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    (listOf("") + AutoCatalog.constraints).map {
                        if (it.isEmpty()) getString(R.string.auto_cond_none)
                        else AutoCatalog.label(this@RuleEditorActivity, "c", it)
                    })
                sp.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                val et = EditText(this@RuleEditorActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 13f
                    hint = getString(R.string.auto_param_hint)
                }
                spCond = sp
                etCondParam = et
                line2.addView(sp)
                line2.addView(et)
                root.addView(line2)
            }
            spinner.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        p: android.widget.AdapterView<*>?,
                        v: android.view.View?, pos: Int, id: Long) {
                        etParam.hint = AutoCatalog.hint(
                            this@RuleEditorActivity, kind, types[pos])
                    }
                    override fun onNothingSelected(
                        p: android.widget.AdapterView<*>?) {}
                }
        }

        fun types(): List<String> = when (kind) {
            "c" -> AutoCatalog.constraints
            "t" -> AutoCatalog.triggers
            else -> AutoCatalog.actions
        }

        fun selectedType(): String = types()[spinner.selectedItemPosition]

        fun toJson(): JSONObject {
            val o = JSONObject().put("type", selectedType())
                .put("param", etParam.text.toString().trim())
            if (kind == "c" && cbNegate?.isChecked == true)
                o.put("negate", true)
            if (kind == "a") {
                val pos = spCond?.selectedItemPosition ?: 0
                if (pos > 0) {
                    o.put("cond", JSONObject()
                        .put("type", AutoCatalog.constraints[pos - 1])
                        .put("param",
                            etCondParam?.text?.toString()?.trim() ?: ""))
                }
            }
            return o
        }

        fun loadExtra(o: JSONObject) {
            if (kind == "c") cbNegate?.isChecked = o.optBoolean("negate")
            if (kind == "a") {
                val cond = o.optJSONObject("cond") ?: return
                val idx = AutoCatalog.constraints.indexOf(cond.optString("type"))
                if (idx >= 0) {
                    spCond?.setSelection(idx + 1)
                    etCondParam?.setText(cond.optString("param"))
                }
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
        trigContainer = findViewById(R.id.trigContainer)
        spConsMode = findViewById(R.id.spConsMode)
        consContainer = findViewById(R.id.consContainer)
        actContainer = findViewById(R.id.actContainer)

        spConsMode.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.auto_cons_all),
                getString(R.string.auto_cons_any)))

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
        findViewById<Button>(R.id.btnEditorBack).setOnClickListener { finish() }

        ruleId = intent.getStringExtra("rule_id") ?: ""
        val existing = Automation.rules(this)
            .firstOrNull { it.optString("id") == ruleId }
        if (existing != null) {
            etName.setText(existing.optString("name"))
            findViewById<EditText>(R.id.etRuleCat)
                .setText(existing.optString("cat"))
            for (trig in Automation.ruleTriggers(existing))
                addRow("t", trig.optString("type"), trig.optString("param"))
            spConsMode.setSelection(
                if (existing.optString("constraints_mode") == "any") 1 else 0)
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
        }
        if (trigRows.isEmpty()) addRow("t", "time_at", "")
        if (actRows.isEmpty()) addRow("a", "notify_peer", "")
    }

    private fun addRow(kind: String, type: String, param: String,
                       extra: JSONObject? = null) {
        val row = Row(kind, type, param)
        when (kind) {
            "t" -> { trigRows.add(row); trigContainer.addView(row.root) }
            "c" -> { consRows.add(row); consContainer.addView(row.root) }
            else -> { actRows.add(row); actContainer.addView(row.root) }
        }
        row.spinner.setSelection(
            row.types().indexOf(type).coerceAtLeast(0))
        if (extra != null) row.loadExtra(extra)
    }

    private fun save() {
        val liveTrig = trigRows.filter { it.root.parent != null }
        val name = etName.text.toString().trim().ifEmpty {
            if (liveTrig.isNotEmpty())
                AutoCatalog.label(this, "t", liveTrig[0].selectedType())
            else getString(R.string.auto_title)
        }
        val rule = JSONObject()
            .put("id", ruleId.ifEmpty { "r" + System.currentTimeMillis() })
            .put("name", name)
            .put("cat", findViewById<EditText>(R.id.etRuleCat)
                .text.toString().trim())
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
            if (spConsMode.selectedItemPosition == 1) "any" else "all")
        val cons = JSONArray()
        for (row in consRows) if (row.root.parent != null) cons.put(row.toJson())
        rule.put("constraints", cons)
        val acts = JSONArray()
        for (row in actRows) if (row.root.parent != null) acts.put(row.toJson())
        rule.put("actions", acts)
        Automation.upsert(this, rule)
        finish()
    }
}
