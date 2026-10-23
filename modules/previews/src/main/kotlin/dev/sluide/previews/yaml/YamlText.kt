package dev.sluide.previews.yaml

import dev.sluide.previews.preview.Formatted

/**
 * Formats YAML as plain text, the way [YamlRenderer] shows it ([YamlLayout]): block style with a 2-space indent, flow
 * collections expanded, scalars as written, comments dropped, a `---` line between documents, a trailing newline.
 * Unlike the page it has no node cap: the cap only protects the DOM.
 */
internal object YamlText {
    fun format(text: String): Formatted {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else YamlParser.parse(source, Int.MAX_VALUE)
        return when {
            parsed?.error != null -> {
                Formatted.Failed(parsed.error)
            }

            parsed == null || parsed.documents.isEmpty() -> {
                Formatted.Failed("The file is empty")
            }

            else -> {
                val out = StringBuilder(source.length + 64)
                YamlLayout(TextSink(out)).write(parsed.documents)
                Formatted.Text(out.toString())
            }
        }
    }

    /** Each line on its own, indented 2 spaces per level; empty lines without indentation. */
    private class TextSink(
        private val out: StringBuilder,
    ) : YamlSink {
        private var depth = 0

        override fun line(
            parts: List<YamlPart>,
            line: Int?,
            end: String,
        ) {
            if (parts.isNotEmpty()) {
                repeat(depth) { out.append("  ") }
                parts.forEach { out.append(it.text) }
            }
            out.append(end)
        }

        override fun open(
            parts: List<YamlPart>,
            line: Int,
            path: String?,
            size: String,
            end: String,
        ) {
            line(parts, line, end)
            depth++
        }

        override fun close() {
            depth--
        }
    }
}
