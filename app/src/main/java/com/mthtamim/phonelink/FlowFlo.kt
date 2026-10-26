package com.mthtamim.phonelink

import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.0 Automate .flo import — BEST-EFFORT, stated plainly.
 *
 * What is verified: Automate .flo files are proprietary BINARY
 * files (LlamaLab, no public format spec found after web
 * research; the characteristic magic is "LAFl"). No public
 * parser or byte-level spec exists, so exact block/edge
 * recovery from the binary is NOT possible from documentation
 * alone. A sample .flo file from the user would settle the
 * real encoding (string table layout, block ids, edge table).
 *
 * What this does anyway (honest subset): it verifies the
 * LAFl magic, extracts every readable string run from the
 * binary (ASCII + UTF-16LE, the two encodings Java-era
 * Android files use), maps strings containing known Automate
 * block keywords to Lab nodes, chains them in file order
 * (edge bytes are unknown, so order is best-effort), and
 * REPORTS mapped and unmapped-block-like strings by name —
 * never silently dropping a block. The imported flow opens in
 * the normal editor, so the user can fix nodes and edges.
 *
 * The "Flow beginning" block maps to a trigger node of type
 * "manual": manual Run-now starts there (runFlowSyncFrom
 * starts at the first kind-"t" node), and no background event
 * ever matches "manual", so an imported flow never fires on
 * its own until the user retargets its trigger.
 */
object FlowFlo {

    data class MappedBlock(val source: String, val labType: String, val kind: String)

    data class ImportResult(
        val flow: JSONObject,
        val mapped: List<MappedBlock>,
        val unmapped: List<String>,
        val totalStrings: Int
    )

    /** keyword (lowercase, contained) -> (Lab type, kind, display name). */
    private val KEYWORDS: List<Triple<String, String, String>> = listOf(
        Triple("flow beginning", "manual", "t"),
        Triple("notification", "local_notify", "a"),
        Triple("speak", "speak_local", "a"),
        Triple("toast", "toast_local", "a"),
        Triple("vibrate", "vibrate", "a"),
        Triple("wait", "wait", "a"),
        Triple("delay", "wait", "a"),
        Triple("send sms", "send_sms", "a"),
        Triple("sms", "send_sms", "a"),
        Triple("call number", "call_number", "a"),
        Triple("dial", "call_number", "a"),
        Triple("wifi", "wifi_toggle", "a"),
        Triple("bluetooth", "bt_toggle", "a"),
        Triple("variable", "set_var", "a"),
        Triple("dialog", "dialog_message", "a"),
        Triple("app start", "open_app", "a"),
        Triple("launch app", "open_app", "a"),
        Triple("http", "http", "a"),
        Triple("shell", "shell_cmd", "a"),
        Triple("write file", "write_file", "a"),
        Triple("read file", "read_file", "a"),
        Triple("file", "read_file", "a"),
        Triple("fork", "fork", "a"),
        Triple("flow stop", "flow_stop", "a"),
        Triple("stop", "flow_stop", "a"),
        Triple("torch", "torch", "a"),
        Triple("brightness", "brightness_set", "a"),
        Triple("volume", "volume_set", "a"),
        Triple("clipboard", "clipboard_set", "a"),
        Triple("camera", "take_photo", "a"),
        Triple("photo", "take_photo", "a"),
        Triple("plug-in", "shell_cmd", "a"),
        Triple("plugin", "shell_cmd", "a"),
        Triple("expression", "calc_var", "a"),
        Triple("condition", "if_var", "c"),
        Triple("decision", "if_var", "c"),
        Triple("loop", "loop_count", "a"),
        Triple("for each", "for_each", "a"),
        Triple("share", "share_text", "a"),
        Triple("url", "open_url", "a"),
        Triple("alarm", "set_alarm", "a"),
        Triple("timer", "set_timer", "a"),
        Triple("calendar", "calendar_event", "a"),
        Triple("email", "send_email", "a"),
        Triple("wallpaper", "set_wallpaper", "a"),
        Triple("screen lock", "lock_screen", "a"),
        Triple("wake", "wake_screen", "a")
    )

    fun isAutomateFlo(bytes: ByteArray): Boolean {
        if (bytes.size < 4) {
            return false
        }
        val head = bytes.take(128).toByteArray()
        return String(head, Charsets.ISO_8859_1).contains("LAFl")
    }

