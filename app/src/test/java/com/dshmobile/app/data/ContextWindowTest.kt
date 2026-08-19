package com.dshmobile.app.data

import com.dshmobile.app.util.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    private fun user(text: String) = ChatMessage(role = Role.USER, content = text)
    private fun assistant(text: String) = ChatMessage(role = Role.ASSISTANT, content = text)

    /** Six turns of roughly 100 tokens of Chinese each. */
    private fun transcript(turns: Int): List<ChatMessage> = buildList {
        repeat(turns) { i ->
            add(user("问题$i" + "字".repeat(96)))
            add(assistant("回答$i" + "字".repeat(96)))
        }
    }

    @Test
    fun `a generous budget keeps the whole transcript`() {
        val messages = transcript(3)
        val plan = planContextWindow(messages, budget = 1_000_000, systemCost = 0)
        assertEquals(0, plan.startIndex)
        assertEquals(0, plan.droppedCount)
        assertEquals(plan.totalTokens, plan.usedTokens)
    }

    @Test
    fun `a tight budget drops the oldest turns`() {
        val messages = transcript(6)
        val plan = planContextWindow(messages, budget = 300, systemCost = 0)
        assertTrue("expected some messages dropped", plan.droppedCount > 0)
        assertTrue("total should exceed what was kept", plan.totalTokens > plan.usedTokens)
        // The budget may be overshot by the one prompt pulled in to keep the turn coherent.
        val widest = messages.maxOf { TokenEstimate.ofMessage(it.content, it.attachments.size) }
        assertTrue(
            "kept cost ${plan.usedTokens} should stay within the budget plus one message",
            plan.usedTokens <= 300 + widest,
        )
    }

    @Test
    fun `opening on a reply pulls in its prompt instead of skipping it`() {
        val messages = listOf(
            user("很久以前的问题"),
            assistant("很久以前的回答"),
            user("第二个问题"),
            assistant("第二个回答"),
        )
        // Enough for the last reply alone, not for the pair.
        val onlyLastReply = TokenEstimate.ofMessage(messages[3].content)
        val plan = planContextWindow(messages, budget = onlyLastReply, systemCost = 0)
        assertEquals("should step back to the prompt", 2, plan.startIndex)
        assertEquals(Role.USER, messages[plan.startIndex].role)
    }

    @Test
    fun `the window always starts on a user message`() {
        val messages = transcript(6)
        listOf(120, 300, 700, 1500).forEach { budget ->
            val plan = planContextWindow(messages, budget = budget, systemCost = 0)
            assertEquals(
                "budget $budget must not open mid-reply",
                Role.USER,
                messages[plan.startIndex].role,
            )
        }
    }

    @Test
    fun `the newest message survives even when it alone overflows`() {
        val messages = listOf(
            user("旧" .repeat(200)),
            assistant("旧回答".repeat(100)),
            user("这条很长".repeat(500)),
        )
        val plan = planContextWindow(messages, budget = 10, systemCost = 0)
        assertEquals(2, plan.startIndex)
        assertEquals(2, plan.droppedCount)
        assertTrue(plan.usedTokens > 10)
    }

    @Test
    fun `the system prompt is charged against the budget`() {
        val messages = transcript(4)
        val without = planContextWindow(messages, budget = 500, systemCost = 0)
        val with = planContextWindow(messages, budget = 500, systemCost = 300)
        assertTrue(
            "a costly system prompt must leave room for less history",
            with.droppedCount > without.droppedCount,
        )
    }

    @Test
    fun `an empty conversation reports only the system prompt`() {
        val plan = planContextWindow(emptyList(), budget = 1000, systemCost = 42)
        assertEquals(0, plan.startIndex)
        assertEquals(0, plan.droppedCount)
        assertEquals(42, plan.usedTokens)
        assertEquals(42, plan.totalTokens)
    }

    @Test
    fun `images are charged at the image rate`() {
        val withImage = listOf(
            user("看图").copy(attachments = listOf(Attachment(fileName = "a.jpg"))),
        )
        val plan = planContextWindow(withImage, budget = 1_000_000, systemCost = 0)
        assertTrue(
            "an attached image should dominate the cost",
            plan.usedTokens >= TokenEstimate.IMAGE_TOKENS,
        )
    }

    @Test
    fun `a single trailing assistant message is still sent`() {
        // Regenerating from a pruned transcript can leave no user message at all.
        val messages = listOf(assistant("只有回答"))
        val plan = planContextWindow(messages, budget = 1000, systemCost = 0)
        assertEquals(0, plan.startIndex)
        assertTrue(plan.usedTokens > 0)
    }
}
