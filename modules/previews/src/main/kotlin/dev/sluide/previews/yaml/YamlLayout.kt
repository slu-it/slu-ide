package dev.sluide.previews.yaml

import org.yaml.snakeyaml.DumperOptions.ScalarStyle

/** A piece of a formatted line and its colour class (`property`, `string`, … of `preview.css`; null: uncoloured). */
internal class YamlPart(
    val text: String,
    val style: String?,
)

/**
 * Where [YamlLayout] writes its lines: the page ([YamlRenderer]) or plain text ([YamlText]). Lines between [open] and
 * its [close] are one level (2 columns) deeper.
 */
internal interface YamlSink {
    /**
     * A line; [line] is the 0-based source line it shows, if it has one. An empty [parts] is an empty line. [end] is the
     * line break that ends it in the text: `\n`, or a line or paragraph separator (LS, PS) a scalar holds as written.
     */
    fun line(
        parts: List<YamlPart>,
        line: Int?,
        end: String = "\n",
    )

    /**
     * A line the following ones (up to [close]) belong to, a fold where [path] is set (its `data-path`); [size] tells
     * how many keys or items it holds, `3 keys`.
     */
    fun open(
        parts: List<YamlPart>,
        line: Int,
        path: String?,
        size: String,
        end: String = "\n",
    )

    fun close()
}

/**
 * Lays out YAML documents as block-style lines with a 2-space indent (ADR 0079), the same for the page and the copied
 * text: flow collections expanded (empty ones stay `{}` and `[]`), scalars as written (multi-line ones indented anew),
 * a sequence item that is a mapping starting with a plain entry written compactly (`- name: web`), keys that span lines
 * or are collections as explicit `? ` entries, a `---` line between documents. Paths are JSON Pointers with the
 * document's index first (`/0/spec/containers/1`), a collection key as `?<entry index>`, an alias key as written.
 */
