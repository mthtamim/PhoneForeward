package com.mthtamim.phonelink

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.3.0 flow export (the sharing mechanism — Lab is local-only
 * by design, so there is no community server; a flow JSON file is
 * shared instead, and FlowFlowImportActivity reads one back).
 * Export writes filesDir/exports/<name>.json and opens the
 * system share sheet via the FileProvider (no world-readable
 * file:// Uri). Returns false when the flow has no name or the
 * write fails; the caller toasts the outcome.
 */
object FlowIO {

    fun exportFlow(ctx: Context, flow: JSONObject): Boolean {
        try {
            val name = flow.optString("name").trim()
            if (name.isEmpty()) {
                return false
            }
            val dir = File(ctx.filesDir, "exports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val file = File(dir, safe + ".json")
            file.writeText(flow.toString())
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(intent, null))
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * PhoneLink v2.24.0 full backup: every FLOW in ONE JSON file
     * with a format-version field, shared through the same
     * FileProvider share sheet as a single-flow export. The file
     * name carries the date and time; restoring merges by id (see
     * FlowRestoreActivity) and never wipes. Rules are NOT in this
     * file: PhoneLink rules have their own backup/restore on the
     * Rules tab, and the two stores must never mix.
     */
    fun exportBackup(ctx: Context): Boolean {
        try {
            val dir = File(ctx.filesDir, "exports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmm", java.util.Locale.US
            ).format(java.util.Date())
            val root = JSONObject()
            root.put("formatVersion", 1)
            root.put("app", "PhoneLink")
            val flows = JSONArray()
            for (flow in FlowStore.flows(ctx)) {
                flows.put(flow)
            }
            root.put("flows", flows)
            val file = File(dir, "phonelink_flow_backup_" + stamp + ".json")
            file.writeText(root.toString())
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(intent, null))
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
