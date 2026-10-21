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
        val pinned: Boolean = false,
        val starred: Boolean = false,
        val archived: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type); put("title", title); put("body", body)
            put("from", from); put("time", time); put("id", id)
            put("code", code); put("extra", extra); put("pinned", pinned)
            put("starred", starred); put("archived", archived)
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
                o.optBoolean("pinned", false),
                o.optBoolean("starred", false),
                o.optBoolean("archived", false)
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
            var items = all(ctx).toMutableList()
            items.add(0, item)
            // Auto-clean: drop items older than the keep window (pinned survive).
            val days = Config.inboxKeepDays(ctx)
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - days * 86400000L
                items = items.filter { it.pinned || it.time >= cutoff }.toMutableList()
            }
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

    // v2.11: star / archive flags (same matching rule as setPinned)
    @Synchronized
    fun setStarred(ctx: Context, target: Item, starred: Boolean) {
        try {
            writeAll(ctx, all(ctx).map {
                if (it.time == target.time && it.title == target.title && it.id == target.id)
                    it.copy(starred = starred) else it
            })
        } catch (_: Exception) { }
    }

    @Synchronized
    fun setArchived(ctx: Context, target: Item, archived: Boolean) {
        try {
            writeAll(ctx, all(ctx).map {
                if (it.time == target.time && it.title == target.title && it.id == target.id)
                    it.copy(archived = archived) else it
            })
        } catch (_: Exception) { }
    }

    /** Items received today (for the launcher badge number). */
    fun todayCount(ctx: Context): Int {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        return all(ctx).count { it.time >= start }
    }

    /** Removes every item carrying this relay id (delete-on-both-phones). */
    @Synchronized
    fun removeById(ctx: Context, id: String) {
        if (id.isEmpty()) return
        try { writeAll(ctx, all(ctx).filterNot { it.id == id }) } catch (_: Exception) { }
    }

    @Synchronized
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }

    /** Whole inbox as one JSON document, for the backup file. */
    fun exportJson(ctx: Context): String {
        val arr = org.json.JSONArray()
        for (item in all(ctx)) arr.put(item.toJson())
        return arr.toString()
    }

    /** Replaces the inbox with a backup document. Returns the item count. */
    @Synchronized
    fun importJson(ctx: Context, text: String): Int {
        return try {
            val arr = org.json.JSONArray(text)
            val items = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                items.add(Item.fromJson(arr.getJSONObject(i)))
            }
            writeAll(ctx, items.sortedByDescending { it.time }.take(500))
            items.size
        } catch (_: Exception) { -1 }
    }
}
