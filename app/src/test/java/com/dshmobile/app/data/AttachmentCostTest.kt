package com.dshmobile.app.data

import com.dshmobile.app.util.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCostTest {

    @Test
    fun `an image carries the flat image rate`() {
        val image = Attachment(fileName = "a.jpg", tokenEstimate = TokenEstimate.IMAGE_TOKENS)
        assertEquals(TokenEstimate.IMAGE_TOKENS, image.tokens)
    }

    /** Conversations stored before attachments carried an estimate must not count as free. */
    @Test
    fun `a legacy image without an estimate falls back to the image rate`() {
        val legacy = Attachment(fileName = "a.jpg", tokenEstimate = 0)
        assertEquals(TokenEstimate.IMAGE_TOKENS, legacy.tokens)
    }

    @Test
    fun `a document is charged its measured text cost`() {
        val text = "字".repeat(500)
        val doc = Attachment(
            kind = AttachmentKind.DOCUMENT,
            fileName = "a.txt",
            charCount = text.length,
            tokenEstimate = TokenEstimate.ofText(text),
        )
        assertEquals(500, doc.tokens)
    }

    @Test
    fun `a legacy document with no estimate is not silently priced as an image`() {
        val doc = Attachment(kind = AttachmentKind.DOCUMENT, fileName = "a.txt")
        assertEquals(0, doc.tokens)
    }

    @Test
    fun `a list sums its attachments`() {
        val list = listOf(
            Attachment(fileName = "a.jpg", tokenEstimate = 1_200),
            Attachment(kind = AttachmentKind.DOCUMENT, fileName = "b.txt", tokenEstimate = 800),
        )
        assertEquals(2_000, list.tokens)
    }

    @Test
    fun `a big attached file pushes older turns out of the window`() {
        val fileTokens = 5_000
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "第一个问题"),
            ChatMessage(role = Role.ASSISTANT, content = "第一个回答"),
            ChatMessage(
                role = Role.USER,
                content = "看看这个文件",
                attachments = listOf(
                    Attachment(
                        kind = AttachmentKind.DOCUMENT,
                        fileName = "big.txt",
                        tokenEstimate = fileTokens,
                    ),
                ),
            ),
        )
        val plan = planContextWindow(messages, budget = 4_096, systemCost = 0)
        assertEquals("the file's own turn must survive", 2, plan.startIndex)
        assertEquals(2, plan.droppedCount)
        assertTrue(plan.usedTokens >= fileTokens)
    }

    @Test
    fun `documents and images are both visible through the kind flags`() {
        val image = Attachment(fileName = "a.jpg")
        val doc = Attachment(kind = AttachmentKind.DOCUMENT, fileName = "b.txt")
        assertTrue(image.isImage && !image.isDocument)
        assertTrue(doc.isDocument && !doc.isImage)
        assertEquals("图片", image.label)
        assertEquals("readme.md", Attachment(kind = AttachmentKind.DOCUMENT, fileName = "x", displayName = "readme.md").label)
    }
}
