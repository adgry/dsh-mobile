package com.dshmobile.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads `.xlsx` and `.pptx` the same way [readDocx] reads Word: pull the parts that carry text out
 * of the zip and scan their XML.
 *
 * A spreadsheet becomes tab-separated rows, which is the shape a model reads best and the one it can
 * quote back. Formulas are not evaluated — the cached result Excel stores alongside them is used,
 * which is what the file was showing on screen anyway.
 */
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val PPTX_MIME =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** Guards against one runaway sheet or deck filling the whole prompt before others are read. */
private const val MAX_ROWS_PER_SHEET = 5_000

fun isXlsx(context: Context, uri: Uri): Boolean {
    if (context.contentResolver.getType(uri).orEmpty().lowercase() == XLSX_MIME) return true
    return documentName(context, uri).endsWith(".xlsx", ignoreCase = true)
}

fun isPptx(context: Context, uri: Uri): Boolean {
    if (context.contentResolver.getType(uri).orEmpty().lowercase() == PPTX_MIME) return true
    return documentName(context, uri).endsWith(".pptx", ignoreCase = true)
}

suspend fun readXlsx(context: Context, uri: Uri): DocumentText? = withContext(Dispatchers.IO) {
    runCatching {
        val name = documentName(context, uri)
        val parts = context.contentResolver.openInputStream(uri)?.use { stream ->
            readZipEntries(stream) { entry ->
                entry == "xl/sharedStrings.xml" ||
                    entry == "xl/workbook.xml" ||
                    (entry.startsWith("xl/worksheets/") && entry.endsWith(".xml"))
            }
        } ?: return@runCatching null
        if (parts.isEmpty()) return@runCatching null

        val shared = parts["xl/sharedStrings.xml"]
            ?.let { sharedStrings(String(it, Charsets.UTF_8)) }
            .orEmpty()
        val titles = parts["xl/workbook.xml"]
            ?.let { sheetNames(String(it, Charsets.UTF_8)) }
            .orEmpty()

        val sheets = parts.keys
            .filter { it.startsWith("xl/worksheets/sheet") }
            .sortedBy { path -> path.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }

        val text = buildString {
            sheets.forEachIndexed { index, path ->
                val rows = sheetText(String(parts.getValue(path), Charsets.UTF_8), shared)
                if (rows.isBlank()) return@forEachIndexed
                // Sheet order in workbook.xml matches the sheetN.xml order in every writer seen,
                // but a mismatch must not mislabel data, so an unmatched sheet stays unnamed.
                val title = titles.getOrNull(index)
                if (sheets.size > 1 || title != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("## ").append(title ?: "工作表 ${index + 1}").append('\n')
                }
                append(rows)
            }
        }

        documentTextOf(name, text, parts.values.sumOf { it.size }.toLong())
    }.getOrNull()
}

suspend fun readPptx(context: Context, uri: Uri): DocumentText? = withContext(Dispatchers.IO) {
    runCatching {
        val name = documentName(context, uri)
        val parts = context.contentResolver.openInputStream(uri)?.use { stream ->
            readZipEntries(stream) { entry ->
                entry.startsWith("ppt/slides/slide") && entry.endsWith(".xml")
            }
        } ?: return@runCatching null
        if (parts.isEmpty()) return@runCatching null

        val slides = parts.keys.sortedBy { path ->
            path.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE
        }

        val text = buildString {
            slides.forEachIndexed { index, path ->
                val body = ooxmlProseText(String(parts.getValue(path), Charsets.UTF_8)).trim()
                if (body.isBlank()) return@forEachIndexed
                if (isNotEmpty()) append("\n\n")
                // Slide numbers matter: someone asking about "the third slide" needs them present.
                append("## 第 ").append(index + 1).append(" 页\n").append(body)
            }
        }

        documentTextOf(name, text, parts.values.sumOf { it.size }.toLong())
    }.getOrNull()
}

/**
 * The workbook's string table. Cells hold an index into this rather than their own text, so without
 * it a spreadsheet reads as a grid of numbers.
 */
internal fun sharedStrings(xml: String): List<String> {
    val strings = mutableListOf<String>()
    // Each <si> is one entry, whose text may be split across <r> runs by formatting.
    Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { match ->
        strings += ooxmlProseText(match.groupValues[1]).replace("\n", " ").trim()
    }
    return strings
}

/** Sheet names in workbook order, for labelling each table. */
internal fun sheetNames(xml: String): List<String> =
    Regex("<sheet\\b[^>]*>").findAll(xml)
        .mapNotNull { attribute(it.value, "name")?.let(::unescapeXml) }
        .toList()

/**
 * One worksheet as tab-separated rows.
 *
 * Cells are placed by their own column reference, so a gap in the middle of a row stays a gap and
 * the columns still line up under their headers. Entirely empty rows are dropped rather than
 * emitting runs of blank lines.
 */
internal fun sheetText(xml: String, shared: List<String>): String {
    val rows = StringBuilder()
    var cells = sortedMapOf<Int, String>()
    var rowCount = 0

    var cellRef: String? = null
    var cellType: String? = null
    var buffer = StringBuilder()
    var capturing = false

    fun flushRow() {
        if (cells.isNotEmpty()) {
            val width = cells.lastKey()
            val line = (0..width).joinToString("\t") { cells[it].orEmpty() }.trimEnd('\t')
            if (line.isNotBlank()) {
                if (rows.isNotEmpty()) rows.append('\n')
                rows.append(line)
                rowCount++
            }
        }
        cells = sortedMapOf()
    }

    var index = 0
    while (index < xml.length && rowCount < MAX_ROWS_PER_SHEET) {
        val open = xml.indexOf('<', index)
        if (open < 0) break
        if (capturing && open > index) buffer.append(unescapeXml(xml.substring(index, open)))
        val close = xml.indexOf('>', open + 1)
        if (close < 0) break

        val raw = xml.substring(open + 1, close)
        val closing = raw.startsWith("/")
        val selfClosing = raw.endsWith("/")

        when (localName(raw)) {
            "row" -> if (closing) flushRow()
            "c" -> when {
                closing || selfClosing -> {
                    val value = buffer.toString().trim()
                    val resolved = when (cellType) {
                        // A shared-string cell holds an index; an inline or formula cell holds text.
                        "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        else -> value
                    }
                    if (resolved.isNotEmpty()) {
                        cells[columnIndex(cellRef)] = resolved.replace("\n", " ")
                    }
                    buffer = StringBuilder()
                    capturing = false
                    cellRef = null
                    cellType = null
                }
                else -> {
                    cellRef = attribute(raw, "r")
                    cellType = attribute(raw, "t")
                    buffer = StringBuilder()
                }
            }
            // <v> is the stored value; <t> is inline-string text. Both are the cell's content.
            "v", "t" -> if (!selfClosing) capturing = !closing
        }

        index = close + 1
    }
    flushRow()
    return rows.toString()
}

/** `C5` → 2. A missing reference lands in the next free slot at the end of the row. */
private fun columnIndex(reference: String?): Int {
    val letters = reference.orEmpty().takeWhile { it.isLetter() }.uppercase()
    if (letters.isEmpty()) return Int.MAX_VALUE - 1
    var value = 0
    for (character in letters) value = value * 26 + (character - 'A' + 1)
    return value - 1
}
