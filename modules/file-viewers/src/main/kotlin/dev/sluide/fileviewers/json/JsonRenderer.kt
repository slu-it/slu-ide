package dev.sluide.fileviewers.json

import dev.sluide.fileviewers.preview.HtmlPage
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.io.JsonStringEncoder
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.json.JsonMapper
import java.util.Locale

/**
 * Renders JSON as a formatted page for the preview (ADR 0017): one line per member or element, every non-empty object
 * and array a `<details>` that can be folded, with its JSON Pointer (`data-path`, for `collapsed` and the page's
 * `sluide.json.collapsed()`) and the source line it starts on (`data-line`, 0-based like the other previews, for scroll
 * sync). A page that is not complete (empty, with an error, or cut off at [MAX_VALUES]) also keeps the `collapsed` it
 * was given in a hidden `data-collapsed` element, which `collapsed()` reports for containers the page lacks, so folds
 * survive an invalid save in between; a complete page reports only what it shows closed. At most [MAX_VALUES] values
 * are shown. Comments and trailing commas are accepted and dropped; key order, duplicate keys and the spelling of
 * numbers are kept (duplicate keys share a JSON Pointer, so they fold together).
 */
internal object JsonRenderer {
    /** The most values (scalars and containers) a page shows; the rest of a larger file is left out with a note. */
    const val MAX_VALUES = 100_000

    private val mapper =
        JsonMapper
            .builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()

    /** The parenthetical with the `[Source: …]` excerpt that Jackson appends to some messages. */
    private val sourceExcerpt = Regex("""\s*\([^()]*\[Source:.*$""", RegexOption.DOT_MATCHES_ALL)

    private val stylesheet = HtmlPage.resource("preview.css")
    private val treeStylesheet = HtmlPage.resource("json-preview.css")
    private val script = HtmlPage.resource("preview.js")
    private val treeScript = HtmlPage.resource("json-tree.js")

    private enum class Kind(
        val container: Boolean = false,
    ) {
        OBJECT(true),
        ARRAY(true),
        STRING,
        NUMBER,
        KEYWORD,
    }

    private class Node(
        val kind: Kind,
        val key: String?,
        val text: String,
        val line: Int,
    ) {
        val children = ArrayList<Node>()
    }

