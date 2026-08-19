package com.dshmobile.app.ui.markdown

/**
 * A deliberately small CommonMark subset — the part chat models actually emit. Inline text is kept
 * as raw source in these nodes and turned into styled spans at render time by `MdInline`.
 *
 * The parser is written to tolerate *incomplete* input, because it runs on a reply that is still
 * streaming: an unterminated fence renders as an open code block instead of leaking backticks.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val children: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<MdAlign>,
    ) : MdBlock
    data object Rule : MdBlock
}

data class MdListItem(val children: List<MdBlock>, val checked: Boolean? = null)

enum class MdAlign { START, CENTER, END }

private val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})\\s*([^`\\s]*).*$")
private val RULE = Regex("^ {0,3}(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$")
private val QUOTE = Regex("^ {0,3}>\\s?(.*)$")
private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])(\\s+)(.*)$")
private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(?:\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")

fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return parseBlocks(lines)
}

private fun parseBlocks(lines: List<String>): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        if (line.isBlank()) {
            i++
            continue
        }

        // ---- fenced code -------------------------------------------------
        val fence = FENCE.matchEntire(line)
        if (fence != null && (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~"))) {
            val indent = fence.groupValues[1].length
            val marker = fence.groupValues[2]
            val language = fence.groupValues[3]
            val body = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                val candidate = lines[i]
                val trimmed = candidate.trim()
                if (trimmed.startsWith(marker.take(3)) && trimmed.all { it == marker[0] } && trimmed.length >= marker.length) {
                    closed = true
                    i++
                    break
                }
                body += candidate.drop(minOf(indent, candidate.takeWhile { it == ' ' }.length))
                i++
            }
            blocks += MdBlock.CodeBlock(language, body.joinToString("\n"), closed)
            continue
        }

        // ---- thematic break ----------------------------------------------
        if (RULE.matches(line)) {
            blocks += MdBlock.Rule
            i++
            continue
        }

        // ---- heading -----------------------------------------------------
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }

        // ---- block quote -------------------------------------------------
        if (QUOTE.matches(line)) {
            val inner = mutableListOf<String>()
            while (i < lines.size) {
                val match = QUOTE.matchEntire(lines[i])
                if (match != null) {
                    inner += match.groupValues[1]
                    i++
                } else if (lines[i].isNotBlank() && inner.isNotEmpty()) {
                    inner += lines[i] // lazy continuation
                    i++
                } else {
                    break
                }
            }
            blocks += MdBlock.Quote(parseBlocks(inner))
            continue
        }

        // ---- table -------------------------------------------------------
        if (line.contains('|') && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1]) &&
            lines[i + 1].contains('-')
        ) {
            val header = splitRow(line)
            val alignments = splitRow(lines[i + 1]).map { spec ->
                val start = spec.startsWith(":")
                val end = spec.endsWith(":")
                when {
                    start && end -> MdAlign.CENTER
                    end -> MdAlign.END
                    else -> MdAlign.START
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(header, rows, alignments)
            continue
        }

        // ---- lists -------------------------------------------------------
        val listMatch = LIST_ITEM.matchEntire(line)
        if (listMatch != null) {
            val ordered = listMatch.groupValues[2].firstOrNull()?.isDigit() == true
            val start = if (ordered) listMatch.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
            val baseIndent = listMatch.groupValues[1].length
            val items = mutableListOf<MdListItem>()

            while (i < lines.size) {
                val itemMatch = LIST_ITEM.matchEntire(lines[i]) ?: break
                val indent = itemMatch.groupValues[1].length
                if (indent < baseIndent) break
                // A deeper marker belongs to the previous item's nested list, handled by recursion.
                if (indent > baseIndent) break
                val itemOrdered = itemMatch.groupValues[2].firstOrNull()?.isDigit() == true
                if (itemOrdered != ordered) break

                val contentIndent = indent + itemMatch.groupValues[2].length + itemMatch.groupValues[3].length
                val itemLines = mutableListOf(itemMatch.groupValues[4])
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank()) {
                        // A blank line only continues the item if indented content follows.
                        val after = lines.getOrNull(i + 1)
                        val continues = after != null && after.isNotBlank() &&
                            after.takeWhile { it == ' ' }.length >= contentIndent
                        if (!continues) break
                        itemLines += ""
                        i++
                        continue
                    }
                    val nextIndent = next.takeWhile { it == ' ' }.length
                    if (nextIndent >= contentIndent) {
                        itemLines += next.drop(contentIndent)
                        i++
                    } else if (LIST_ITEM.matchEntire(next) == null && !isBlockStart(next)) {
                        itemLines += next.trim() // lazy continuation of the item's paragraph
                        i++
                    } else {
                        break
                    }
                }

                val task = TASK.matchEntire(itemLines.firstOrNull()?.trim().orEmpty())
                if (task != null) {
                    itemLines[0] = task.groupValues[2]
                    items += MdListItem(parseBlocks(itemLines), task.groupValues[1].lowercase() == "x")
                } else {
                    items += MdListItem(parseBlocks(itemLines))
                }
            }

            if (items.isEmpty()) {
                blocks += MdBlock.Paragraph(line.trim())
                i++
            } else {
                blocks += MdBlock.ListBlock(ordered, start, items)
            }
            continue
        }

        // ---- paragraph ---------------------------------------------------
        val paragraph = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i])) {
            paragraph += lines[i].trim()
            i++
        }
        if (paragraph.isEmpty()) {
            paragraph += lines[i].trim()
            i++
        }
        blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
    }

    return blocks
}

/** True when [line] would begin a block other than a paragraph continuation. */
private fun isBlockStart(line: String): Boolean {
    val trimmed = line.trimStart()
    return HEADING.matches(line) ||
        RULE.matches(line) ||
        QUOTE.matches(line) ||
        LIST_ITEM.matches(line) ||
        trimmed.startsWith("```") ||
        trimmed.startsWith("~~~")
}

private fun splitRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.drop(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (ch in body) {
        when {
            escaped -> {
                current.append(ch)
                escaped = false
            }
            ch == '\\' -> {
                current.append(ch)
                escaped = true
            }
            ch == '|' -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    cells += current.toString().trim()
    return cells
}
