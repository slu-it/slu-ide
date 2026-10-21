package dev.sluide.previews.json

import dev.sluide.previews.preview.Formatted

/**
 * Formats JSON as plain text, the way [JsonRenderer] shows it: 2-space indent, `": "` after keys, one member or element
 * per line, `{}` and `[]` for empty containers, numbers as spelled, strings re-quoted, comments dropped, a trailing
 * newline. Unlike the page it has no value cap: the cap only protects the DOM.
 */
internal object JsonText {
    fun format(text: String): Formatted {
        // Editors may save a byte order mark.
        val source = text.removePrefix("\uFEFF")
        val parsed = if (source.isBlank()) null else JsonTree.parse(source, Int.MAX_VALUE)
        val root = parsed?.root
        return when {
            parsed?.error != null -> Formatted.Failed(parsed.error)
            root == null -> Formatted.Failed("The file is empty")
            else -> Formatted.Text(buildString(source.length + 64) { write(root, 0, false) }.plus('\n'))
        }
    }

    private fun StringBuilder.write(
        node: JsonValue,
        depth: Int,
        comma: Boolean,
    ) {
        if (depth > 0) append('\n')
        repeat(depth) { append("  ") }
        node.key?.let { append(JsonTree.quote(it)).append(": ") }
        when {
            node.kind.container && node.children.isNotEmpty() -> {
                val isObject = node.kind == JsonKind.OBJECT
                append(if (isObject) '{' else '[')
                node.children.forEachIndexed { index, child -> write(child, depth + 1, index < node.children.size - 1) }
                append('\n')
                repeat(depth) { append("  ") }
                append(if (isObject) '}' else ']')
            }

            node.kind == JsonKind.OBJECT -> {
                append("{}")
            }

            node.kind == JsonKind.ARRAY -> {
                append("[]")
            }

            node.kind == JsonKind.STRING -> {
                append(JsonTree.quote(node.text))
            }

            else -> {
                append(node.text)
            }
        }
        if (comma) append(',')
    }
}
