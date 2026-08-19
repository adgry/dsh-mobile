package com.dshmobile.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.Attachment
import com.dshmobile.app.data.AttachmentKind
import com.dshmobile.app.data.ChatMessage
import com.dshmobile.app.data.Role
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.ui.components.TypingDots
import com.dshmobile.app.ui.components.rememberPulseAlpha
import com.dshmobile.app.ui.components.AttachmentThumb
import com.dshmobile.app.ui.components.DocumentChip
import com.dshmobile.app.ui.markdown.MarkdownText
import com.dshmobile.app.ui.theme.MonoFamily
import com.dshmobile.app.util.formatDuration
import com.dshmobile.app.util.formatTimestamp
import com.dshmobile.app.util.formatTokenCount
import java.io.File

@Composable
fun MessageItem(
    message: ChatMessage,
    streaming: Boolean,
    showReasoning: Boolean,
    attachmentFile: (Attachment) -> File,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.USER -> UserMessage(
            message = message,
            attachmentFile = attachmentFile,
            onCopy = onCopy,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
        )
        else -> AssistantMessage(
            message = message,
            streaming = streaming,
            showReasoning = showReasoning,
            onCopy = onCopy,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
            onContinue = onContinue,
            modifier = modifier,
        )
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    attachmentFile: (Attachment) -> File,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var actionsVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (message.attachments.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                message.attachments.filter { it.kind == AttachmentKind.IMAGE }.takeLast(4)
                    .forEach { attachment ->
                        AttachmentThumb(file = attachmentFile(attachment), size = 84.dp)
                    }
            }
        }

        message.attachments.filter { it.isDocument }.forEach { attachment ->
            DocumentChip(
                attachment = attachment,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (message.content.isNotBlank()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        scheme.primaryContainer,
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
                    )
                    .clickable { actionsVisible = !actionsVisible }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onPrimaryContainer,
                    )
                }
            }
        }

        AnimatedVisibility(visible = actionsVisible) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = formatTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                SmallIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "复制",
                    onClick = {
                        onCopy(message.content)
                        actionsVisible = false
                    },
                )
                SmallIconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = "编辑并重新发送",
                    onClick = {
                        onEdit()
                        actionsVisible = false
                    },
                )
                SmallIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = scheme.error,
                    onClick = {
                        onDelete()
                        actionsVisible = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    streaming: Boolean,
    showReasoning: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hasBody = message.content.isNotBlank()
    val waiting = streaming && !hasBody && message.reasoning.isBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        if (message.reasoning.isNotBlank()) {
            ReasoningBlock(
                reasoning = message.reasoning,
                streaming = streaming && !hasBody,
                defaultExpanded = showReasoning && streaming,
                elapsedMs = message.elapsedMs,
            )
            Spacer(Modifier.height(if (hasBody) 10.dp else 0.dp))
        }

        if (waiting) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                TypingDots()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在连接…",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (hasBody) {
            SelectionContainer {
                MarkdownText(
                    markdown = message.content,
                    color = scheme.onSurface,
                    onCopyCode = onCopy,
                )
            }
        }

        if (!streaming && !hasBody && message.reasoning.isNotBlank() && message.error == null) {
            Text(
                text = if (message.truncatedByLength) {
                    "输出额度在思考阶段就用完了，没有留下正文。调大设置里的 max_tokens，或点下面的「继续生成」。"
                } else {
                    "这次只返回了思考过程，没有正文。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        message.error?.let { error ->
            Row(
                modifier = Modifier
                    .padding(top = if (hasBody) 8.dp else 0.dp)
                    .fillMaxWidth()
                    .background(scheme.errorContainer.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .border(1.dp, scheme.error.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = scheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                SmallIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "重试",
                    tint = scheme.error,
                    onClick = onRegenerate,
                )
            }
        }

        // A reply can finish having produced only a reasoning trace — a small max_tokens gets spent
        // on thinking first. Gating the footer on body text hid the length warning and the continue
        // button precisely then, leaving a bare "已思考" card with no actions.
        val finished = !streaming && !message.isBlank
        if (finished) {
            AssistantFooter(
                message = message,
                onCopy = { onCopy(message.content) },
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onContinue = onContinue,
            )
        } else if (streaming && hasBody) {
            Text(
                text = "输出中… ${formatDuration(message.elapsedMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .alpha(rememberPulseAlpha(active = true)),
            )
        }
    }
}

@Composable
private fun AssistantFooter(
    message: ChatMessage,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onContinue: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // Hitting the output cap mid-sentence is common on reasoning models; offer the obvious next move.
    if (message.truncatedByLength) {
        TextButton(
            onClick = onContinue,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("继续生成", style = MaterialTheme.typography.labelLarge)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        val meta = buildList {
            if (message.model.isNotBlank()) add(message.model)
            if (message.elapsedMs > 0) add(formatDuration(message.elapsedMs))
            if (!message.usage.isEmpty) {
                add("↑${formatTokenCount(message.usage.promptTokens)} ↓${formatTokenCount(message.usage.completionTokens)}")
            }
            if (message.finishReason == "length") add("已达长度上限")
            if (message.finishReason == "stopped") add("已停止")
        }.joinToString(" · ")

        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
        SmallIconButton(
            icon = Icons.Outlined.ContentCopy,
            contentDescription = "复制回复",
            onClick = onCopy,
        )
        SmallIconButton(
            icon = Icons.Outlined.Refresh,
            contentDescription = "重新生成",
            onClick = onRegenerate,
        )
        SmallIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = "删除这条回复",
            tint = scheme.onSurfaceVariant,
            onClick = onDelete,
        )
    }
}

/**
 * DeepSeek returns its chain of thought separately from the answer. It's valuable but long, so it
 * lives in a card that is open while the model is still thinking and folded away once text arrives.
 */
@Composable
private fun ReasoningBlock(
    reasoning: String,
    streaming: Boolean,
    defaultExpanded: Boolean,
    elapsedMs: Long,
) {
    val scheme = MaterialTheme.colorScheme
    var userToggled by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: (defaultExpanded && streaming)
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "reasoningArrow")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { userToggled = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier
                    .size(17.dp)
                    .alpha(rememberPulseAlpha(active = streaming)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (streaming) "正在思考…" else "已思考",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurfaceVariant,
            )
            if (elapsedMs > 0) {
                Text(
                    text = " · ${formatDuration(elapsedMs)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${reasoning.length} 字",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
                    .rotate(arrow),
            )
        }

        if (expanded) {
            SelectionContainer {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}
