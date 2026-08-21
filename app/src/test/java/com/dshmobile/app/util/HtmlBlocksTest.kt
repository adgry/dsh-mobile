package com.dshmobile.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBlocksTest {

    private val page = "<!DOCTYPE html>\n<html><head><title>销售看板</title></head><body>x</body></html>"

    @Test
    fun `a labelled html fence is previewable`() {
        assertTrue(isPreviewableHtml("html", page))
        assertTrue(isPreviewableHtml("HTML", page))
        assertTrue(isPreviewableHtml("htm", "<html><body>hi</body></html>"))
    }

    /** Models often omit the fence label when asked for "an HTML file". */
    @Test
    fun `an unlabelled fence is sniffed`() {
        assertTrue(isPreviewableHtml("", page))
        assertTrue(isPreviewableHtml("", "<html lang=\"zh\"><body>hi</body></html>"))
        assertTrue(isPreviewableHtml("", "<svg viewBox=\"0 0 10 10\"><rect/></svg>"))
    }

    /** A fragment renders as a near-blank screen, which reads as a broken feature. */
    @Test
    fun `a fragment is not offered`() {
        assertFalse(isPreviewableHtml("", "<div class=\"card\">just a piece</div>"))
        assertFalse(isPreviewableHtml("", "<p>hello</p>"))
        assertFalse(isPreviewableHtml("", ""))
    }

    @Test
    fun `other languages are never previewed even when they contain markup`() {
        assertFalse(isPreviewableHtml("kotlin", "val x = \"<html><body>no</body></html>\""))
        assertFalse(isPreviewableHtml("python", page))
        assertFalse(isPreviewableHtml("json", page))
    }

    @Test
    fun `leading blank lines and comments do not hide the doctype`() {
        assertTrue(isPreviewableHtml("", "\n\n  <!DOCTYPE html><html></html>"))
    }

    @Test
    fun `the file name comes from the page title`() {
        assertEquals("销售看板.html", htmlFileName(page))
        assertEquals("预览.html", htmlFileName("<html><body>no title</body></html>"))
    }

    @Test
    fun `a title that cannot be a file name is cleaned up`() {
        assertEquals("a b.html", htmlFileName("<title>  a\n  b </title>"))
        assertEquals("report.html", htmlFileName("<title>report/*?</title>"))
        assertEquals("预览.html", htmlFileName("<title>   </title>"))
        assertTrue(htmlFileName("<title>${"长".repeat(200)}</title>").length <= 53)
    }
}
