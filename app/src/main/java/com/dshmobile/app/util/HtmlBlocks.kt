package com.dshmobile.app.util

/**
 * Whether a code block is a whole page worth rendering, rather than a fragment worth reading.
 *
 * The point is a one-tap preview of something the model just wrote, so this leans on the fence's
 * own language first, and only falls back to sniffing when the fence is unlabelled — a model asked
 * for "an HTML file" quite often omits the label. A lone `<div>` is deliberately not enough: a
 * preview of a fragment is a blank screen, which reads as a bug.
 */
fun isPreviewableHtml(language: String, code: String): Boolean {
    val head = code.trimStart().take(2_000).lowercase()
    if (head.isEmpty()) return false

    return when (language.trim().lowercase()) {
        "html", "htm", "xhtml", "html5" -> true
        "svg" -> head.startsWith("<svg")
        "", "markup", "text", "plain" -> looksLikeDocument(head)
        else -> false
    }
}

/** A document announces itself: a doctype, an `<html>` root, or a standalone `<svg>`. */
private fun looksLikeDocument(head: String): Boolean =
    head.startsWith("<!doctype html") ||
        head.startsWith("<html") ||
        head.startsWith("<svg") ||
        // Some models open straight into the head, or wrap the page in a comment first.
        (head.startsWith("<head") && "<body" in head)

/**
 * A filename for the file handed to a browser or share sheet. Taken from `<title>` so the shared
 * file is recognisable, since a page's own title is the only name it carries.
 */
fun htmlFileName(code: String): String {
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(code)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()

    val cleaned = title
        .replace(Regex("\\s+"), " ")
        .filterNot { it in "/\\:*?\"<>|" }
        .trim()
        .take(48)

    return if (cleaned.isBlank()) "预览.html" else "$cleaned.html"
}
