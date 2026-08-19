package com.dshmobile.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

data class InlineStyles(
    val linkColor: Color,
    val codeColor: Color,
    val codeBackground: Color,
)

fun inlineAnnotated(source: String, styles: InlineStyles): AnnotatedString =
    buildAnnotatedString { appendInline(source, styles) }

private fun AnnotatedString.Builder.appendInline(text: String, styles: InlineStyles) {
    var i = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val c = text[i]

        // Backslash escapes: \* \_ \` \# ...
        if (c == '\\' && i + 1 < text.length && !text[i + 1].isLetterOrDigit()) {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // `code span`
        if (c == '`') {
            val run = runLength(text, i, '`')
            val fence = "`".repeat(run)
            val close = text.indexOf(fence, i + run)
            if (close >= 0) {
                flush()
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = styles.codeBackground,
                        color = styles.codeColor,
                    ),
                ) {
                    append(text.substring(i + run, close).trim())
                }
                i = close + run
                continue
            }
        }

        // ![alt](url) — shown as a labelled link; remote images are never fetched.
        if (c == '!' && i + 1 < text.length && text[i + 1] == '[') {
            val parsed = parseLink(text, i + 1)
            if (parsed != null) {
                flush()
                appendLink(parsed.label.ifBlank { "图片" }, parsed.url, styles, prefix = "🖼 ")
                i = parsed.end
                continue
            }
        }

        // [label](url)
        if (c == '[') {
            val parsed = parseLink(text, i)
            if (parsed != null) {
                flush()
                appendLink(parsed.label, parsed.url, styles)
                i = parsed.end
                continue
            }
        }

        // Bare URL autolink
        if ((c == 'h' || c == 'H') && (text.startsWith("http://", i) || text.startsWith("https://", i))) {
            var end = i
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "\"'<>」）)") end++
            // Don't swallow sentence-ending punctuation.
            while (end > i && text[end - 1] in ".,;:!?、。！？") end--
            val url = text.substring(i, end)
            flush()
            appendLink(url, url, styles)
            i = end
            continue
        }

        // ~~strikethrough~~
        if (text.startsWith("~~", i)) {
            val close = findClose(text, i + 2, "~~")
            if (close > i + 2) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(text.substring(i + 2, close), styles)
                }
                i = close + 2
                continue
            }
        }

        // ***both*** / **bold** / __bold__
        if (text.startsWith("***", i) || text.startsWith("___", i)) {
            val token = text.substring(i, i + 3)
            val close = findClose(text, i + 3, token)
            if (close > i + 3) {
                flush()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    appendInline(text.substring(i + 3, close), styles)
                }
                i = close + 3
                continue
            }
        }
        if (text.startsWith("**", i) || (text.startsWith("__", i) && opensUnderscore(text, i))) {
            val token = text.substring(i, i + 2)
            val close = findClose(text, i + 2, token)
            if (close > i + 2 && (token == "**" || closesUnderscore(text, close, 2))) {
                flush()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(text.substring(i + 2, close), styles)
                }
                i = close + 2
                continue
            }
        }

        // *italic* / _italic_ — underscores only at word boundaries so snake_case survives.
        if (c == '*' || (c == '_' && opensUnderscore(text, i))) {
            val token = c.toString()
            val close = findClose(text, i + 1, token)
            if (close > i + 1 && (c == '*' || closesUnderscore(text, close, 1))) {
                val inner = text.substring(i + 1, close)
                if (inner.isNotBlank()) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(inner, styles)
                    }
                    i = close + 1
                    continue
                }
            }
        }

        plain.append(c)
        i++
    }

    flush()
}

private fun AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    styles: InlineStyles,
    prefix: String = "",
) {
    val safe = url.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") }
    if (safe == null) {
        withStyle(SpanStyle(color = styles.linkColor)) { append(prefix + label) }
        return
    }
    withLink(
        LinkAnnotation.Url(
            safe,
            TextLinkStyles(
                style = SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
            ),
        ),
    ) {
        append(prefix + label.ifBlank { safe })
    }
}

private class ParsedLink(val label: String, val url: String, val end: Int)

/** Parses `[label](url "title")` starting at the `[`. Returns null when it isn't a link. */
private fun parseLink(text: String, start: Int): ParsedLink? {
    if (start >= text.length || text[start] != '[') return null
    var depth = 0
    var i = start
    var labelEnd = -1
    while (i < text.length) {
        val c = text[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '[') depth++
        if (c == ']') {
            depth--
            if (depth == 0) {
                labelEnd = i
                break
            }
        }
        i++
    }
    if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null

    var parens = 0
    var j = labelEnd + 1
    var urlEnd = -1
    while (j < text.length) {
        val c = text[j]
        if (c == '\\') {
            j += 2
            continue
        }
        if (c == '(') parens++
        if (c == ')') {
            parens--
            if (parens == 0) {
                urlEnd = j
                break
            }
        }
        j++
    }
    if (urlEnd < 0) return null

    val label = text.substring(start + 1, labelEnd)
    val target = text.substring(labelEnd + 2, urlEnd).trim()
    // Strip an optional link title.
    val url = target.substringBefore(' ').trim().trim('<', '>')
    return ParsedLink(label, url, urlEnd + 1)
}

private fun runLength(text: String, from: Int, ch: Char): Int {
    var n = 0
    while (from + n < text.length && text[from + n] == ch) n++
    return n
}

/** Finds [token] after [from], skipping escapes and code spans. */
private fun findClose(text: String, from: Int, token: String): Int {
    var i = from
    while (i <= text.length - token.length) {
        val c = text[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (c == '`' && token != "`") {
            val run = runLength(text, i, '`')
            val end = text.indexOf("`".repeat(run), i + run)
            i = if (end < 0) i + run else end + run
            continue
        }
        if (text.startsWith(token, i)) return i
        i++
    }
    return -1
}

private fun opensUnderscore(text: String, at: Int): Boolean {
    val before = text.getOrNull(at - 1) ?: return true
    return !before.isLetterOrDigit()
}

private fun closesUnderscore(text: String, closeAt: Int, tokenLength: Int): Boolean {
    val after = text.getOrNull(closeAt + tokenLength) ?: return true
    return !after.isLetterOrDigit()
}
