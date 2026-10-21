package com.mthtamim.phonelink

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Receiver-side inbox: every forwarded event, newest first, capped at 500. */
object History {
    data class Item(
        val type: String,
        val title: String,
        val body: String,
        val from: String,
        val time: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type); put("title", title); put("body", body)
            put("from", from); put("time", time)
        }
        companion object {
            fun fromJson(o: JSONObject) = Item(
                o.optString("type", "INFO"),
                o.optString("title", ""),
                o.optString("body", ""),
                o.optString("from", ""),
                o.optLong("time", 0L)
            )
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "inbox.jsonl")

    @Synchronized
    fun add(ctx: Context, item: Item) {
        try {
            val f = file(ctx)
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            lines.add(0, item.toJson().toString())
            while (lines.size > 500) lines.removeAt(lines.size - 1)
            f.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun all(ctx: Context): List<Item> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull {
            try { Item.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        }
    }

    @Synchronized
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }
}
