package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.SyntaxHighlighter
import org.jsoup.nodes.Entities

/**
 * The frame of the pages that previews load from a string (Markdown, notebooks, CSV, images): UTF-8, stylesheets
 * inline in the head, scripts inline at the end of the body. Such a page cannot reference files in the jar, so it
 * carries what it uses.
 */
internal object HtmlPage {
    /** The text of [name], a resource of the module (`dev/sluide/fileviewers`): the pages' stylesheets and scripts. */
    fun resource(name: String): String =
        checkNotNull(HtmlPage::class.java.getResource("/dev/sluide/fileviewers/$name")) { "missing $name" }.readText()

    /** [text] escaped for HTML text and double-quoted attribute values. */
    fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
        return buildString(text.length + 16) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }
    }

    /**
     * A page with [styles] in its head, `dark` (if [dark]) and [classes] on its body, the [content] and then each of
     * [scripts] in a script element of its own. A [title] becomes the page's `<title>`.
     */
    fun render(
        dark: Boolean,
        styles: List<String>,
        scripts: List<String>,
        classes: Collection<String> = emptyList(),
        title: String? = null,
        content: StringBuilder.() -> Unit,
    ): String =
        buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
            if (title != null) append("<title>").append(Entities.escape(title)).append("</title>\n")
            for (style in styles) append("<style>\n").append(style).append("\n</style>\n")
            append("</head>\n")
            val bodyClasses = (if (dark) listOf("dark") else emptyList()) + classes
            append(if (bodyClasses.isEmpty()) "<body>\n" else "<body class=\"${bodyClasses.joinToString(" ")}\">\n")
            content()
            append('\n')
            for (script in scripts) append("<script>\n").append(script).append("\n</script>\n")
            append("</body>\n</html>\n")
        }
}

/**
 * Writes [code] with the tokens of [highlighter] (none: all plain), as fenced code in Markdown and code cells in
 * notebooks show it: plain parts through [text], tokens through [token] with their CSS class from `syntax.css`.
 */
internal fun writeHighlighted(
    code: String,
    highlighter: SyntaxHighlighter?,
    text: (String) -> Unit,
    token: (text: String, styleClass: String) -> Unit,
) {
    var position = 0
    for (span in highlighter?.highlight(code).orEmpty()) {
        text(code.substring(position, span.start))
        token(code.substring(span.start, span.end), span.style.styleClass)
        position = span.end
    }
    text(code.substring(position))
}