    /** All printable runs (ASCII + UTF-16LE), length >= 4, in order. */
    fun extractStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val ascii = StringBuilder()
        fun flushAscii() {
            if (ascii.length >= 4) {
                out.add(ascii.toString())
            }
            ascii.setLength(0)
        }
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 32..126) {
                ascii.append(b.toChar())
            } else {
                flushAscii()
            }
            i++
        }
        flushAscii()
        // UTF-16LE: printable low byte followed by 0x00, run >= 4.
        val uni = StringBuilder()
        fun flushUni() {
            if (uni.length >= 4) {
                out.add(uni.toString())
            }
            uni.setLength(0)
        }
        var j = 0
        while (j + 1 < bytes.size) {
            val lo = bytes[j].toInt() and 0xFF
            val hi = bytes[j + 1].toInt() and 0xFF
            if (hi == 0 && lo in 32..126) {
                uni.append(lo.toChar())
                j += 2
            } else {
                flushUni()
                j++
            }
        }
        flushUni()
        return out
    }

    fun importBytes(bytes: ByteArray, fileName: String): ImportResult? {
        if (!isAutomateFlo(bytes)) {
            return null
        }
        val strings = extractStrings(bytes)
        val mapped = ArrayList<MappedBlock>()
        val unmapped = ArrayList<String>()
        val seenSources = HashSet<String>()
        for (s in strings) {
            val lower = s.lowercase()
            var hit: Triple<String, String, String>? = null
            for (kw in KEYWORDS) {
                if (lower.contains(kw.first)) {
                    hit = kw
                    break
                }
            }
            if (hit != null) {
                // Same source string twice = the same block's
                // name stored twice, not two blocks.
                if (seenSources.add(s)) {
                    mapped.add(MappedBlock(s, hit.second, hit.third))
                }
            } else if (looksBlockLike(lower)) {
                if (seenSources.add(s)) {
                    unmapped.add(s)
                }
            }
        }
        if (mapped.isEmpty()) {
            // Nothing recognizable: still import nothing, but
            // report the strings so the user can see why.
            val flow = emptyFlow(fileName)
            flow.put(
                "import_report",
                "No Automate block keywords found in " + strings.size + " strings."
            )
            return ImportResult(flow, emptyList(), unmapped, strings.size)
        }
        val flow = JSONObject()
        flow.put("id", "f" + System.currentTimeMillis().toString())
        val base = fileName.substringBeforeLast(".").ifEmpty { "Imported flow" }
        flow.put("name", "Imported: " + base)
        val nodes = JSONArray()
        val edges = JSONArray()
        var prevId: String? = null
        for ((idx, block) in mapped.withIndex()) {
            val x = 240f + (idx % 4) * 460f
            val y = 240f + (idx / 4) * 300f
            val node = FlowFlow.newNode(
                block.kind, block.labType,
                FlowFlow.defaultParam(block.labType), x, y
            )
            nodes.put(node)
            val id = node.optString("id")
            if (prevId != null) {
                edges.put(FlowFlow.newEdge(prevId, id, "any"))
            }
            prevId = id
        }
        flow.put("nodes", nodes)
        flow.put("edges", edges)
        val report = StringBuilder()
        report.append("Mapped " + mapped.size + " block(s): ")
        report.append(mapped.joinToString(", ") { it.source.take(40) })
        report.append(". Unmapped block-like: " + unmapped.size)
        if (unmapped.isNotEmpty()) {
            report.append(": ")
            report.append(unmapped.joinToString(", ") { it.take(40) })
        }
        report.append(". Edges are file-order chains (best effort); " +
            "open the flow in the editor to fix nodes and edges.")
        flow.put("import_report", report.toString())
        return ImportResult(flow, mapped, unmapped, strings.size)
    }

    private fun emptyFlow(fileName: String): JSONObject {
        val flow = JSONObject()
        flow.put("id", "f" + System.currentTimeMillis().toString())
        flow.put("name", "Imported: " + fileName.substringBeforeLast("."))
        flow.put("nodes", JSONArray())
        flow.put("edges", JSONArray())
        return flow
    }

    /** Heuristic: strings that smell like block identifiers. */
    private fun looksBlockLike(lower: String): Boolean {
        if (lower.length > 80) {
            return false
        }
        return lower.contains("llamalab") ||
            lower.contains("automate") ||
            lower.contains("block") ||
            lower.contains("com.") ||
            (lower.contains(" ") && lower.split(" ").size == 2 &&
                lower[0].isLetter() && lower.all { it.isLetter() || it == ' ' })
    }
}
