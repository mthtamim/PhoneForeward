package com.mthtamim.phonelink

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Card-style inbox rows (v2.5.1): icon chip + title + body + meta line.
 *  An empty list renders one placeholder row with the empty text. */
class InboxAdapter(
    private val ctx: Context,
    private val items: List<History.Item>,
    private val emptyText: String
) : BaseAdapter() {

    private val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    override fun getCount(): Int = if (items.isEmpty()) 1 else items.size
    override fun getItem(position: Int): Any = items.getOrNull(position) ?: emptyText
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx)
            .inflate(R.layout.item_inbox, parent, false)
        val icon = v.findViewById<TextView>(R.id.tvItemIcon)
        val title = v.findViewById<TextView>(R.id.tvItemTitle)
        val body = v.findViewById<TextView>(R.id.tvItemBody)
        val meta = v.findViewById<TextView>(R.id.tvItemMeta)
        val item = items.getOrNull(position)
        if (item == null) {
            icon.text = "📭"
            title.text = emptyText
            body.text = ""
            meta.text = ""
            return v
        }
        icon.text = when (item.type) {
            Relay.TYPE_SMS -> "✉️"; Relay.TYPE_NOTIF -> "🔔"; Relay.TYPE_CALL -> "📞"
            Relay.TYPE_BATTERY -> "🔋"; Relay.TYPE_APP -> "📦"; Relay.TYPE_LOCATION -> "📍"
            Relay.TYPE_SYSTEM -> "⚙️"; Relay.TYPE_TEST -> "🧪"; else -> "•"
        }
        title.text = (if (item.pinned) "📌 " else "") + item.title
        body.text = item.body
        meta.text = "${item.type} • ${item.from} • ${fmt.format(Date(item.time))}"
        return v
    }
}
