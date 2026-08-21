package com.dshmobile.app.util

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The pieces every Office format shares.
 *
 * A .docx, .xlsx and .pptx are all a zip of XML parts, so reading them needs no parser library and
 * no platform facility — the reason the earlier "a phone has no document parser" call was too
 * pessimistic. What differs between them is only which parts to read and which tags carry text.
 */

/** A part larger than this belongs to a file nobody is going to paste into a prompt anyway. */
internal const val MAX_PART_BYTES = 32 * 1024 * 1024

/** Reads selected entries out of a zip stream in one pass, without holding the archive in memory. */
internal fun readZipEntries(stream: InputStream, wanted: (String) -> Boolean): Map<String, ByteArray> {
    val parts = LinkedHashMap<String, ByteArray>()
    ZipInputStream(stream).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!wanted(entry.name)) {
                zip.closeEntry()
                continue
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < MAX_PART_BYTES) {
                val read = zip.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            parts[entry.name] = out.toByteArray()
            zip.closeEntry()
        }
    }
    return parts
}

internal fun readZipEntry(stream: InputStream, path: String): ByteArray? =
    readZipEntries(stream) { it == path }[path]

/**
 * Turns the prose parts of OOXML into plain text.
 *
 * Word runs (`w:t` in `w:p`) and PowerPoint runs (`a:t` in `a:p`) have the same shape once the
 * namespace prefix is ignored, so one scanner reads both. The markup is machine-written and only a
 * handful of elements carry visible text, so scanning the tags beats pulling in a parser — and
 * unlike `android.util.Xml` this runs in a plain unit test.
 *
 * Field codes (`w:instrText`) are skipped because they hold directives like PAGE, not prose. Table
 * structure is dropped: each cell's paragraphs become their own lines, which reads acceptably and
 * avoids guessing at column layout.
 */
internal fun ooxmlProseText(xml: String): String {
    val out = StringBuilder()
    var index = 0
    var depthInsideText = 0

    while (index < xml.length) {
        val open = xml.indexOf('<', index)
        if (open < 0) {
            // A part cut off mid-run still has its last words worth keeping.
            if (depthInsideText > 0) out.append(unescapeXml(xml.substring(index)))
            break
        }
        if (depthInsideText > 0 && open > index) {
            out.append(unescapeXml(xml.substring(index, open)))
        }
        val close = xml.indexOf('>', open + 1)
        if (close < 0) break

        val raw = xml.substring(open + 1, close)
        val closing = raw.startsWith("/")
        val selfClosing = raw.endsWith("/")

        when (localName(raw)) {
            "t" -> if (!selfClosing) {
                if (closing) depthInsideText = (depthInsideText - 1).coerceAtLeast(0) else depthInsideText++
            }
            "tab" -> if (!closing) out.append('\t')
            "br", "cr" -> if (!closing) out.append('\n')
            // A blank line is written as an empty self-closing paragraph, which still ends a line.
            "p" -> if (closing || selfClosing) out.append('\n')
        }

        index = close + 1
    }

    // An empty paragraph per blank line means runs of them pile up.
    return out.toString().replace(Regex("\n{3,}"), "\n\n")
}

/** The tag name without its namespace prefix, from the raw text between `<` and `>`. */
internal fun localName(raw: String): String =
    raw.trim('/', ' ', '\t', '\n', '\r')
        .substringBefore(' ')
        .substringBefore('/')
        .substringAfter(':')

/** One attribute off a raw tag. Values are quoted in every OOXML writer's output. */
internal fun attribute(raw: String, name: String): String? {
    val match = Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(raw)
    return match?.groupValues?.getOrNull(1)
}

internal fun unescapeXml(value: String): String {
    if ('&' !in value) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val amp = value.indexOf('&', index)
        if (amp < 0) {
            out.append(value, index, value.length)
            break
        }
        out.append(value, index, amp)
        val semicolon = value.indexOf(';', amp + 1)
        if (semicolon < 0 || semicolon - amp > 10) {
            out.append('&')
            index = amp + 1
            continue
        }
        when (val entity = value.substring(amp + 1, semicolon)) {
            "amp" -> out.append('&')
            "lt" -> out.append('<')
            "gt" -> out.append('>')
            "quot" -> out.append('"')
            "apos" -> out.append('\'')
            else -> {
                val code = when {
                    entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                    else -> null
                }
                if (code != null) out.appendCodePoint(code) else out.append('&').append(entity).append(';')
            }
        }
        index = semicolon + 1
    }
    return out.toString()
}

internal fun documentName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
        .ifBlank { "文档" }

/** Builds the attachment record shared by all three readers. */
internal fun documentTextOf(name: String, text: String, byteCount: Long): DocumentText? {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return null
    val truncated = trimmed.length > MAX_DOCUMENT_CHARS
    return DocumentText(
        text = if (truncated) trimmed.take(MAX_DOCUMENT_CHARS) else trimmed,
        displayName = name,
        // What is handed to the model is text, whatever the file on disk was.
        mimeType = "text/plain",
        byteCount = byteCount,
        truncated = truncated,
    )
}