    private class Parsed(
        val root: Node?,
        val error: String?,
        val truncated: Boolean,
    )

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
        val parsed = if (source.isBlank()) null else parse(source, maxValues)
        return HtmlPage.render(
            dark,
            listOf(stylesheet, treeStylesheet),
            listOf(script, treeScript),
            listOf("json"),
        ) {
            // The folds of the page before, also those of containers this text lacks, while it is incomplete.
            val complete = parsed?.root != null && parsed.error == null && !parsed.truncated
            if (collapsed.isNotEmpty() && !complete) {
                append("<div id=\"json-state\" hidden data-collapsed=\"")
                    .append(escape(mapper.writeValueAsString(collapsed.sorted())))
                    .append("\"></div>")
            }
            if (parsed == null || (parsed.root == null && parsed.error == null)) {
                append("<p class=\"json-note\">Empty file</p>")
            } else {
                if (parsed.error != null) {
                    append("<p class=\"json-error\">").append(escape(parsed.error)).append("</p>")
                }
                // No line breaks between the blocks: they would show as blank lines.
                append("<div class=\"json-tree\">")
                parsed.root?.let { write(it, "", collapsed, false) }
                append("</div>")
                if (parsed.truncated) {
                    append("<p class=\"json-note\">Showing the first ")
                        .append("%,d".format(Locale.ROOT, maxValues))
                        .append(" values.</p>")
                }
            }
        }
    }

    /**
     * The values of [text] as a tree: all that precedes the first error (reported as such), or the first [maxValues].
     */
    private fun parse(
        text: String,
        maxValues: Int,
    ): Parsed {
        var root: Node? = null
        var error: String? = null
        var truncated = false
        val stack = ArrayList<Node>()
        try {
            mapper.createParser(text).use { parser ->
                var key: String? = null
                var keyLine = 0
                var count = 0
                while (true) {
                    val token = parser.nextToken() ?: break
                    if (token == JsonToken.PROPERTY_NAME) {
                        key = parser.currentName()
                        keyLine = parser.currentTokenLocation().lineNr - 1
                        continue
                    }
                    if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        stack.removeLast()
                        continue
                    }
                    if (stack.isEmpty() && root != null) {
                        error = "Unexpected content after the end of the value"
                        break
                    }
                    if (count >= maxValues) {
                        truncated = true
                        break
                    }
                    count++
                    val kind =
                        when (token) {
                            JsonToken.START_OBJECT -> Kind.OBJECT
                            JsonToken.START_ARRAY -> Kind.ARRAY
                            JsonToken.VALUE_STRING -> Kind.STRING
                            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> Kind.NUMBER
                            else -> Kind.KEYWORD
                        }
                    // A member starts at its key, an element or the root at its value.
                    val line = if (key != null) keyLine else parser.currentTokenLocation().lineNr - 1
                    val node = Node(kind, key, if (kind.container) "" else parser.text, line)
                    key = null
                    if (stack.isEmpty()) root = node else stack.last().children += node
                    if (kind.container) stack += node
                }
            }
        } catch (e: JacksonException) {
            error = describe(e, stack.lastOrNull())
        }
        return Parsed(root, error, truncated)
    }

    /**
     * "Line 3, column 7: message" for [e], without Jackson's source excerpt; [open] is the innermost unclosed
     * container.
     */
    private fun describe(
        e: JacksonException,
        open: Node?,
    ): String {
        val line = e.location?.lineNr ?: 0
        val where = if (line > 0) "Line $line, column ${e.location?.columnNr}: " else ""
        val message = e.originalMessage.orEmpty()
        if (open != null && message.startsWith("Unexpected end-of-input") &&
            ("expected close marker" in message || "within/between" in message)
        ) {
            val what = if (open.kind == Kind.OBJECT) "object" else "array"
            return where + "the $what opened on line ${open.line + 1} is not closed"
        }
        return where + message.replace(sourceExcerpt, "").substringBefore(" (start marker at").trim()
    }

    private fun StringBuilder.write(
        node: Node,
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
            Kind.OBJECT -> append("{}")
            Kind.ARRAY -> append("[]")
            Kind.STRING -> append("<span class=\"string\">").append(escape(quote(node.text))).append("</span>")
            Kind.NUMBER -> append("<span class=\"number\">").append(escape(node.text)).append("</span>")
            Kind.KEYWORD -> append("<span class=\"keyword\">").append(escape(node.text)).append("</span>")
        }
        append(separator).append("</div>")
    }

    private fun StringBuilder.writeContainer(
        node: Node,
        path: String,
        collapsed: Set<String>,
        separator: String,
    ) {
        val isObject = node.kind == Kind.OBJECT
        val open = if (isObject) "{" else "["
        val close = if (isObject) "}" else "]"
        val size = node.children.size
        val noun = if (isObject) (if (size == 1) "key" else "keys") else (if (size == 1) "item" else "items")
        append("<details")
        if (path !in collapsed) append(" open")
        append(" data-path=\"")
            .append(escape(path))
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

    private fun StringBuilder.writeKey(node: Node) {
        if (node.key != null) {
            append("<span class=\"property\">").append(escape(quote(node.key))).append("</span>: ")
        }
    }

    /** [text] as a JSON string literal. */
    private fun quote(text: String): String =
        StringBuilder(text.length + 2)
            .append('"')
            .also { JsonStringEncoder.getInstance().quoteAsString(text, it) }
            .append('"')
            .toString()

    private fun pointer(key: String) = key.replace("~", "~0").replace("/", "~1")

    private fun escape(text: String): String {
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
}
