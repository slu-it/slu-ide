package dev.sluide.previews.yaml

import dev.sluide.previews.preview.HtmlPage
import dev.sluide.previews.preview.appendFoldState
import java.util.Locale

/**
 * Renders YAML as a formatted page for the preview (ADR 0079), laid out by [YamlLayout]: one line per entry or item,
 * every non-empty mapping and sequence a `<details>` that can be folded, with its path (`data-path`,
 * `/0/spec/containers/1`, for `collapsed` and the page's `sluide.folds.collapsed()`) and the source line it starts on
 * (`data-line`, 0-based like the other previews, for scroll sync). A page that is not complete (empty, with an error,
 * or cut off at [MAX_NODES]) also keeps the `collapsed` it was given in a hidden `#fold-state` element, so folds survive
 * an invalid save in between. Comments are dropped; anchors, aliases, tags, duplicate keys and the spelling of scalars
 * are kept. Parsing: [YamlParser].
 */
internal object YamlRenderer {
    /** The most nodes (scalars, aliases, collections) a page shows; the rest of a larger file is left out with a note. */
    const val MAX_NODES = 100_000

    private val stylesheet = HtmlPage.resource("preview.css")
    private val treeStylesheet = HtmlPage.resource("fold-tree.css")
    private val script = HtmlPage.resource("preview.js")
    private val treeScript = HtmlPage.resource("fold-tree.js")

    /** The formatted page of [text]; collections whose path is in [collapsed] start closed. */
    fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String> = emptySet(),
    ): String = render(text, dark, collapsed, MAX_NODES)

    internal fun render(
        text: String,
        dark: Boolean,
        collapsed: Set<String>,
        maxNodes: Int,
    ): String {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else YamlParser.parse(source, maxNodes)
        return HtmlPage.render(
            dark,
            listOf(stylesheet, treeStylesheet),
            listOf(script, treeScript),
            listOf("fold-page"),
        ) {
            val complete = parsed != null && parsed.documents.isNotEmpty() && parsed.error == null && !parsed.truncated
            if (collapsed.isNotEmpty() && !complete) appendFoldState(collapsed)
            if (parsed == null || (parsed.documents.isEmpty() && parsed.error == null)) {
                append("<p class=\"fold-note\">Empty file</p>")
            } else {
                if (parsed.error != null) {
                    append("<p class=\"fold-error\">").append(HtmlPage.escape(parsed.error)).append("</p>")
                }
                // No line breaks between the blocks: they would show as blank lines.
                append("<div class=\"fold-tree\">")
                YamlLayout(HtmlSink(this, collapsed)).write(parsed.documents)
                append("</div>")
                if (parsed.truncated) {
                    append("<p class=\"fold-note\">Showing the first ")
                        .append("%,d".format(Locale.ROOT, maxNodes))
                        .append(" nodes.</p>")
                }
            }
        }
    }

    /** Lines as `<div>`s, folds as `<details>`, open unless their path is in [collapsed]. */
    private class HtmlSink(
        private val out: StringBuilder,
        private val collapsed: Set<String>,
    ) : YamlSink {
        /** Per open block: whether it is a fold (`<details>`). */
        private val folds = ArrayList<Boolean>()

        override fun line(
            parts: List<YamlPart>,
            line: Int?,
            end: String,
        ) {
            out.append("<div")
            if (line != null) out.append(" data-line=\"").append(line).append('"')
            out.append('>')
            // An empty div would take no height.
            if (parts.isEmpty()) out.append("<br>") else write(parts)
            out.append("</div>")
        }

        override fun open(
            parts: List<YamlPart>,
            line: Int,
            path: String?,
            size: String,
            end: String,
        ) {
            folds += path != null
            if (path == null) {
                line(parts, line)
                out.append("<div class=\"children\">")
                return
            }
            out.append("<details")
            if (path !in collapsed) out.append(" open")
            out
                .append(" data-path=\"")
                .append(HtmlPage.escape(path))
                .append("\" data-line=\"")
                .append(line)
                .append("\"><summary>")
            write(parts)
            out.append("<span class=\"tail\"> … ").append(size).append("</span></summary><div class=\"children\">")
        }

        override fun close() {
            out.append("</div>")
            if (folds.removeLast()) out.append("</details>")
        }

        private fun write(parts: List<YamlPart>) {
            for (part in parts) {
                val text = HtmlPage.escape(part.text)
                if (part.style == null) {
                    out.append(text)
                } else {
                    out
                        .append("<span class=\"")
                        .append(part.style)
                        .append("\">")
                        .append(text)
                        .append("</span>")
                }
            }
        }
    }
}
