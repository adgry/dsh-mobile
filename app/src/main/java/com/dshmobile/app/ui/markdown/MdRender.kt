package com.dshmobile.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshmobile.app.util.isPreviewableHtml
import androidx.compose.ui.unit.sp
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.ui.theme.MonoFamily
import kotlinx.coroutines.delay

/** Beyond this the parser is skipped and the text renders verbatim, to keep streaming smooth. */
private const val MAX_MARKDOWN_CHARS = 60_000

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onCopyCode: (String) -> Unit = {},
    onPreviewHtml: ((String) -> Unit)? = null,
) {
    if (markdown.length > MAX_MARKDOWN_CHARS) {
        Text(text = markdown, style = style, color = color, modifier = modifier)
        return
    }
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    MdBlockList(
        blocks = blocks,
        modifier = modifier,
        style = style,
        color = color,
        onCopyCode = onCopyCode,
        onPreviewHtml = onPreviewHtml,
    )
}

@Composable
private fun MdBlockList(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    style: TextStyle,
    color: Color,
    onCopyCode: (String) -> Unit,
    onPreviewHtml: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            MdBlockView(
                block = block,
                style = style,
                color = color,
                onCopyCode = onCopyCode,
                onPreviewHtml = onPreviewHtml,
            )
        }
    }
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    style: TextStyle,
    color: Color,
    onCopyCode: (String) -> Unit,
    onPreviewHtml: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val inline = InlineStyles(
        linkColor = scheme.primary,
        codeColor = scheme.tertiary,
        codeBackground = scheme.surfaceContainerHighest.copy(alpha = 0.7f),
    )

    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> style.fontSize * 1.45f
                2 -> style.fontSize * 1.28f
                3 -> style.fontSize * 1.14f
                else -> style.fontSize * 1.04f
            }
            Text(
                text = inlineAnnotated(block.text, inline),
                style = style.copy(
                    fontSize = size,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = size * 1.35f,
                ),
                color = color,
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
            )
        }

        is MdBlock.Paragraph -> Text(
            text = inlineAnnotated(block.text, inline),
            style = style,
            color = color,
        )

        is MdBlock.CodeBlock -> CodeBlockCard(
            code = block.code,
            language = block.language,
            streaming = !block.closed,
            onCopy = onCopyCode,
            onPreview = onPreviewHtml,
        )

        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
            MdBlockList(
                blocks = block.children,
                modifier = Modifier.weight(1f),
                style = style,
                color = color.copy(alpha = 0.85f),
                onCopyCode = onCopyCode,
                onPreviewHtml = onPreviewHtml,
            )
        }

        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    when {
                        item.checked != null -> {
                            Icon(
                                imageVector = if (item.checked) {
                                    Icons.Outlined.CheckBox
                                } else {
                                    Icons.Outlined.CheckBoxOutlineBlank
                                },
                                contentDescription = null,
                                tint = if (item.checked) scheme.primary else scheme.outline,
                                modifier = Modifier
                                    .padding(top = 3.dp, end = 8.dp)
                                    .size(17.dp),
                            )
                        }
                        block.ordered -> Text(
                            text = "${block.start + index}.",
                            style = style.copy(fontWeight = FontWeight.Medium),
                            color = color.copy(alpha = 0.7f),
                            // The gutter is sized from the widest number this list will actually
                            // print. Guessing from item count breaks when a model splits one list
                            // across blocks: a "10." marker landed in a 20dp slot and wrapped to
                            // two lines. softWrap is off so a surprise can only clip, never wrap.
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier
                                .width(orderedGutter(block.start, block.items.size))
                                .padding(end = 4.dp),
                            textAlign = TextAlign.End,
                        )
                        else -> Text(
                            text = "•",
                            style = style,
                            color = scheme.primary,
                            modifier = Modifier
                                .width(18.dp)
                                .padding(end = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    MdBlockList(
                        blocks = item.children,
                        modifier = Modifier.weight(1f),
                        style = style,
                        color = color,
                        onCopyCode = onCopyCode,
                        onPreviewHtml = onPreviewHtml,
                    )
                }
            }
        }

        is MdBlock.Table -> MdTable(block = block, style = style, color = color, inline = inline)

        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = scheme.outlineVariant,
        )
    }
}

/** Width reserved for ordered-list numbers, from the digit count of the last item. */
private fun orderedGutter(start: Int, count: Int): Dp {
    val digits = (start + (count - 1).coerceAtLeast(0)).toString().length.coerceAtLeast(1)
    return (16 + digits * 10).dp
}

@Composable
private fun MdTable(
    block: MdBlock.Table,
    style: TextStyle,
    color: Color,
    inline: InlineStyles,
) {
    val scheme = MaterialTheme.colorScheme
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    // Width per column from its widest cell, so narrow tables stay compact and wide ones scroll.
    val widths = remember(block) {
        (0 until columnCount).map { col ->
            val longest = (listOf(block.header) + block.rows)
                .maxOfOrNull { row -> row.getOrNull(col)?.length ?: 0 } ?: 0
            (longest * 8 + 24).coerceIn(84, 240).dp
        }
    }

    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerLow, RoundedCornerShape(10.dp)),
    ) {
        Row(modifier = Modifier.background(scheme.surfaceContainerHigh)) {
            (0 until columnCount).forEach { col ->
                TableCell(
                    text = block.header.getOrNull(col).orEmpty(),
                    width = widths[col],
                    align = block.alignments.getOrNull(col) ?: MdAlign.START,
                    style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 0.92f),
                    color = color,
                    inline = inline,
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = if (rowIndex % 2 == 1) {
                    Modifier.background(scheme.surfaceContainerHighest.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            ) {
                (0 until columnCount).forEach { col ->
                    TableCell(
                        text = row.getOrNull(col).orEmpty(),
                        width = widths[col],
                        align = block.alignments.getOrNull(col) ?: MdAlign.START,
                        style = style.copy(fontSize = style.fontSize * 0.92f),
                        color = color,
                        inline = inline,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    align: MdAlign,
    style: TextStyle,
    color: Color,
    inline: InlineStyles,
) {
    Text(
        text = inlineAnnotated(text, inline),
        style = style,
        color = color,
        textAlign = when (align) {
            MdAlign.START -> TextAlign.Start
            MdAlign.CENTER -> TextAlign.Center
            MdAlign.END -> TextAlign.End
        },
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun CodeBlockCard(
    code: String,
    language: String,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPreview: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        CodeColors(
            plain = scheme.onSurface,
            keyword = scheme.primary,
            string = scheme.tertiary,
            comment = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            number = scheme.secondary,
            annotation = scheme.primary.copy(alpha = 0.85f),
        )
    }
    val highlighted = remember(code, language, colors) { highlightCode(code, language, colors) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                Text(
                    text = "输出中",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            // Only offered once the fence has closed: half a page renders as a broken page.
            if (onPreview != null && !streaming && isPreviewableHtml(language, code)) {
                SmallIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    contentDescription = "预览页面",
                    tint = scheme.primary,
                    onClick = { onPreview(code) },
                )
            }
            SmallIconButton(
                icon = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copied) "已复制" else "复制代码",
                tint = if (copied) scheme.primary else scheme.onSurfaceVariant,
                onClick = {
                    onCopy(code)
                    copied = true
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = highlighted,
                style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.plain,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
