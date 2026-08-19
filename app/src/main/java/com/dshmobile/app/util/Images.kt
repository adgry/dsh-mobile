package com.dshmobile.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class DecodedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val displayName: String,
)

/**
 * Reads an image the user picked and re-encodes it small enough to inline as a `data:` URL.
 * Phone photos are 4000px wide and several megabytes; a vision model gains nothing from that and
 * the request would blow past the gateway's token budget, so everything is capped at [maxDimension].
 */
suspend fun decodeForUpload(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1280,
    jpegQuality: Int = 85,
): DecodedImage? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null

        val scaled = scaleWithin(decoded, maxDimension)
        if (scaled !== decoded) decoded.recycle()

        // JPEG would flatten transparency to black, so alpha images stay PNG.
        val png = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        scaled.compress(
            if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            if (png) 100 else jpegQuality,
            out,
        )
        val result = DecodedImage(
            bytes = out.toByteArray(),
            mimeType = if (png) "image/png" else "image/jpeg",
            width = scaled.width,
            height = scaled.height,
            displayName = queryDisplayName(context, uri),
        )
        scaled.recycle()
        result
    }.getOrNull()
}

suspend fun decodeThumbnail(file: File, maxDimension: Int = 640): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }.getOrNull()
    }

fun toDataUrl(bytes: ByteArray, mimeType: String): String =
    "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= maxDimension && h / 2 >= maxDimension) {
        w /= 2
        h /= 2
        sample *= 2
    }
    // Also step down when only one side is oversized by a lot.
    while (maxOf(w, h) / 2 >= maxDimension) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

private fun scaleWithin(source: Bitmap, maxDimension: Int): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= maxDimension) return source
    val ratio = maxDimension.toFloat() / longest
    val width = (source.width * ratio).toInt().coerceAtLeast(1)
    val height = (source.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, width, height, true)
}

private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull().orEmpty().ifBlank { "图片" }