internal class YamlLayout(
    private val sink: YamlSink,
) {
    fun write(documents: List<YamlDocument>) {
        documents.forEachIndexed { index, document ->
            // Directives belong to the next document only after an explicit end of the one before.
            if (index > 0 && document.directives.isNotEmpty()) sink.line(Line().word("...", KEYWORD), null)
            document.directives.forEach { sink.line(Line().word(it, ANNOTATION), null) }
            val root = document.root
            val rootProperties = root.isFilledCollection() && root?.properties?.isNotEmpty() == true
            // Without content, only the marker makes it a document.
            val empty = root is YamlScalar && root.properties.isEmpty() && root.lines.singleOrNull()?.isEmpty() == true
            if (index > 0 || document.directives.isNotEmpty() || rootProperties || empty) {
                val marker = Line().word("---", KEYWORD)
                if (rootProperties && root != null) marker.properties(root)
                sink.line(marker, document.line.takeIf { document.explicit })
            }
            if (root != null && !empty) root(root, "/$index")
        }
    }

    private fun root(
        node: YamlNode,
        path: String,
    ) {
        when {
            node is YamlMapping && node.entries.isNotEmpty() -> entries(node, path, 0)
            node is YamlSequence && node.items.isNotEmpty() -> items(node, path)
            else -> value(Line(), node, path, node.line)
        }
    }

    private fun entries(
        mapping: YamlMapping,
        path: String?,
        from: Int,
    ) {
        for (index in from until mapping.entries.size) {
            val entry = mapping.entries[index]
            val key = entry.key
            val segment =
                when (key) {
                    is YamlScalar -> pointer(key.value)
                    is YamlAlias -> pointer("*" + key.name)
                    else -> "?$index"
                }
            val entryPath = path?.let { "$it/$segment" }
            if (isSimpleKey(key)) {
                value(Line().key(key), entry.value, entryPath, key.line)
            } else {
                // A key that is no fold of its own: its folds would need paths of their own.
                item("?", key, null)
                entry.value?.let { item(":", it, entryPath) }
            }
        }
    }

    private fun items(
        sequence: YamlSequence,
        path: String?,
    ) {
        sequence.items.forEachIndexed { index, item -> item("-", item, path?.let { "$it/$index" }) }
    }

    /** A sequence item (or a part of an explicit entry) after its [indicator] (`-`, `?`, `:`). */
    private fun item(
        indicator: String,
        node: YamlNode,
        path: String?,
    ) {
        val head = Line().word(indicator)
        val first = (node as? YamlMapping)?.entries?.firstOrNull()
        if (first != null && node.properties.isEmpty() && isSimpleKey(first.key) && !first.value.isFilledCollection()) {
            inline(head.key(first.key), first.value)
            sink.open(head, node.line, path, size(node), head.end)
            (first.value as? YamlScalar)?.let(::continuation)
            entries(node as YamlMapping, path, 1)
            sink.close()
        } else {
            value(head, node, path, node.line)
        }
    }

    /** [node] after [head] (a key or an indicator), on its line or, for a filled collection, in a fold under it. */
    private fun value(
        head: Line,
        node: YamlNode?,
        path: String?,
        line: Int,
    ) {
        if (inline(head, node)) {
            sink.line(head, line, head.end)
            (node as? YamlScalar)?.let(::continuation)
            return
        }
        head.properties(checkNotNull(node))
        sink.open(head, line, path, size(node))
        when (node) {
            is YamlMapping -> entries(node, path, 0)
            is YamlSequence -> items(node, path)
            else -> error("only collections fold")
        }
        sink.close()
    }

    /** Adds [node] to [head] if it fits there: anything but a filled collection; true if it did. */
    private fun inline(
        head: Line,
        node: YamlNode?,
    ): Boolean {
        when (node) {
            null -> {}

            is YamlAlias -> {
                head.word("*" + node.name, ANNOTATION)
            }

            is YamlScalar -> {
                head.properties(node)
                val text = node.lines.first()
                if (text.isNotEmpty()) head.word(text, scalarStyle(node))
                head.end = end(node, 0)
            }

            is YamlMapping -> {
                if (node.entries.isNotEmpty()) return false
                head.properties(node).word("{}")
            }

            is YamlSequence -> {
                if (node.items.isNotEmpty()) return false
                head.properties(node).word("[]")
            }
        }
        return true
    }

    /**
     * The lines of [scalar] after its first, 2 columns in from the line it starts on, each with the source line it is
     * on (NEL, LS and PS end no line in the editor).
     */
    private fun continuation(scalar: YamlScalar) {
        val style = scalarStyle(scalar)
        var line = scalar.valueLine
        for (index in 1 until scalar.lines.size) {
            if (scalar.breaks[index - 1] in editorBreaks) line++
            val text = scalar.lines[index]
            val parts = if (text.isEmpty()) emptyList() else listOf(YamlPart("  ", null), YamlPart(text, style))
            sink.line(parts, line, end(scalar, index))
        }
    }

    /**
     * The break after line [index] of [scalar] in the formatted text: an LS or PS as written (SnakeYAML keeps them in
     * the value), else `\n` (also for NEL, which reads as one).
     */
    private fun end(
        scalar: YamlScalar,
        index: Int,
    ): String = scalar.breaks.getOrNull(index)?.takeIf { it == "\u2028" || it == "\u2029" } ?: "\n"

    /** A line under construction: words separated by a space, and the line break that ends it. */
    private class Line : ArrayList<YamlPart>() {
        var end = "\n"

        fun word(
            text: String,
            style: String? = null,
        ): Line {
            if (isNotEmpty()) add(YamlPart(" ", null))
            add(YamlPart(text, style))
            return this
        }

        fun properties(node: YamlNode): Line {
            node.properties.forEach { word(it, ANNOTATION) }
            return this
        }

        /** A simple key with its colon. */
        fun key(key: YamlNode): Line {
            when (key) {
                // `*a:` would read as the alias `a:`.
                is YamlAlias -> word("*" + key.name, ANNOTATION).word(":")

                is YamlScalar -> properties(key).word(key.lines.first(), PROPERTY).add(YamlPart(":", null))

                else -> error("collections are no simple keys")
            }
            return this
        }
    }

    internal companion object {
        private const val PROPERTY = "property"
        private const val STRING = "string"
        private const val NUMBER = "number"
        private const val KEYWORD = "keyword"
        private const val ANNOTATION = "annotation"

        /** The longest key YAML allows without `? `, in code points; chars are at least as many. */
        private const val MAX_SIMPLE_KEY = 1024

        private val editorBreaks = setOf("\n", "\r\n", "\r")

        private val keyword = Regex("true|True|TRUE|false|False|FALSE|null|Null|NULL|~")

        /** The numbers the source highlighting colours (file-viewers.md). */
        private val number =
            Regex(
                """[-+]?(?:0x[0-9a-fA-F]+|0o[0-7]+|\d[\d_]*(?:\.\d*)?(?:[eE][-+]?\d+)?""" +
                    """|\.\d+(?:[eE][-+]?\d+)?|\.(?:inf|Inf|INF|nan|NaN|NAN))""",
            )

        /** The colour of [scalar]'s text, as the source highlighting gives it; a tag decides its type instead. */
        fun scalarStyle(scalar: YamlScalar): String? =
            when {
                scalar.style != ScalarStyle.PLAIN -> STRING
                scalar.lines.size > 1 || scalar.properties.any { it.startsWith('!') } -> null
                keyword.matches(scalar.lines[0]) -> KEYWORD
                number.matches(scalar.lines[0]) -> NUMBER
                else -> null
            }

        /** A key that can stand before `: ` on one line: an alias or a one-line scalar with text. */
        fun isSimpleKey(key: YamlNode): Boolean =
            key is YamlAlias ||
                (
                    key is YamlScalar && key.lines.size == 1 && key.lines[0].isNotEmpty() &&
                        key.lines[0].length <= MAX_SIMPLE_KEY
                )

        private fun YamlNode?.isFilledCollection() =
            (this is YamlMapping && entries.isNotEmpty()) || (this is YamlSequence && items.isNotEmpty())

        private fun size(node: YamlNode): String =
            when (node) {
                is YamlMapping -> node.entries.size.let { "$it ${if (it == 1) "key" else "keys"}" }
                is YamlSequence -> node.items.size.let { "$it ${if (it == 1) "item" else "items"}" }
                else -> ""
            }

        /** [key] as a JSON Pointer segment. */
        fun pointer(key: String) = key.replace("~", "~0").replace("/", "~1")
    }
}
