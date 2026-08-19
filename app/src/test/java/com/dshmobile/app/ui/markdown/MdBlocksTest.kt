package com.dshmobile.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdBlocksTest {

    @Test
    fun `fenced code keeps language and body`() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```")
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertTrue(code.closed)
    }

    /** The parser runs on a reply that is still arriving, so a half-written fence must render. */
    @Test
    fun `unterminated fence is an open code block, not stray backticks`() {
        val blocks = parseMarkdown("解释一下：\n\n```python\ndef f(x):\n    return x")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val code = blocks[1] as MdBlock.CodeBlock
        assertEquals("python", code.language)
        assertEquals("def f(x):\n    return x", code.code)
        assertEquals(false, code.closed)
    }

    @Test
    fun `backticks inside a fence are not treated as a close`() {
        val blocks = parseMarkdown("```\na = `b`\n```")
        val code = blocks.single() as MdBlock.CodeBlock
        assertEquals("a = `b`", code.code)
    }

    @Test
    fun `headings capture level and strip trailing hashes`() {
        val blocks = parseMarkdown("# One\n### Three ###")
        assertEquals(MdBlock.Heading(1, "One"), blocks[0])
        assertEquals(MdBlock.Heading(3, "Three"), blocks[1])
    }

    @Test
    fun `paragraph joins wrapped lines but stops at a list`() {
        val blocks = parseMarkdown("first line\nsecond line\n- item")
        assertEquals("first line\nsecond line", (blocks[0] as MdBlock.Paragraph).text)
        assertTrue(blocks[1] is MdBlock.ListBlock)
    }

    @Test
    fun `ordered list remembers its starting number`() {
        val blocks = parseMarkdown("3. three\n4. four")
        val list = blocks.single() as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `nested list becomes a child block of its item`() {
        val blocks = parseMarkdown("- outer\n    - inner\n- second")
        val list = blocks.single() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        val firstItem = list.items[0].children
        assertEquals("outer", (firstItem[0] as MdBlock.Paragraph).text)
        val nested = firstItem[1] as MdBlock.ListBlock
        assertEquals("inner", (nested.items.single().children.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `task list items expose their checked state`() {
        val blocks = parseMarkdown("- [x] done\n- [ ] pending")
        val list = blocks.single() as MdBlock.ListBlock
        assertEquals(true, list.items[0].checked)
        assertEquals(false, list.items[1].checked)
        assertEquals("done", (list.items[0].children.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `plain bullet has no checkbox state`() {
        val list = parseMarkdown("- just text").single() as MdBlock.ListBlock
        assertEquals(null, list.items.single().checked)
    }

    @Test
    fun `table parses header, alignment and rows`() {
        val blocks = parseMarkdown(
            """
            | 名称 | 值 | 说明 |
            |:---|---:|:---:|
            | a | 1 | 第一 |
            | b | 2 | 第二 |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("名称", "值", "说明"), table.header)
        assertEquals(listOf(MdAlign.START, MdAlign.END, MdAlign.CENTER), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("b", "2", "第二"), table.rows[1])
    }

    @Test
    fun `a pipe line without a divider stays a paragraph`() {
        val blocks = parseMarkdown("a | b\nnot a table")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `escaped pipe stays inside its cell`() {
        val table = parseMarkdown("| a | b |\n|---|---|\n| x \\| y | z |").single() as MdBlock.Table
        assertEquals(listOf("x \\| y", "z"), table.rows.single())
    }

    @Test
    fun `blockquote nests its own blocks`() {
        val quote = parseMarkdown("> **note**\n> - one").single() as MdBlock.Quote
        assertEquals("**note**", (quote.children[0] as MdBlock.Paragraph).text)
        assertTrue(quote.children[1] is MdBlock.ListBlock)
    }

    @Test
    fun `thematic breaks are recognised in all three spellings`() {
        val blocks = parseMarkdown("---\n\n***\n\n___")
        assertEquals(listOf(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule), blocks)
    }

    @Test
    fun `blank input yields nothing`() {
        assertEquals(emptyList<MdBlock>(), parseMarkdown("   \n\n  "))
    }

    /** Guards the paragraph fallback against the infinite loop a mis-classified line could cause. */
    @Test
    fun `pathological input terminates`() {
        val weird = "|\n>\n#\n-\n```\n~~~\n***\n1.\n"
        val blocks = parseMarkdown(weird)
        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun `crlf input parses the same as lf`() {
        val a = parseMarkdown("# t\r\n\r\nbody\r\n")
        val b = parseMarkdown("# t\n\nbody\n")
        assertEquals(b, a)
    }
}
