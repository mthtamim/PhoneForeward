package com.mthtamim.phonelink

import android.content.Context

/**
 * v2.11: parses mobile-money / bank SMS already sitting in the inbox
 * (bKash, Nagad, Rocket, Upay, banks) into a small expense ledger.
 * Nothing leaves the phone; it is a read-only view over History items.
 */
object Expenses {

    data class Txn(
        val time: Long,
        val service: String,
        val incoming: Boolean,
        val amount: Double,
        val title: String
    )

    private val amountRx = Regex(
        "(?:Tk|BDT)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|" +
        "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:Tk|BDT|টাকা)")

    private val services = listOf(
        "bkash" to "bKash", "বিকাশ" to "bKash",
        "nagad" to "Nagad", "নগদ" to "Nagad",
        "rocket" to "Rocket", "রকেট" to "Rocket",
        "upay" to "Upay", "dbbl" to "DBBL",
        "bank" to "Bank", "ব্যাংক" to "Bank")

    private val inWords = listOf(
        "received", "cash in", "cashin", "credited", "credit ",
        "পেয়েছেন", "জমা হয়েছে", "এসেছে")
    private val outWords = listOf(
        "payment", "cash out", "cashout", "send money", "debited",
        "debit ", "পেমেন্ট", "ক্যাশ আউট", "খরচ হয়েছে", "পাঠিয়েছেন")

    fun parse(ctx: Context): List<Txn> {
        val out = mutableListOf<Txn>()
        for (item in History.all(ctx)) {
            if (item.type != Relay.TYPE_SMS) continue
            val hay = (item.title + " " + item.body).lowercase()
            val service = services.firstOrNull { hay.contains(it.first) }?.second
                ?: continue
            val m = amountRx.find(item.body) ?: continue
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] })
                .replace(",", "")
            val amount = raw.toDoubleOrNull() ?: continue
            if (amount <= 0) continue
            val incoming = when {
                inWords.any { hay.contains(it) } -> true
                outWords.any { hay.contains(it) } -> false
                else -> continue
            }
            out.add(Txn(item.time, service, incoming, amount, item.title))
        }
        return out.sortedByDescending { it.time }
    }
}
