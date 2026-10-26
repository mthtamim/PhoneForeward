package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flow JSON helpers. Flow shape:
 * {id, name, nodes:[{id, kind(t/c/a), type, param, x, y, s}],
 *  edges:[{from, to, branch(yes/no/any)}]}
 * Coordinates are canvas pixels in the editor's FlowCanvasView.
 * "s" is the per-node size factor (v1.1.0, default 1.0, 0.6-2.0):
 * the node's box is nodeW*s by nodeH*s, and canvas zoom scales
 * everything on top of it.
 */
object FlowFlow {

    private var counter = 0

    fun newId(): String {
        counter++
        return "n" + System.currentTimeMillis().toString() + "_" + counter
    }

    fun newNode(kind: String, type: String, param: String, x: Float, y: Float): JSONObject {
        val node = JSONObject()
        node.put("id", newId())
        node.put("kind", kind)
        node.put("type", type)
        node.put("param", param)
        node.put("x", x.toDouble())
        node.put("y", y.toDouble())
        node.put("s", 1.0)
        return node
    }

    fun newEdge(from: String, to: String, branch: String): JSONObject {
        val edge = JSONObject()
        edge.put("from", from)
        edge.put("to", to)
        edge.put("branch", branch)
        return edge
    }

    fun nodesOf(flow: JSONObject): JSONArray {
        var arr = flow.optJSONArray("nodes")
        if (arr == null) {
            arr = JSONArray()
            flow.put("nodes", arr)
        }
        return arr
    }

    fun edgesOf(flow: JSONObject): JSONArray {
        var arr = flow.optJSONArray("edges")
        if (arr == null) {
            arr = JSONArray()
            flow.put("edges", arr)
        }
        return arr
    }

    fun nodeById(flow: JSONObject, id: String): JSONObject? {
        val arr = flow.optJSONArray("nodes") ?: return null
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            if (n.optString("id") == id) {
                return n
            }
        }
        return null
    }

    fun nodeByIdFromArray(nodes: JSONArray, id: String): JSONObject? {
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (n.optString("id") == id) {
                return n
            }
        }
        return null
    }

    fun removeNode(flow: JSONObject, id: String) {
        val nodes = JSONArray()
        val oldNodes = flow.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until oldNodes.length()) {
            val n = oldNodes.optJSONObject(i) ?: continue
            if (n.optString("id") != id) {
                nodes.put(n)
            }
        }
        flow.put("nodes", nodes)
        val edges = JSONArray()
        val oldEdges = flow.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until oldEdges.length()) {
            val e = oldEdges.optJSONObject(i) ?: continue
            if (e.optString("from") != id && e.optString("to") != id) {
                edges.put(e)
            }
        }
        flow.put("edges", edges)
    }

    fun title(ctx: Context, node: JSONObject): String {
        return FlowCatalog.label(ctx, node.optString("kind"), node.optString("type"))
    }

    /** Sensible default params so a fresh node is runnable. */
    fun defaultParam(type: String): String {
        return when (type) {
            "time_at" -> "09:00"
            "time_interval" -> "30"
            // v1.4.0 trigger defaults.
            "sound_level" -> "500"
            "temp" -> "30"
            "humidity" -> "70"
            "heart_rate" -> "120"
            "accel" -> "2.0"
            "hinge_angle" -> "90"
            "app_recent" -> ""
            "wifi_rssi" -> "-70"
            "location_at" -> "0|0|100"
            "content_changed" -> "content://media/external/images/media"
            "settings_changed" -> "screen_brightness"
            "audio_muted" -> "music"
            "battery_below" -> "20"
            "local_notify", "toast_local" -> "PhoneLink flow"
            "set_var" -> "x=1"
            "wait" -> "5"
            "loop_count" -> "3"
            "for_each" -> "items|item"
            "fork" -> ""
            "stop_flow" -> ""
            "set_local" -> "x=1"
            "list_append" -> "items|value"
            "list_get" -> "items|0|list_value"
            "dict_get" -> "data|key|dict_value"
            "text_replace" -> "text|from|to"
            "text_split" -> "text|,|items"
            "text_join" -> "items|,|text"
            "random_int" -> "1|100|random"
            "dialog_input" -> "Title|default|dialog_input"
            "dialog_message" -> "Title|Hello"
            "dialog_progress" -> "Working|5"
            "date_picker" -> "Pick a date|picked_date"
            "time_picker" -> "Pick a time|picked_time"
            "file_list" -> "|file_list"
            "file_exists" -> "file.txt|file_exists"
            "unzip" -> "archive.zip|out"
            "clipboard_get" -> "clipboard_text"
            "bt_connect" -> ""
            "wifi_connect" -> "SSID|password"
            "hotspot_set" -> "on"
            "battery_saver_set" -> "on"
            "data_saver_set" -> "on"
            "dark_set" -> "on"
            "listen" -> "speech_text|Speak now"
            "get_location" -> ""
            "screenshot" -> ""
            "file_pick" -> "picked_file"
            "dir_pick" -> "picked_dir"
            "focuslock_control" -> "cmd=lock_full"
            // v1.4.3.
            "qr_scan" -> "60"
            "wol" -> ""
            "ping" -> "8.8.8.8|4|2000"
            // v1.5.0: formerly dropped actions.
            "redial_control" -> "number|30|20"
            "mqtt_publish" -> "topic|payload|0|0"
            "qr_show" -> ""
            "torch_level" -> "100"
            "perm_grant", "perm_revoke" -> "com.example.app|android.permission.CAMERA"
            "reboot_device" -> ""
            "notify_peer" -> "Title|message"
            "command_peer" -> "command|arg"
            else -> ""
        }
    }
}
