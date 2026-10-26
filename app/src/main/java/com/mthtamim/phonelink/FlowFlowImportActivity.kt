package com.mthtamim.phonelink

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * v1.3.0 flow import (the counterpart to FlowIO export).
 * Opens the system file picker for a JSON file, validates it
 * (must be an object with a "nodes" array and a non-empty
 * "name"), and saves it as a flow. A colliding id gets a fresh
 * one instead of overwriting. Malformed files are rejected
 * with a toast, never a crash. Launched from Home's Import
 * button; finishes on every path.
 */
class FlowFlowImportActivity : LocalizedActivity() {

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val flow = JSONObject(text)
            val nodes = flow.optJSONArray("nodes")
            val name = flow.optString("name")
            if (nodes == null || name.isEmpty()) {
                Toast.makeText(this, R.string.flow_import_bad, Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            var id = flow.optString("id")
            if (id.isEmpty() || FlowStore.loadFlow(this, id) != null) {
                id = "f" + System.currentTimeMillis().toString()
                flow.put("id", id)
            }
            FlowStore.saveFlow(this, flow)
            Toast.makeText(this, getString(R.string.flow_import_ok, name), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.flow_import_bad, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker.launch(arrayOf("application/json"))
    }
}
