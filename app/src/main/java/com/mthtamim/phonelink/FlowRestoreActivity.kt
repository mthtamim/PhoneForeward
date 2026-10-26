package com.mthtamim.phonelink

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * PhoneLink v2.24.0 flow backup restore (the counterpart to
 * FlowIO exportBackup). Opens the system file picker for a JSON
 * backup, validates it (must be an object with formatVersion 1),
 * then shows a confirmation dialog BEFORE touching anything:
 * restore MERGES by id — an entry whose id already exists
 * overwrites that entry, a new id is added — and it never wipes
 * the current flows. Entries without an id (or flows without a
 * name) are skipped and counted. The final toast reports added /
 * overwritten / skipped. A "rules" section in the file (from an
 * old Lab backup) is IGNORED and counted as skipped: PhoneLink
 * rules are never restored from a flow backup.
 * Launched from the Flows tab ⋮ menu; finishes on every path.
 */
class FlowRestoreActivity : LocalizedActivity() {

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val root = JSONObject(text)
            if (root.optInt("formatVersion", -1) != 1) {
                Toast.makeText(this, R.string.flow_restore_bad, Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            val flows = validFlows(root.optJSONArray("flows"))
            val rulesArr = root.optJSONArray("rules")
            val skipped = skippedCount(root.optJSONArray("flows"), rulesArr, flows.size, 0)
            var overwrite = 0
            for (flow in flows) {
                if (FlowStore.loadFlow(this, flow.optString("id")) != null) {
                    overwrite++
                }
            }
            if (flows.isEmpty()) {
                Toast.makeText(this, R.string.flow_restore_bad, Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.restore_title)
                .setMessage(
                    getString(R.string.flow_restore_confirm, flows.size, overwrite)
                )
                .setPositiveButton(R.string.restore_confirm_ok) { _, _ ->
                    var added = 0
                    var overwritten = 0
                    for (flow in flows) {
                        if (FlowStore.loadFlow(this, flow.optString("id")) != null) {
                            overwritten++
                        } else {
                            added++
                        }
                        FlowStore.saveFlow(this, flow)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.restore_done, added, overwritten, skipped),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .setOnCancelListener {
                    finish()
                }
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.flow_restore_bad, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun validFlows(arr: JSONArray?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (arr == null) {
            return out
        }
        for (i in 0 until arr.length()) {
            val flow = arr.optJSONObject(i) ?: continue
            if (flow.optString("id").isEmpty() || flow.optString("name").isEmpty()) {
                continue
            }
            out.add(flow)
        }
        return out
    }

    private fun skippedCount(
        flows: JSONArray?,
        rules: JSONArray?,
        validFlowCount: Int,
        @Suppress("UNUSED_PARAMETER") validRuleCount: Int
    ): Int {
        // Rules entries are always skipped in a flow restore.
        val total = (flows?.length() ?: 0) + (rules?.length() ?: 0)
        return total - validFlowCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker.launch(arrayOf("application/json"))
    }
}
