package com.mthtamim.phonelink

/**
 * v2.11: conservative on-device phishing heuristic for incoming SMS.
 * Fires only when a message carries a link AND at least two red flags,
 * so ordinary bank/OTP messages (which warn users themselves) stay quiet.
 */
object Spam {

    private val linkHints = listOf(
        "http://", "https://", "www.", "bit.ly", "tinyurl", "t.co/",
        "rb.gy", "cutt.ly", "shorturl", ".xyz", ".top/", ".click")

    private val flags = listOf(
        "winner", "won a", "prize", "lottery", "bonus cash", "free money",
        "account blocked", "account suspended", "verify now", "verify your",
        "confirm your password", "send the code", "share the code",
        "share your otp", "send otp", "urgent action", "claim now",
        "লটারি", "পুরস্কার জিতেছেন", "অ্যাকাউন্ট বন্ধ", "কোডটি পাঠান",
        "কোড পাঠিয়ে", "এখনই যাচাই")

    fun isSuspicious(body: String): Boolean {
        val b = body.lowercase()
        if (linkHints.none { b.contains(it) }) return false
        return flags.count { b.contains(it) } >= 2
    }
}
