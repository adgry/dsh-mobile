package com.dshmobile.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XML shapes here are what Excel and PowerPoint actually write. */
class OfficeDocsTest {

    private val shared = listOf("城市", "销量", "苏州", "无锡")

    @Test
    fun `the string table is read entry by entry`() {
        val xml = """
            <sst count="4" uniqueCount="4">
              <si><t>城市</t></si>
              <si><t xml:space="preserve">销量 </t></si>
              <si><r><rPr><b/></rPr><t>苏</t></r><r><t>州</t></r></si>
            </sst>
        """.trimIndent()
        assertEquals(listOf("城市", "销量", "苏州"), sharedStrings(xml))
    }

    @Test
    fun `a sheet becomes tab separated rows with shared strings resolved`() {
        val xml = """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>42</v></c></row>
              <row r="3"><c r="A3" t="s"><v>3</v></c><c r="B3"><v>17</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        assertEquals("城市\t销量\n苏州\t42\n无锡\t17", sheetText(xml, shared))
    }

    /** A gap must stay a gap, or every later column shifts under the wrong header. */
    @Test
    fun `a hole in the middle of a row keeps the columns aligned`() {
        val xml = """
            <sheetData>
              <row r="1"><c r="A1"><v>1</v></c><c r="C1"><v>3</v></c></row>
            </sheetData>
        """.trimIndent()
        assertEquals("1\t\t3", sheetText(xml, emptyList()))
    }

    @Test
    fun `inline strings and formula results are both read`() {
        val xml = """
            <sheetData>
              <row r="1"><c r="A1" t="inlineStr"><is><t>直接写的字</t></is></c></row>
              <row r="2"><c r="A2" t="str"><f>CONCAT(1,2)</f><v>12</v></c></row>
            </sheetData>
        """.trimIndent()
        assertEquals("直接写的字\n12", sheetText(xml, emptyList()))
    }

    @Test
    fun `blank rows are dropped instead of leaving gaps`() {
        val xml = """
            <sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c></row>
              <row r="2"/><row r="3"><c r="A3"/></row>
              <row r="4"><c r="A4" t="s"><v>2</v></c></row>
            </sheetData>
        """.trimIndent()
        assertEquals("城市\n苏州", sheetText(xml, shared))
    }

    @Test
    fun `a cell holding a newline stays on its own row`() {
        val xml = """<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>上
下</t></is></c><c r="B1"><v>9</v></c></row></sheetData>"""
        assertEquals("上 下\t9", sheetText(xml, emptyList()))
    }

    @Test
    fun `sheet names are read in workbook order`() {
        val xml = """
            <workbook><sheets>
              <sheet name="八月" sheetId="1" r:id="rId1"/>
              <sheet name="九月 &amp; 十月" sheetId="2" r:id="rId2"/>
            </sheets></workbook>
        """.trimIndent()
        assertEquals(listOf("八月", "九月 & 十月"), sheetNames(xml))
    }

    /** Slide text is the same run shape as Word's, so the shared scanner reads it. */
    @Test
    fun `slide text comes out paragraph by paragraph`() {
        val xml = """
            <p:sld><p:cSld><p:spTree><p:sp><p:txBody>
              <a:p><a:r><a:t>季度目标</a:t></a:r></a:p>
              <a:p><a:r><a:t>把折扣审批</a:t></a:r><a:r><a:t>收回总监</a:t></a:r></a:p>
            </p:txBody></p:sp></p:spTree></p:cSld></p:sld>
        """.trimIndent()
        assertEquals("季度目标\n把折扣审批收回总监", ooxmlProseText(xml).trim())
    }

    @Test
    fun `column references beyond Z still resolve`() {
        val xml = """<sheetData><row r="1"><c r="AA1"><v>27</v></c></row></sheetData>"""
        assertTrue(sheetText(xml, emptyList()).endsWith("27"))
        assertEquals(26, sheetText(xml, emptyList()).count { it == '\t' })
    }
}
