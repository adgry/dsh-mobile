package com.dshmobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshmobile.app.util.decodeThumbnail
import java.io.File
import androidx.compose.foundation.Image as ComposeImage

/**
 * Decodes a stored attachment off the main thread and caches it for the composition's lifetime.
 * Kept deliberately dependency-free — one bitmap per thumbnail is cheaper than an image loader.
 */
@Composable
fun AttachmentThumb(
    file: File,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    corner: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.absolutePath) {
        value = decodeThumbnail(file)
    }

    Box(modifier = modifier.size(size + if (onRemove != null) 6.dp else 0.dp)) {
        Box(
            modifier = Modifier
                .size(size)
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(corner))
                .background(scheme.surfaceContainerHighest)
                .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(corner))
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> ComposeImage(
                    bitmap = image,
                    contentDescription = "附件图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
                file.exists() -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = scheme.primary,
                )
                else -> Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "图片已丢失",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .border(1.dp, scheme.outlineVariant, CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "移除图片",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(13.dp),
                )
            }
        }
    }
}
