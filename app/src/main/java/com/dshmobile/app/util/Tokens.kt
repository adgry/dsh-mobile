package com.dshmobile.app.util

/**
 * Local token estimation. There is no tokenizer on the device and the gateway only reports usage
 * *after* a turn, but context trimming has to happen before the request is built — so this
 * approximates, deliberately erring high.
 *
 * Calibrated against real `prompt_tokens` from the gateway: a 10-character Chinese prompt billed 10
 * tokens, and a 29-character English one billed 11 including envelope overhead. So CJK and other
 * non-Latin scripts count as roughly one token per character, Latin text as one per four, plus a
 * small per-message envelope.
 */
object TokenEstimate {

    /** Rough per-message envelope (role, delimiters) that providers bill on top of content. */
    const val MESSAGE_OVERHEAD = 4

    /**
     * A downscaled 1280px image costs on the order of a thousand tokens on most vision models.
     * Overstating it is the safe direction: it only makes trimming more conservative.
     */
    const val IMAGE_TOKENS = 1_200

    fun ofText(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var latinChars = 0
        for (ch in text) {
            if (isWideScript(ch)) wide++ else latinChars++
        }
        // Latin runs compress to ~4 characters per token; round up so short strings aren't free.
        val latinTokens = (latinChars + 3) / 4
        return wide + latinTokens
    }

    /** [attachmentTokens] is the pre-computed cost of whatever is attached; see `Attachment.tokens`. */
    fun ofMessage(text: String, attachmentTokens: Int = 0): Int =
        MESSAGE_OVERHEAD + ofText(text) + attachmentTokens

    /**
     * CJK, kana, Hangul and the full-width forms bill about one token per character. Cyrillic,
     * Greek, Arabic and friends sit between the two extremes; counting them as wide keeps the
     * estimate on the safe side.
     */
    private fun isWideScript(ch: Char): Boolean {
        if (ch.code < 0x80) return false
        return when (ch.code) {
            in 0x0400..0x04FF -> true // Cyrillic
            in 0x0590..0x08FF -> true // Hebrew, Arabic, Syriac …
            in 0x0900..0x0DFF -> true // Indic
            in 0x0E00..0x0FFF -> true // Thai, Lao, Tibetan
            in 0x1100..0x11FF -> true // Hangul Jamo
            in 0x2E80..0xA4CF -> true // CJK radicals through Yi
            in 0xA960..0xA97F -> true // Hangul Jamo Extended-A
            in 0xAC00..0xD7FF -> true // Hangul syllables
            in 0xF900..0xFAFF -> true // CJK compatibility ideographs
            in 0xFE30..0xFE4F -> true // CJK compatibility forms
            in 0xFF00..0xFF60 -> true // full-width forms
            in 0xFFE0..0xFFE6 -> true // full-width signs
            else -> false             // Latin-1 accents, symbols, emoji surrogates
        }
    }
}

/**
 * Token budgets read best in the idiom the provider advertises. A decimal window (200000) should
 * print "200K" and a power-of-two one (262144) "256K" — and the two overlap: 128000 is *also* a
 * multiple of 1024, so testing divisibility by 1024 first would render it "125K". Decimal is
 * therefore tried first, and only an inexact decimal split falls through to the binary reading.
 */
fun formatTokenBudget(tokens: Int): String = when {
    tokens <= 0 -> ""
    tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens % (1 shl 20) == 0 -> "${tokens / (1 shl 20)}M"
    tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens % 1024 == 0 -> "${tokens / 1024}K"
    tokens >= 1 shl 20 -> trimZero(tokens.toDouble() / (1 shl 20)) + "M"
    tokens >= 1_000 -> "${(tokens / 1000.0).toInt()}K"
    else -> tokens.toString()
}

private fun trimZero(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}
