package dev.sluide.previews.json

import dev.sluide.previews.preview.HtmlPage
import dev.sluide.previews.preview.appendFoldState
import java.util.Locale

/**
 * Renders JSON as a formatted page for the preview (ADR 0017): one line per member or element, every non-empty object
 * and array a `<details>` that can be folded, with its JSON Pointer (`data-path`, for `collapsed` and the page's
 * `sluide.folds.collapsed()`) and the source line it starts on (`data-line`, 0-based like the other previews, for scroll
 * sync). A page that is not complete (empty, with an error, or cut off at [MAX_VALUES]) also keeps the `collapsed` it
 * was given in a hidden `#fold-state` element, which `collapsed()` reports for containers the page lacks, so folds
 * survive an invalid save in between; a complete page reports only what it shows closed. At most [MAX_VALUES] values
 * are shown. Comments and trailing commas are accepted and dropped; key order, duplicate keys and the spelling of
 * numbers are kept (duplicate keys share a JSON Pointer, so they fold together).
 */
internal object JsonRenderer {
    /** The most values (scalars and containers) a page shows; the rest of a larger file is left out with a note. */
    const val MAX_VALUES = 100_000

    private val stylesheet = HtmlPage.resource("preview.css")
    private val treeStylesheet = HtmlPage.resource("fold-tree.css")
    private val script = HtmlPage.resource("preview.js")
    private val treeScript = HtmlPage.resource("fold-tree.js")

    /** The formatted page of [text]; containers whose JSON Pointer is in [collapsed] start closed. */
    fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String> = emptySet(),
    ): String = render(text, dark, collapsed, MAX_VALUES)

    internal fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String>,
        maxValues: Int,
    ): String {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else JsonTree.parse(source, maxValues)
        return HtmlPage.render(
            dark,
            listOf(stylesheet, treeStylesheet),
            listOf(script, treeScript),
            listOf("fold-page"),
        ) {
            // The folds of the page before, also those of containers this text lacks, while it is incomplete.
            val complete = parsed?.root != null && parsed.error == null && !parsed.truncated
            if (collapsed.isNotEmpty() && !complete) {
                appendFoldState(collapsed)
            }
            if (parsed == null || (parsed.root == null && parsed.error == null)) {
                append("<p class=\"fold-note\">Empty file</p>")
            } else {
                if (parsed.error != null) {
                    append("<p class=\"fold-error\">").append(HtmlPage.escape(parsed.error)).append("</p>")
                }
                // No line breaks between the blocks: they would show as blank lines.
                append("<div class=\"fold-tree\">")
                parsed.root?.let { write(it, "", collapsed, false) }
                append("</div>")
                if (parsed.truncated) {
                    append("<p class=\"fold-note\">Showing the first ")
                        .append("%,d".format(Locale.ROOT, maxValues))
                        .append(" values.</p>")
                }
            }
        }
    }

    private fun StringBuilder.write(
        node: JsonValue,
        path: String,
        collapsed: Set<String>,
        comma: Boolean,
    ) {
        val separator = if (comma) "," else ""
        if (node.kind.container && node.children.isNotEmpty()) {
            writeContainer(node, path, collapsed, separator)
            return
        }
        append("<div data-line=\"").append(node.line).append("\">")
        writeKey(node)
        when (node.kind) {
            JsonKind.OBJECT -> {
                append("{}")
            }

            JsonKind.ARRAY -> {
                append("[]")
            }

            JsonKind.STRING -> {
                val quoted = HtmlPage.escape(JsonTree.quote(node.text))
                append("<span class=\"string\">").append(quoted).append("</span>")
            }

            JsonKind.NUMBER -> {
                append("<span class=\"number\">").append(HtmlPage.escape(node.text)).append("</span>")
            }

            JsonKind.KEYWORD -> {
                append("<span class=\"keyword\">").append(HtmlPage.escape(node.text)).append("</span>")
            }
        }
        append(separator).append("</div>")
    }

    private fun StringBuilder.writeContainer(
        node: JsonValue,
        path: String,
        collapsed: Set<String>,
        separator: String,
    ) {
        val isObject = node.kind == JsonKind.OBJECT
        val open = if (isObject) "{" else "["
        val close = if (isObject) "}" else "]"
        val size = node.children.size
        val noun = if (isObject) (if (size == 1) "key" else "keys") else (if (size == 1) "item" else "items")
        append("<details")
        if (path !in collapsed) append(" open")
        append(" data-path=\"")
            .append(HtmlPage.escape(path))
            .append("\" data-line=\"")
            .append(node.line)
            .append("\">")
        append("<summary>")
        writeKey(node)
        append(open)
            .append("<span class=\"tail\"> … ")
            .append(close)
            .append(' ')
            .append(size)
            .append(' ')
            .append(noun)
        append(separator).append("</span></summary><div class=\"children\">")
        node.children.forEachIndexed { index, child ->
            val childPath = path + "/" + if (isObject) pointer(child.key.orEmpty()) else index.toString()
            write(child, childPath, collapsed, index < size - 1)
        }
        append("</div><div class=\"close\">").append(close).append(separator).append("</div></details>")
    }

    private fun StringBuilder.writeKey(node: JsonValue) {
        if (node.key != null) {
            append("<span class=\"property\">").append(HtmlPage.escape(JsonTree.quote(node.key))).append("</span>: ")
        }
    }

    private fun pointer(key: String) = key.replace("~", "~0").replace("/", "~1")
}
