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
        val time: Long,
        val id: String = "",
        val code: String = "",
        val extra: String = "",
        val pinned: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type); put("title", title); put("body", body)
            put("from", from); put("time", time); put("id", id)
            put("code", code); put("extra", extra); put("pinned", pinned)
        }
        companion object {
            fun fromJson(o: JSONObject) = Item(
                o.optString("type", "INFO"),
                o.optString("title", ""),
                o.optString("body", ""),
                o.optString("from", ""),
                o.optLong("time", 0L),
                o.optString("id", ""),
                o.optString("code", ""),
                o.optString("extra", ""),
                o.optBoolean("pinned", false)
            )
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "inbox.jsonl")

    private fun writeAll(ctx: Context, items: List<Item>) {
        val f = file(ctx)
        f.writeText(items.joinToString("\n") { it.toJson().toString() } +
            if (items.isEmpty()) "" else "\n")
    }

    @Synchronized
    fun add(ctx: Context, item: Item) {
        try {
            val items = all(ctx).toMutableList()
            items.add(0, item)
            // Cap at 500: drop the oldest non-pinned items first.
            while (items.size > 500) {
                val idx = items.indexOfLast { !it.pinned }
                if (idx < 0) break
                items.removeAt(idx)
            }
            writeAll(ctx, items)
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
    fun delete(ctx: Context, target: Item) {
        try {
            val items = all(ctx).filterNot {
                it.time == target.time && it.title == target.title && it.id == target.id
            }
            writeAll(ctx, items)
        } catch (_: Exception) { }
    }

    @Synchronized
    fun setPinned(ctx: Context, target: Item, pinned: Boolean) {
        try {
            val items = all(ctx).map {
                if (it.time == target.time && it.title == target.title && it.id == target.id)
                    it.copy(pinned = pinned) else it
            }
            // Pinned items float to the top, keeping their relative order.
            writeAll(ctx, items.sortedByDescending { it.pinned })
        } catch (_: Exception) { }
    }

    @Synchronized
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }
}
