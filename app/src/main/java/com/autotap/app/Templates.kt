package com.autotap.app

/**
 * Ready-made templates and the rule-based "বাংলায় লিখলেই script" builder.
 * The builder is fully offline: it reads the user's Bengali/English
 * description and turns common patterns (scroll interval, text tap, run
 * duration, repeat count) into steps. No AI service and no internet needed;
 * an "Ultimate" version may add a smarter generator later.
 */
object Templates {

    data class Template(val name: String, val desc: String, val build: (Int, Int) -> Script)

    fun all(screenW: Int, screenH: Int): List<Template> {
        val cx = screenW / 2
        return listOf(
            Template("আঁকা (সই/তারা)", "একটা তারা এঁকে দেবে - নিজের সই বানাতে রেকর্ডার দিয়ে পথ বদলে নাও") { w, h ->
                Script(Script.newId(), "আঁকা").apply {
                    val cxp = w / 2
                    val top = (h * 0.30).toInt()
                    val bl = (h * 0.62).toInt()
                    val br = (h * 0.62).toInt()
                    val pts = listOf(
                        intArrayOf(cxp, top),
                        intArrayOf((w * 0.28).toInt(), br),
                        intArrayOf((w * 0.74).toInt(), (h * 0.44).toInt()),
                        intArrayOf((w * 0.26).toInt(), (h * 0.44).toInt()),
                        intArrayOf((w * 0.72).toInt(), bl),
                        intArrayOf(cxp, top)
                    )
                    val text = pts.joinToString(";") { "${it[0]},${it[1]}" }
                    steps.add(Step(StepType.PATH, text = text, durationMs = 1600, delayAfterMs = 800))
                }
            },
            Template("Facebook স্ক্রল", "নিচে স্ক্রল, মাঝে মাঝে থামবে, অসীম লুপ") { w, h ->
                Script(Script.newId(), "Facebook স্ক্রল").apply {
                    humanLike = true
                    steps.add(Step(StepType.SWIPE, cx(w), (h * 0.72).toInt(), cx(w), (h * 0.30).toInt(), durationMs = 650, delayMinMs = 1200, delayMaxMs = 3200))
                }
            },
            Template("Shorts / Reels", "ভিডিও বদলাতে উপরে swipe, 8-20 সেকেন্ড পরপর") { w, h ->
                Script(Script.newId(), "Shorts / Reels").apply {
                    humanLike = true
                    steps.add(Step(StepType.SWIPE, cx(w), (h * 0.70).toInt(), cx(w), (h * 0.25).toInt(), durationMs = 350, delayMinMs = 8000, delayMaxMs = 20000))
                }
            },
            Template("বই / আর্টিকেল পড়া", "ধীরে স্ক্রল, প্রতি পেজে লম্বা বিরতি") { w, h ->
                Script(Script.newId(), "বই পড়া").apply {
                    steps.add(Step(StepType.SWIPE, cx(w), (h * 0.78).toInt(), cx(w), (h * 0.35).toInt(), durationMs = 900, delayMinMs = 6000, delayMaxMs = 12000))
                }
            },
            Template("গেম tap farming", "মাঝখানে দ্রুত tap, বিরতি চক্রসহ") { w, h ->
                Script(Script.newId(), "গেম tap farming").apply {
                    humanLike = true
                    workMin = 10
                    restMin = 1
                    steps.add(Step(StepType.TAP, cx(w), (h * 0.55).toInt(), durationMs = 60, delayAfterMs = 350))
                }
            },
            Template("পেজ শেষ হলে থামবে", "স্ক্রল + feed শেষ ধরা (স্ক্রিন-capture লাগবে)") { w, h ->
                Script(Script.newId(), "পেজ শেষ হলে থামবে").apply {
                    endDetect = true
                    steps.add(Step(StepType.SWIPE, cx(w), (h * 0.72).toInt(), cx(w), (h * 0.30).toInt(), durationMs = 650, delayAfterMs = 2500))
                }
            }
        )
    }

    private fun cx(w: Int) = w / 2

    // ---------- rule-based description builder ----------

    private val bnDigits = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    fun toAsciiDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(bnDigits[c] ?: c)
        return sb.toString()
    }

    private fun firstNumber(near: String, text: String): Int? {
        val idx = text.indexOf(near)
        if (idx < 0) return null
        val window = text.substring(idx, minOf(text.length, idx + 30))
        return Regex("\\d+").find(window)?.value?.toIntOrNull()
    }

    /**
     * Builds a script from a free description. Returns null when nothing
     * recognizable was found (the UI then asks for clearer wording).
     */
    fun fromDescription(raw: String, screenW: Int, screenH: Int): Script? {
        val text = toAsciiDigits(raw.lowercase())
        val cx = screenW / 2
        val script = Script(Script.newId(), "নতুন script")

        // Quoted word -> text tap / wait.
        val quoted = Regex("[\"'“”‘’](.+?)[\"'“”‘’]").find(raw)?.groupValues?.get(1)?.trim()
        val wantsScroll = text.contains("স্ক্রল") || text.contains("scroll") || text.contains("নিচে")
        val intervalSec = firstNumber("প্রতি", text) ?: firstNumber("পরপর", text) ?: firstNumber("every", text)
        val durationMin = firstNumber("মিনিট", text) ?: firstNumber("minute", text)
        val times = firstNumber("বার", text) ?: firstNumber("times", text)

        if (wantsScroll) {
            val delay = (intervalSec ?: 3) * 1000L
            script.steps.add(
                Step(
                    StepType.SWIPE, cx, (screenH * 0.72).toInt(), cx, (screenH * 0.30).toInt(),
                    durationMs = 650, delayMinMs = delay, delayMaxMs = delay + delay / 2
                )
            )
            script.humanLike = true
        }
        if (!quoted.isNullOrEmpty()) {
            script.steps.add(
                Step(StepType.TEXT_TAP, text = quoted, delayAfterMs = 1500)
            )
        }
        if (!text.contains("বারবার") && script.steps.isEmpty()) {
            // A lone tap request: "মাঝখানে tap করো" style.
            if (text.contains("tap") || text.contains("ট্যাপ") || text.contains("চাপ")) {
                val delay = (intervalSec ?: 1) * 1000L
                script.steps.add(Step(StepType.TAP, cx, screenH / 2, durationMs = 60, delayAfterMs = delay))
            }
        }
        if (script.steps.isEmpty()) return null
        if (durationMin != null && durationMin > 0) script.globalMinutes = durationMin
        if (times != null && times > 0) script.loopCount = times
        return script
    }
}
