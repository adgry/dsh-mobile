package com.dshmobile.app.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class CodeColors(
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
)

/**
 * A single-pass lexer good enough to make a chat reply's code readable. It is intentionally
 * language-*family* aware rather than per-language: comment and string syntax is what actually
 * differs, and one broad keyword union highlights well without a grammar per language.
 */
fun highlightCode(code: String, language: String, colors: CodeColors): AnnotatedString {
    if (code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)

    val lang = language.trim().lowercase()
    val lineComment = when (lang) {
        in HASH_LANGS -> "#"
        "sql", "lua", "haskell", "elm" -> "--"
        "" -> "//"
        else -> "//"
    }
    val blockComments = lang !in HASH_LANGS && lang !in setOf("sql", "lua", "haskell", "elm")
    val keywords = KEYWORDS + (EXTRA_KEYWORDS[lang] ?: emptySet())

    return buildAnnotatedString {
        var i = 0
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                append(plain.toString())
                plain.clear()
            }
        }

        while (i < code.length) {
            val c = code[i]

            // line comment
            if (code.startsWith(lineComment, i)) {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                flush()
                withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                    append(code.substring(i, end))
                }
                i = end
                continue
            }

            // block comment
            if (blockComments && code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                flush()
                withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                    append(code.substring(i, end))
                }
                i = end
                continue
            }

            // triple-quoted string
            if (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) {
                val fence = code.substring(i, i + 3)
                val end = code.indexOf(fence, i + 3).let { if (it < 0) code.length else it + 3 }
                flush()
                withStyle(SpanStyle(color = colors.string)) { append(code.substring(i, end)) }
                i = end
                continue
            }

            // single-line string
            if (c == '"' || c == '\'' || c == '`') {
                var j = i + 1
                while (j < code.length) {
                    if (code[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (code[j] == c) {
                        j++
                        break
                    }
                    if (code[j] == '\n' && c != '`') break
                    j++
                }
                flush()
                withStyle(SpanStyle(color = colors.string)) { append(code.substring(i, minOf(j, code.length))) }
                i = j
                continue
            }

            // annotation / decorator
            if ((c == '@' || (c == '#' && lang == "csharp")) && code.getOrNull(i + 1)?.isLetter() == true) {
                var j = i + 1
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
                flush()
                withStyle(SpanStyle(color = colors.annotation)) { append(code.substring(i, j)) }
                i = j
                continue
            }

            // number
            if (c.isDigit() && (i == 0 || !isIdentChar(code[i - 1]))) {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                flush()
                withStyle(SpanStyle(color = colors.number)) { append(code.substring(i, j)) }
                i = j
                continue
            }

            // identifier / keyword
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < code.length && isIdentChar(code[j])) j++
                val word = code.substring(i, j)
                if (word in keywords) {
                    flush()
                    withStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)) { append(word) }
                } else {
                    plain.append(word)
                }
                i = j
                continue
            }

            plain.append(c)
            i++
        }

        flush()
    }
}

private const val MAX_HIGHLIGHT_CHARS = 24_000

private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

private val HASH_LANGS = setOf(
    "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "yaml", "yml", "toml",
    "ini", "conf", "dockerfile", "makefile", "make", "r", "perl", "julia", "nix", "tf", "hcl",
)

private val KEYWORDS = setOf(
    // control flow shared by nearly every language a model will emit
    "if", "else", "elif", "for", "while", "do", "switch", "case", "default", "break", "continue",
    "return", "yield", "try", "catch", "except", "finally", "throw", "throws", "raise", "match",
    "when", "in", "is", "as", "not", "and", "or", "await", "async", "defer", "goto", "pass",
    // declarations
    "class", "interface", "struct", "enum", "object", "trait", "impl", "type", "typedef",
    "fun", "func", "function", "def", "fn", "sub", "lambda", "val", "var", "let", "const",
    "static", "final", "abstract", "open", "sealed", "data", "override", "operator", "suspend",
    "inline", "companion", "public", "private", "protected", "internal", "package", "module",
    "import", "from", "export", "require", "include", "using", "namespace", "extends",
    "implements", "new", "delete", "this", "self", "super", "null", "nil", "none", "nullptr",
    "true", "false", "void", "unsafe", "mut", "pub", "use", "where", "with", "global", "extern",
    // common primitive types
    "int", "long", "short", "float", "double", "bool", "boolean", "char", "byte", "string",
    "str", "list", "dict", "set", "map", "array", "vec", "any", "unit", "auto", "var",
)

private val EXTRA_KEYWORDS = mapOf(
    "sql" to setOf(
        "select", "insert", "update", "delete", "create", "drop", "alter", "table", "index",
        "join", "left", "right", "inner", "outer", "on", "group", "order", "by", "having",
        "limit", "offset", "values", "into", "distinct", "union", "all", "primary", "key",
        "foreign", "references", "constraint", "view", "begin", "commit", "rollback",
    ),
    "html" to setOf("div", "span", "script", "style", "html", "head", "body", "link", "meta"),
    "css" to setOf("important", "media", "keyframes", "root", "supports", "import"),
    "go" to setOf("chan", "range", "select", "defer", "go", "map", "make", "nil"),
    "rust" to setOf("crate", "dyn", "move", "ref", "trait", "unsafe", "loop", "matches"),
    "kotlin" to setOf("by", "init", "constructor", "reified", "crossinline", "noinline", "vararg"),
)
