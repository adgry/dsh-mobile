package com.dshmobile.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XML here is shaped like what Word actually writes — namespaced tags, `xml:space` on runs,
 * text split across runs mid-sentence because of spell-check or formatting.
 */
class WordDocsTest {

    @Test
    fun `runs split mid-sentence are joined back into one line`() {
        val xml = """
            <w:document><w:body>
              <w:p><w:r><w:t xml:space="preserve">项目进度</w:t></w:r><w:r><w:t>报告</w:t></w:r></w:p>
              <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        assertEquals("项目进度报告\n第二段", ooxmlProseText(xml).trim())
    }

    @Test
    fun `tabs and line breaks inside a paragraph survive`() {
        val xml = """<w:p><w:r><w:t>甲</w:t><w:tab/><w:t>乙</w:t><w:br/><w:t>丙</w:t></w:r></w:p>"""
        assertEquals("甲\t乙\n丙", ooxmlProseText(xml).trim())
    }

    @Test
    fun `entities are decoded rather than left as markup`() {
        val xml = """<w:p><w:r><w:t>a &amp; b &lt;tag&gt; &#65; &#x42;</w:t></w:r></w:p>"""
        assertEquals("a & b <tag> A B", ooxmlProseText(xml).trim())
    }

    /** Field codes hold directives like PAGE or HYPERLINK, which are not prose. */
    @Test
    fun `field codes are left out`() {
        val xml = """
            <w:p><w:r><w:t>见第</w:t></w:r>
            <w:r><w:instrText> PAGE  \* MERGEFORMAT </w:instrText></w:r>
            <w:r><w:t>页</w:t></w:r></w:p>
        """.trimIndent()
        assertEquals("见第页", ooxmlProseText(xml).trim())
    }

    @Test
    fun `text in shapes and text boxes is picked up`() {
        val xml = """<w:p><w:r><mc:AlternateContent><w:txbxContent>
            <w:p><w:r><w:t>图注</w:t></w:r></w:p>
            </w:txbxContent></mc:AlternateContent></w:r></w:p>"""
        assertEquals("图注", ooxmlProseText(xml).trim())
    }

    @Test
    fun `a run of empty paragraphs collapses instead of piling up`() {
        val xml = """
            <w:p><w:r><w:t>上</w:t></w:r></w:p>
            <w:p/><w:p/><w:p/><w:p/>
            <w:p><w:r><w:t>下</w:t></w:r></w:p>
        """.trimIndent()
        assertEquals("上\n\n下", ooxmlProseText(xml).trim())
    }

    /** Table cells become their own lines; column layout is not guessed at. */
    @Test
    fun `table cells each become a line`() {
        val xml = """
            <w:tbl><w:tr>
              <w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>张三</w:t></w:r></w:p></w:tc>
            </w:tr></w:tbl>
        """.trimIndent()
        assertEquals("姓名\n张三", ooxmlProseText(xml).trim())
    }

    @Test
    fun `markup outside text runs never leaks into the output`() {
        val xml = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/><w:rPr><w:b/></w:rPr></w:pPr>
            <w:r><w:rPr><w:b/></w:rPr><w:t>标题</w:t></w:r></w:p>
        """.trimIndent()
        assertEquals("标题", ooxmlProseText(xml).trim())
        assertFalse("Heading1" in ooxmlProseText(xml))
    }

    @Test
    fun `a truncated part does not throw`() {
        assertTrue(ooxmlProseText("<w:p><w:r><w:t>半个").isNotEmpty())
        assertEquals("", ooxmlProseText("").trim())
        assertEquals("", ooxmlProseText("<<<>>>").trim())
    }
}
