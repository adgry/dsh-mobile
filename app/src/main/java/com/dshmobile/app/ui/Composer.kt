package com.dshmobile.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.Attachment
import com.dshmobile.app.ui.components.AttachmentThumb
import com.dshmobile.app.ui.components.DocumentChip
import com.dshmobile.app.ui.components.SmallIconButton
import java.io.File

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    streaming: Boolean,
    attachments: List<Attachment>,
    attachmentFile: (Attachment) -> File,
    onRemoveAttachment: (String) -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    attaching: Boolean,
    sendOnEnter: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val canSend = (value.text.isNotBlank() || attachments.isNotEmpty()) && !streaming

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        ) {
            AnimatedVisibility(visible = attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        if (attachment.isImage) {
                            AttachmentThumb(
                                file = attachmentFile(attachment),
                                size = 64.dp,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        } else {
                            DocumentChip(
                                attachment = attachment,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (attaching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = scheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        // Always tappable: a disabled paperclip reads as "this app can't do
                        // attachments". The sheet explains what each kind needs instead.
                        SmallIconButton(
                            icon = Icons.Outlined.Add,
                            contentDescription = "添加文件或图片",
                            onClick = onAttach,
                            enabled = !streaming,
                            size = 44.dp,
                            iconSize = 24.dp,
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(scheme.surfaceContainerHigh, RoundedCornerShape(22.dp))
                        .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = "发消息…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { if (canSend) onSend() },
                        ),
                        singleLine = false,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp),
                    )
                }

                if (value.text.isBlank() && attachments.isEmpty() && !streaming) {
                    SmallIconButton(
                        icon = Icons.Outlined.Mic,
                        contentDescription = "语音输入",
                        onClick = onVoice,
                        size = 40.dp,
                        iconSize = 21.dp,
                        tint = scheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(4.dp))

                SendButton(
                    streaming = streaming,
                    enabled = canSend || streaming,
                    onClick = { if (streaming) onStop() else if (canSend) onSend() },
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    streaming: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        streaming -> scheme.errorContainer
        enabled -> scheme.primary
        else -> scheme.surfaceContainerHighest
    }
    val tint = when {
        streaming -> scheme.error
        enabled -> scheme.onPrimary
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) Icons.Rounded.Stop else Icons.Filled.ArrowUpward,
            contentDescription = if (streaming) "停止生成" else "发送",
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
    }
}
