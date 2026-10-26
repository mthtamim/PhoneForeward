package com.mthtamim.phonelink

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.5.0 Automate .flo import (best-effort — see FlowFlo).
 * Opens the system file picker for any file, validates the
 * LAFl magic, and imports recognized blocks as a new Lab flow.
 * The result dialog ALWAYS reports mapped blocks, unmapped
 * block-like strings, and the best-effort caveat — nothing is
 * silently dropped. A file without the magic is rejected with
 * a toast, never a crash. Launched from Home's "Import .flo"
 * button; finishes on every path.
 */
class FlowFloImportActivity : LocalizedActivity() {

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
            val name = uri.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":")
                ?: "flow.flo"
            val result = FlowFlo.importBytes(bytes, name)
            if (result == null) {
                Toast.makeText(this, R.string.flow_flo_bad, Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            if (result.mapped.isNotEmpty()) {
                FlowStore.saveFlow(this, result.flow)
            }
            val msg = StringBuilder()
            msg.append(getString(R.string.flow_flo_mapped, result.mapped.size))
            msg.append("\n")
            msg.append(getString(R.string.flow_flo_unmapped, result.unmapped.size))
            if (result.unmapped.isNotEmpty()) {
                msg.append("\n")
                val shown = result.unmapped.take(20)
                msg.append(shown.joinToString(", "))
                if (result.unmapped.size > 20) {
                    msg.append(" … +" + (result.unmapped.size - 20) + " more")
                }
            }
            msg.append("\n\n")
            msg.append(getString(R.string.flow_flo_caveat))
            AlertDialog.Builder(this)
                .setTitle(R.string.flow_flo_report_title)
                .setMessage(msg.toString())
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.flow_flo_bad, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker.launch(arrayOf("*/*"))
    }
}
