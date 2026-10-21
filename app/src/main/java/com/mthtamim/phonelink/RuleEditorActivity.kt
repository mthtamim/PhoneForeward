package com.mthtamim.phonelink

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rule editor: one trigger, any number of constraints and actions.
 * Each row is a type spinner plus a free-text parameter field whose
 * hint explains the expected format for the chosen type.
 */
class RuleEditorActivity : LocalizedActivity() {

    private lateinit var etName: EditText
    private lateinit var spTrigger: Spinner
    private lateinit var etTriggerParam: EditText
    private lateinit var tvTriggerHint: TextView
    private lateinit var consContainer: LinearLayout
    private lateinit var actContainer: LinearLayout
    private var ruleId: String = ""

    private inner class Row(val kind: String, type: String, param: String) {
        val root = LinearLayout(this@RuleEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 4, 0, 4) }
        }
        val spinner = Spinner(this@RuleEditorActivity)
        val etParam = EditText(this@RuleEditorActivity).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(param)
            textSize = 13f
        }
        init {
            val types = if (kind == "c") AutoCatalog.constraints else AutoCatalog.actions
            spinner.adapter = ArrayAdapter(this@RuleEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types.map { AutoCatalog.label(this@RuleEditorActivity, kind, it) })
            val idx = types.indexOf(type)
            if (idx >= 0) spinner.setSelection(idx)
            spinner.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            root.addView(spinner)
            root.addView(etParam)
            root.addView(Button(this@RuleEditorActivity).apply {
                text = "✕"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    (if (kind == "c") consContainer else actContainer).removeView(root)
                }
            })
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    etParam.hint = AutoCatalog.hint(this@RuleEditorActivity, kind, types[pos])
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        fun toJson(kind: String): JSONObject {
            val types = if (kind == "c") AutoCatalog.constraints else AutoCatalog.actions
            return JSONObject().put("type", types[spinner.selectedItemPosition])
                .put("param", etParam.text.toString().trim())
        }
    }

    private val consRows = mutableListOf<Row>()
    private val actRows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)
        etName = findViewById(R.id.etRuleName)
        spTrigger = findViewById(R.id.spTrigger)
        etTriggerParam = findViewById(R.id.etTriggerParam)
        tvTriggerHint = findViewById(R.id.tvTriggerHint)
        consContainer = findViewById(R.id.consContainer)
        actContainer = findViewById(R.id.actContainer)

        spTrigger.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            AutoCatalog.triggers.map { AutoCatalog.label(this, "t", it) })
        spTrigger.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val hint = AutoCatalog.hint(this@RuleEditorActivity, "t", AutoCatalog.triggers[pos])
                tvTriggerHint.text = hint
                etTriggerParam.hint = hint
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnAddConstraint).setOnClickListener { addRow("c", "", "") }
        findViewById<Button>(R.id.btnAddAction).setOnClickListener { addRow("a", "", "") }
        findViewById<Button>(R.id.btnSaveRule).setOnClickListener { save() }
        findViewById<Button>(R.id.btnEditorBack).setOnClickListener { finish() }

        ruleId = intent.getStringExtra("rule_id") ?: ""
        val existing = Automation.rules(this).firstOrNull { it.optString("id") == ruleId }
        if (existing != null) {
            etName.setText(existing.optString("name"))
            findViewById<android.widget.EditText>(R.id.etRuleCat)
                .setText(existing.optString("cat"))
            val trig = existing.optJSONObject("trigger")
            if (trig != null) {
                val idx = AutoCatalog.triggers.indexOf(trig.optString("type"))
                if (idx >= 0) spTrigger.setSelection(idx)
                etTriggerParam.setText(trig.optString("param"))
            }
            val cons = existing.optJSONArray("constraints")
            if (cons != null) for (i in 0 until cons.length()) {
                val c = cons.optJSONObject(i) ?: continue
                addRow("c", c.optString("type"), c.optString("param"))
            }
            val acts = existing.optJSONArray("actions")
            if (acts != null) for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                addRow("a", a.optString("type"), a.optString("param"))
            }
        }
        if (actRows.isEmpty()) addRow("a", "notify_peer", "")
    }

    private fun addRow(kind: String, type: String, param: String) {
        val row = Row(kind, type, param)
        if (kind == "c") { consRows.add(row); consContainer.addView(row.root) }
        else { actRows.add(row); actContainer.addView(row.root) }
        row.spinner.setSelection(
            (if (kind == "c") AutoCatalog.constraints else AutoCatalog.actions)
                .indexOf(type).coerceAtLeast(0))
    }

    private fun save() {
        val name = etName.text.toString().trim()
            .ifEmpty { AutoCatalog.label(this, "t", AutoCatalog.triggers[spTrigger.selectedItemPosition]) }
        val rule = JSONObject()
            .put("id", ruleId.ifEmpty { "r" + System.currentTimeMillis() })
            .put("name", name)
            .put("cat", findViewById<android.widget.EditText>(R.id.etRuleCat)
                .text.toString().trim())
            .put("enabled", true)
            .put("trigger", JSONObject()
                .put("type", AutoCatalog.triggers[spTrigger.selectedItemPosition])
                .put("param", etTriggerParam.text.toString().trim()))
        val cons = JSONArray()
        for (row in consRows) if (row.root.parent != null) cons.put(row.toJson("c"))
        rule.put("constraints", cons)
        val acts = JSONArray()
        for (row in actRows) if (row.root.parent != null) acts.put(row.toJson("a"))
        rule.put("actions", acts)
        Automation.upsert(this, rule)
        finish()
    }
}
