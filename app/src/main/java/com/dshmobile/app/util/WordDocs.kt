package com.dshmobile.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the text out of a `.docx`.
 *
 * Only the main document part is read: headers, footers, comments and tracked changes are not what
 * someone attaching a document wants a model to answer about. The legacy binary `.doc` really is out
 * of reach, and says so instead of failing vaguely.
 */
private const val DOCUMENT_PART = "word/document.xml"

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

fun isDocx(context: Context, uri: Uri): Boolean {
    if (context.contentResolver.getType(uri).orEmpty().lowercase() == DOCX_MIME) return true
    return documentName(context, uri).endsWith(".docx", ignoreCase = true)
}

/** The formats that predate OOXML: a zip-and-XML reader cannot help with these. */
fun isLegacyOfficeBinary(context: Context, uri: Uri): Boolean {
    val name = documentName(context, uri).lowercase()
    return listOf(".doc", ".xls", ".ppt").any { name.endsWith(it) }
}

suspend fun readDocx(context: Context, uri: Uri): DocumentText? = withContext(Dispatchers.IO) {
    runCatching {
        val name = documentName(context, uri)
        val xml = context.contentResolver.openInputStream(uri)?.use { stream ->
            readZipEntry(stream, DOCUMENT_PART)
        } ?: return@runCatching null
        if (xml.isEmpty()) return@runCatching null

        documentTextOf(
            name = name,
            text = ooxmlProseText(String(xml, Charsets.UTF_8)),
            byteCount = xml.size.toLong(),
        )
    }.getOrNull()
}
