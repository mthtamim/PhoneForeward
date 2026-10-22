package com.mthtamim.phonelink

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Card-style inbox rows (v2.5.1): icon chip + title + body + meta line.
 *  An empty list renders one placeholder row with the empty text.
 *  v2.17: rows gain quick actions (copy code / call back / reply) and
 *  bKash payment messages get a highlighted card with the amount and
 *  TrxID pulled out of the real message body — nothing is invented,
 *  the buttons only appear when the data is actually in the item. */
class InboxAdapter(
    private val ctx: Context,
    private val items: List<History.Item>,
    private val emptyText: String,
    private val onAction: (History.Item, String) -> Unit = { _, _ -> }
) : BaseAdapter() {

    private val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    companion object {
        const val ACT_COPY = "copy"
        const val ACT_COPY_CODE = "copycode"
        const val ACT_CALL = "call"
        const val ACT_COPY_NUMBER = "copynum"
        const val ACT_REPLY = "reply"

        private val AMOUNT_RE =
            Regex("""(?i)(?:tk|৳|bdt)\s*([\d,]+(?:\.\d{1,2})?)""")
        private val AMOUNT_RE2 =
            Regex("""(?i)([\d,]+(?:\.\d{1,2})?)\s*(?:tk|taka)""")
        private val TRX_RE = Regex("""(?i)trx\s*id[:\s]*([a-z0-9]{6,})""")
        private val OTP_RE = Regex("""\b(\d{4,8})\b""")

        fun bkashAmount(item: History.Item): String? {
            val text = item.title + " " + item.body
            if (!text.lowercase().contains("bkash")) return null
            return AMOUNT_RE.find(item.body)?.groupValues?.get(1)
                ?: AMOUNT_RE2.find(item.body)?.groupValues?.get(1)
        }

        fun bkashTrx(item: History.Item): String? {
            val text = item.title + " " + item.body
            if (!text.lowercase().contains("bkash")) return null
            return TRX_RE.find(text)?.groupValues?.get(1)
        }

        /** The copyable code for an item: the ingest-extracted code,
         *  a TrxID, or an OTP-shaped number in the body. */
        fun extractCode(item: History.Item): String =
            item.code.ifEmpty {
                bkashTrx(item) ?: OTP_RE.find(item.body)?.groupValues?.get(1) ?: ""
            }

        fun isBkash(item: History.Item): Boolean =
            (item.title + " " + item.body).lowercase().contains("bkash")
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) ==
                cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun dayLabel(t: Long): String {
        val now = System.currentTimeMillis()
        val yesterday = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        return when {
            sameDay(t, now) -> ctx.getString(R.string.day_today)
            sameDay(t, yesterday) -> ctx.getString(R.string.day_yesterday)
            else -> SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
                .format(Date(t))
        }
    }

    override fun getCount(): Int = if (items.isEmpty()) 1 else items.size
    override fun getItem(position: Int): Any = items.getOrNull(position) ?: emptyText
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx)
            .inflate(R.layout.item_inbox, parent, false)
        val card = v.findViewById<MaterialCardView>(R.id.cardItem)
        val icon = v.findViewById<TextView>(R.id.tvItemIcon)
        val title = v.findViewById<TextView>(R.id.tvItemTitle)
        val body = v.findViewById<TextView>(R.id.tvItemBody)
        val meta = v.findViewById<TextView>(R.id.tvItemMeta)
        val dayView = v.findViewById<TextView>(R.id.tvItemDay)
        val badge = v.findViewById<TextView>(R.id.tvItemBadge)
        val amount = v.findViewById<TextView>(R.id.tvItemAmount)
        val rowActions = v.findViewById<View>(R.id.rowItemActions)
        val btnPrimary = v.findViewById<TextView>(R.id.btnItemPrimary)
        val btnSecondary = v.findViewById<TextView>(R.id.btnItemSecondary)
        val item = items.getOrNull(position)
        // v2.11: date-group header on the first row of each day
        val prev = items.getOrNull(position - 1)
        if (item != null && (prev == null || !sameDay(prev.time, item.time))) {
            dayView.visibility = View.VISIBLE
            dayView.text = dayLabel(item.time)
        } else {
            dayView.visibility = View.GONE
        }
        if (item == null) {
            icon.text = "📭"
            title.text = emptyText
            body.text = ""
            meta.text = ""
            badge.visibility = View.GONE
            amount.visibility = View.GONE
            rowActions.visibility = View.GONE
            resetCard(card)
            return v
        }
        icon.text = when (item.type) {
            Relay.TYPE_SMS -> "✉️"; Relay.TYPE_NOTIF -> "🔔"; Relay.TYPE_CALL -> "📞"
            Relay.TYPE_BATTERY -> "🔋"; Relay.TYPE_APP -> "📦"; Relay.TYPE_LOCATION -> "📍"
            Relay.TYPE_SYSTEM -> "⚙️"; Relay.TYPE_TEST -> "🧪"; else -> "•"
        }
        title.text = (if (item.pinned) "📌 " else "") +
            (if (item.starred) "⭐ " else "") + item.title
        body.text = item.body
        meta.text = "${item.type} • ${item.from} • ${fmt.format(Date(item.time))}"

        // ----- v2.17: bKash highlight + quick actions -----
        val bkash = isBkash(item)
        if (bkash) {
            card.setCardBackgroundColor(
                MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimaryContainer))
            card.strokeColor = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary)
            badge.visibility = View.VISIBLE
            badge.text = ctx.getString(R.string.bkash_badge)
            val amt = bkashAmount(item)
            if (amt != null) {
                amount.visibility = View.VISIBLE
                amount.text = "৳$amt"
            } else amount.visibility = View.GONE
        } else {
            resetCard(card)
            badge.visibility = View.GONE
            amount.visibility = View.GONE
        }

        val hasTel = item.extra.startsWith("tel:")
        val code = extractCode(item)
        val replyable = (hasTel && (item.type == Relay.TYPE_SMS ||
            item.type == Relay.TYPE_CALL)) ||
            (item.type == Relay.TYPE_NOTIF && item.extra.startsWith("notif:"))
        var primary: Pair<String, String>? = null
        var secondary: Pair<String, String>? = null
        when {
            item.type == Relay.TYPE_CALL && hasTel -> {
                primary = ctx.getString(R.string.item_call_back) to ACT_CALL
                secondary = ctx.getString(R.string.item_copy_number) to ACT_COPY_NUMBER
            }
            bkash && bkashTrx(item) != null -> {
                primary = ctx.getString(R.string.item_copy_trxid) to ACT_COPY_CODE
                if (replyable) secondary =
                    ctx.getString(R.string.item_reply) to ACT_REPLY
            }
            code.isNotEmpty() -> {
                primary = ctx.getString(R.string.item_copy_code) to ACT_COPY_CODE
                if (replyable) secondary =
                    ctx.getString(R.string.item_reply) to ACT_REPLY
            }
            replyable -> {
                primary = ctx.getString(R.string.item_reply) to ACT_REPLY
                secondary = ctx.getString(R.string.item_copy) to ACT_COPY
            }
            else -> {
                primary = ctx.getString(R.string.item_copy) to ACT_COPY
            }
        }
        if (primary != null) {
            rowActions.visibility = View.VISIBLE
            btnPrimary.text = primary.first
            val act = primary.second
            btnPrimary.setOnClickListener { onAction(item, act) }
            if (secondary != null) {
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = secondary.first
                val act2 = secondary.second
                btnSecondary.setOnClickListener { onAction(item, act2) }
            } else {
                btnSecondary.visibility = View.GONE
                btnSecondary.setOnClickListener(null)
            }
        } else {
            rowActions.visibility = View.GONE
            btnPrimary.setOnClickListener(null)
            btnSecondary.setOnClickListener(null)
        }
        return v
    }

    private fun resetCard(card: MaterialCardView) {
        card.setCardBackgroundColor(
            MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface))
        card.strokeColor =
            MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurfaceVariant)
    }
}
