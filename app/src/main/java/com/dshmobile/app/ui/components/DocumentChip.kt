package com.dshmobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.Attachment
import com.dshmobile.app.util.formatTokenCount

/** An attached text file: name, size in tokens, and whether it had to be cut short. */
@Composable
fun DocumentChip(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .widthIn(max = 240.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 168.dp)) {
            Text(
                text = attachment.label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    if (attachment.tokenEstimate > 0) add("≈${formatTokenCount(attachment.tokenEstimate)} token")
                    if (attachment.truncated) add("已截断")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "移除文件",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
