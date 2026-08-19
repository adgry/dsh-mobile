package com.dshmobile.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokensTest {

    /**
     * Calibration anchor: the gateway billed `prompt_tokens = 10` for this exact 10-character
     * Chinese prompt, so one token per CJK character is the right shape.
     */
    @Test
    fun `chinese counts about one token per character`() {
        assertEquals(10, TokenEstimate.ofText("用中文说三个字的问候"))
    }

    @Test
    fun `latin text counts about four characters per token`() {
        // 29 characters -> ceil(29 / 4) = 8 tokens.
        assertEquals(8, TokenEstimate.ofText("reply with the single word ok"))
    }

    @Test
    fun `estimate errs high rather than low against a billed request`() {
        // The billed figure for the Chinese prompt above, envelope included, was 10.
        val estimate = TokenEstimate.ofMessage("用中文说三个字的问候")
        assertTrue("estimate $estimate should not undercount", estimate >= 10)
        assertTrue("estimate $estimate should stay within 1.5x", estimate <= 15)
    }

    @Test
    fun `empty text is free but a message still carries envelope`() {
        assertEquals(0, TokenEstimate.ofText(""))
        assertEquals(TokenEstimate.MESSAGE_OVERHEAD, TokenEstimate.ofMessage(""))
    }

    @Test
    fun `attachment cost is added on top of the text`() {
        val withImage = TokenEstimate.ofMessage("看这个", attachmentTokens = TokenEstimate.IMAGE_TOKENS)
        assertEquals(TokenEstimate.MESSAGE_OVERHEAD + 3 + TokenEstimate.IMAGE_TOKENS, withImage)
    }

    @Test
    fun `mixed scripts add up per run`() {
        // 4 CJK + 8 latin/space characters -> 4 + ceil(8/4) = 6
        assertEquals(6, TokenEstimate.ofText("中文混排 abc def"))
    }

    @Test
    fun `latin accents and emoji are not billed as wide script`() {
        assertTrue(TokenEstimate.ofText("café") <= 2)
    }

    @Test
    fun `a decimal-looking multiple of 1024 keeps its decimal reading`() {
        // 128000 is divisible by both 1000 and 1024; the advertised figure is 128K, not 125K.
        assertEquals("128K", formatTokenBudget(128_000))
    }

    @Test
    fun `power of two budgets use the binary idiom`() {
        assertEquals("1M", formatTokenBudget(1_048_576))
        assertEquals("256K", formatTokenBudget(262_144))
        assertEquals("32K", formatTokenBudget(32_768))
    }

    @Test
    fun `decimal budgets keep their decimal look`() {
        assertEquals("200K", formatTokenBudget(200_000))
        assertEquals("128K", formatTokenBudget(128_000))
    }

    @Test
    fun `zero renders as nothing`() {
        assertEquals("", formatTokenBudget(0))
    }
}
