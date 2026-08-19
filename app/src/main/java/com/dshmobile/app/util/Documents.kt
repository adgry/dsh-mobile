package com.dshmobile.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Text pulled out of an attached file, ready to inline into a prompt. */
class DocumentText(
    val text: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val truncated: Boolean,
)

/** Roughly 60k tokens of Chinese, or 240k of English — plenty of file, bounded memory. */
private const val MAX_DOCUMENT_CHARS = 60_000

/** Only the first slice is examined to decide whether a file is text at all. */
private const val SNIFF_BYTES = 4_096

/**
 * Reads an attached file as text when it plausibly *is* text.
 *
 * The platform has no document parser, so this covers the case that actually matters on a phone:
 * source files, logs, configs, CSV, markdown — things a model can read directly. Binary formats are
 * rejected rather than mangled into replacement characters, and PDFs go through
 * [renderPdfPages] instead because the platform can only rasterise them.
 */
suspend fun readTextDocument(context: Context, uri: Uri): DocumentText? = withContext(Dispatchers.IO) {
    runCatching {
        val name = queryName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (looksBinary(mime, name)) return@runCatching null

        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            // Read a little past the char cap; UTF-8 is at most 4 bytes per char but text files
            // are overwhelmingly ASCII, so this keeps the common case from over-reading.
            val limit = MAX_DOCUMENT_CHARS * 2L
            while (out.size() < limit) {
                val read = stream.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null

        // NUL bytes early in a file are the clearest signal it isn't text.
        if (bytes.take(SNIFF_BYTES).any { it == 0.toByte() }) return@runCatching null

        val decoded = String(bytes, Charsets.UTF_8)
        // A decode littered with U+FFFD means the bytes weren't UTF-8 text.
        val bad = decoded.take(SNIFF_BYTES).count { it == '�' }
        if (bad > SNIFF_BYTES / 20) return@runCatching null

        val truncated = decoded.length > MAX_DOCUMENT_CHARS
        DocumentText(
            text = if (truncated) decoded.take(MAX_DOCUMENT_CHARS) else decoded,
            displayName = name,
            mimeType = mime.ifBlank { "text/plain" },
            byteCount = bytes.size.toLong(),
            truncated = truncated,
        )
    }.getOrNull()
}

/**
 * Rasterises a PDF's pages so a vision model can read them. `PdfRenderer` is the only PDF facility
 * Android ships and it produces pixels, not text — so a PDF is treated as a short stack of images.
 */
suspend fun renderPdfPages(
    context: Context,
    uri: Uri,
    maxPages: Int = 8,
    targetWidth: Int = 1240,
): List<DecodedImage> = withContext(Dispatchers.IO) {
    runCatching {
        val name = queryName(context, uri)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            renderPages(descriptor, name, maxPages, targetWidth)
        }.orEmpty()
    }.getOrElse { emptyList() }
}

private fun renderPages(
    descriptor: ParcelFileDescriptor,
    name: String,
    maxPages: Int,
    targetWidth: Int,
): List<DecodedImage> = PdfRenderer(descriptor).use { renderer ->
    val pages = minOf(renderer.pageCount, maxPages)
    (0 until pages).mapNotNull { index ->
        runCatching {
            renderer.openPage(index).use { page ->
                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                val width = targetWidth
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Pages are transparent where nothing is drawn; paper should be white, not black.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val bytes = out.toByteArray()
                bitmap.recycle()
                DecodedImage(
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    width = width,
                    height = height,
                    displayName = "$name 第${index + 1}页",
                )
            }
        }.getOrNull()
    }
}

fun isPdf(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.equals("application/pdf", ignoreCase = true)) return true
    return queryName(context, uri).endsWith(".pdf", ignoreCase = true)
}

private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico", "tiff",
    "zip", "gz", "tar", "7z", "rar", "jar", "apk", "aab", "dex", "so", "dll", "exe", "bin",
    "mp3", "wav", "flac", "ogg", "m4a", "aac", "mp4", "mov", "mkv", "avi", "webm",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "key", "pages", "numbers", "epub",
    "ttf", "otf", "woff", "woff2", "psd", "sketch", "db", "sqlite", "pdf",
)

private fun looksBinary(mimeType: String, name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension in BINARY_EXTENSIONS) return true
    val mime = mimeType.lowercase()
    if (mime.isEmpty()) return false
    if (mime.startsWith("text/")) return false
    // Structured formats that are text despite not being under text/*.
    val textish = listOf(
        "json", "xml", "yaml", "yml", "toml", "csv", "javascript", "typescript",
        "x-sh", "x-python", "x-java", "x-kotlin", "sql", "graphql", "svg+xml", "x-ndjson",
    )
    if (textish.any { mime.contains(it) }) return false
    return mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") ||
        mime.startsWith("application/")
}

private fun queryName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
        .ifBlank { "文件" }
