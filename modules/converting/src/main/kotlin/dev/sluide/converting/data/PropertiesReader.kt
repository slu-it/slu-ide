package dev.sluide.converting.data

import dev.sluide.converting.ReadResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.util.IdentityHashMap

/** One `key=value` line of a properties text; [separator] is `=`, `:`, a blank, or `null` if the line has no value. */
internal class PropertiesEntry(
    val key: String,
    val value: String,
    val separator: Char?,
    val offset: Int,
)

/** The entries of a properties text by document (separated by `#---` lines), and how many comments it has. */
internal class PropertiesSyntax(
    val documents: List<List<PropertiesEntry>>,
    val comments: Int,
)

private class PropertiesException(
    message: String,
    val offset: Int,
) : Exception(message)

private sealed interface KeyPart {
    data class Name(
        val name: String,
    ) : KeyPart

    data class Index(
        val index: Int,
    ) : KeyPart
}

/**
 * Reads `java.util.Properties` syntax with Spring Boot's conventions: `#---` separates documents, `a.b` and `a[0]`
 * nest, `[a.b]` is one key part.
 */
internal object PropertiesReader {
    private const val BLANKS = " \t\u000C"
    private val number = Regex("-?[0-9]+")

    fun read(
        text: String,
        inferTypes: Boolean,
    ): ReadResult<DataContent> =
        try {
            val syntax = scan(text)
            var duplicates = 0
            val documents =
                syntax.documents.map { entries ->
                    val builder = TreeBuilder(entries.size)
                    entries.forEach { entry ->
                        val value = if (inferTypes) inferScalar(entry.value) else StringNode.valueOf(entry.value)
                        builder.put(splitKey(entry), value, entry)
                    }
                    builder.finish()
                    duplicates += builder.duplicates
                    builder.root
                }
            val notes = ArrayList<String>()
            if (syntax.comments > 0) notes += counted(syntax.comments, "comment dropped", "comments dropped")
            if (duplicates > 0) {
                notes +=
                    counted(duplicates, "duplicate key, the last value kept", "duplicate keys, the last value kept")
            }
            ReadResult.Read(DataContent(documents), notes)
        } catch (e: PropertiesException) {
            ReadResult.Failed(e.message ?: "Cannot be read", e.offset)
        }

    /** The entries and comments of [text], without building the tree; throws on a malformed `\u` or an empty key. */
    fun scan(text: String): PropertiesSyntax {
        val lines = physicalLines(text)
        val documents = ArrayList<List<PropertiesEntry>>()
        var current = ArrayList<PropertiesEntry>()
        var comments = 0
        var index = 0
        while (index < lines.size) {
            val (start, end) = lines[index++]
            var first = start
            while (first < end && text[first] in BLANKS) first++
            if (first == end) continue
            if (text[first] == '#' || text[first] == '!') {
                val trimmed = text.substring(first, end).trimEnd(' ', '\t', '\u000C')
                if (trimmed == "#---" || trimmed == "!---") {
                    if (current.isNotEmpty()) documents += current
                    current = ArrayList()
                } else {
                    comments++
                }
                continue
            }
            val raw = StringBuilder()
            val offsets = ArrayList<Int>()
            var from = first
            var to = end
            while (true) {
                for (i in from until to) {
                    raw.append(text[i])
                    offsets += i
                }
                val backslashes = (from until to).reversed().takeWhile { text[it] == '\\' }.count()
                if (backslashes % 2 == 0 || index >= lines.size) break
                raw.setLength(raw.length - 1)
                offsets.removeLast()
                from = lines[index].first
                to = lines[index].second
                index++
                while (from < to && text[from] in BLANKS) from++
            }
            current += entry(raw.toString(), offsets, start)
        }
        if (current.isNotEmpty()) documents += current
        return PropertiesSyntax(documents, comments)
    }

    private fun physicalLines(text: String): List<Pair<Int, Int>> {
        val lines = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n' || text[i] == '\r') {
                lines += start to i
                if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) lines += start to text.length
        return lines
    }

    private fun entry(
        raw: String,
        offsets: List<Int>,
        lineStart: Int,
    ): PropertiesEntry {
        var keyEnd = 0
        while (keyEnd < raw.length && raw[keyEnd] !in "=:$BLANKS") keyEnd += if (raw[keyEnd] == '\\') 2 else 1
        keyEnd = minOf(keyEnd, raw.length)
        var valueStart = keyEnd
        while (valueStart < raw.length && raw[valueStart] in BLANKS) valueStart++
        var separator: Char? = if (valueStart > keyEnd) ' ' else null
        if (valueStart < raw.length && raw[valueStart] in "=:") {
            separator = raw[valueStart]
            valueStart++
            while (valueStart < raw.length && raw[valueStart] in BLANKS) valueStart++
        }
        val key = unescape(raw, offsets, 0, keyEnd)
        if (key.isEmpty()) throw PropertiesException("Key is empty", lineStart)
        return PropertiesEntry(key, unescape(raw, offsets, valueStart, raw.length), separator, lineStart)
    }

    private fun unescape(
        raw: String,
        offsets: List<Int>,
        from: Int,
        to: Int,
    ): String {
        val out = StringBuilder()
        var i = from
        while (i < to) {
            val char = raw[i++]
            if (char != '\\') {
                out.append(char)
            } else if (i < to) {
                val escaped = raw[i++]
                when (escaped) {
                    't' -> {
                        out.append('\t')
                    }

                    'n' -> {
                        out.append('\n')
                    }

                    'r' -> {
                        out.append('\r')
                    }

                    'f' -> {
                        out.append('\u000C')
                    }

                    'u' -> {
                        val hex = if (i + 4 <= to) raw.substring(i, i + 4) else ""
                        val code = hex.takeIf { h -> h.length == 4 && h.all { it.digitToIntOrNull(16) != null } }
                        if (code == null) throw PropertiesException("Malformed \\u escape", offsets[i - 2])
                        out.append(code.toInt(16).toChar())
                        i += 4
                    }

                    else -> {
                        out.append(escaped)
                    }
                }
            }
        }
        return out.toString()
    }

    private fun tooLarge(
        key: String,
        content: String,
        entry: PropertiesEntry,
    ) = PropertiesException("Key $key has the index $content, which is too large", entry.offset)

    private fun splitKey(entry: PropertiesEntry): List<KeyPart> {
        val key = entry.key
        val parts = ArrayList<KeyPart>()
        var i = 0
        while (i < key.length) {
            if (key[i] == '[') {
                val close = key.indexOf(']', i + 1)
                if (close < 0) throw PropertiesException("Key $key misses a ]", entry.offset)
                val content = key.substring(i + 1, close)
                val isIndex = parts.isNotEmpty() && number.matches(content)
                if (isIndex && content.startsWith("-")) {
                    throw PropertiesException("Key $key has [$content], which is not a list index", entry.offset)
                }
                val index = if (isIndex) content.toIntOrNull() ?: throw tooLarge(key, content, entry) else null
                parts += if (index != null) KeyPart.Index(index) else KeyPart.Name(content)
                i = close + 1
                if (i < key.length && key[i] != '.' && key[i] != '[') {
                    throw PropertiesException("Key $key has text after a ]", entry.offset)
                }
            } else {
                var end = i
                while (end < key.length && key[end] != '.' && key[end] != '[') end++
                if (end == i) throw PropertiesException("Key $key has an empty part", entry.offset)
                parts += KeyPart.Name(key.substring(i, end))
                i = end
            }
            if (i < key.length && key[i] == '.') {
                i++
                if (i == key.length) throw PropertiesException("Key $key has an empty part", entry.offset)
            }
        }
        return parts
    }

    private class Gap(
        val message: String,
        val offset: Int,
        val index: Int,
    )

    /**
     * Builds one document's tree; list slots skipped by a higher index are gaps until their own key fills them. As
     * each gap needs an entry of its own, a document of [entryCount] entries cannot fill more gaps than that, so
     * skipping beyond it fails at once instead of allocating the slots.
     */
    private class TreeBuilder(
        private val entryCount: Int,
    ) {
        private var padded = 0
        val root: ObjectNode = JsonNodeFactory.instance.objectNode()
        var duplicates = 0
        private val gaps = IdentityHashMap<JsonNode, Gap>()

        fun put(
            parts: List<KeyPart>,
            value: JsonNode,
            entry: PropertiesEntry,
        ) {
            var container: JsonNode = root
            parts.forEachIndexed { i, part ->
                val child = child(container, part, parts, i, entry)
                val next = parts.getOrNull(i + 1)
                if (next == null) {
                    if (child != null) {
                        if (child.isContainer) conflict(parts, i, "has both a value and children", entry)
                        duplicates++
                    }
                    set(container, part, value)
                } else {
                    val wantArray = next is KeyPart.Index
                    if (child == null) {
                        val created: JsonNode =
                            if (wantArray) {
                                JsonNodeFactory.instance.arrayNode()
                            } else {
                                JsonNodeFactory.instance
                                    .objectNode()
                            }
                        set(container, part, created)
                        container = created
                    } else if (!child.isContainer) {
                        conflict(parts, i, "has both a value and children", entry)
                    } else if (child.isArray != wantArray) {
                        conflict(parts, i, "is both a list and an object", entry)
                    } else {
                        container = child
                    }
                }
            }
        }

        fun finish() {
            val gap = gaps.values.minWithOrNull(compareBy({ it.offset }, { it.index })) ?: return
            throw PropertiesException(gap.message, gap.offset)
        }

        private fun child(
            container: JsonNode,
            part: KeyPart,
            parts: List<KeyPart>,
            at: Int,
            entry: PropertiesEntry,
        ): JsonNode? =
            when (part) {
                is KeyPart.Name -> {
                    (container as ObjectNode).get(part.name)
                }

                is KeyPart.Index -> {
                    val list = container as ArrayNode
                    val skipped = part.index - list.size()
                    if (skipped > 0 && padded + skipped > entryCount) {
                        val last = part.index - 1
                        val items = if (skipped == 1) "item $last" else "items ${list.size()} to $last"
                        conflict(parts, at, "leaves out $items", entry)
                    }
                    padded += maxOf(skipped, 0)
                    for (missing in list.size() until part.index) {
                        val gap: JsonNode = JsonNodeFactory.instance.objectNode()
                        val path = render(parts.take(at))
                        gaps[gap] = Gap("List $path has no item $missing", entry.offset, missing)
                        list.add(gap)
                    }
                    list.get(part.index)?.takeUnless { gaps.containsKey(it) }
                }
            }

        private fun set(
            container: JsonNode,
            part: KeyPart,
            value: JsonNode,
        ) {
            when (part) {
                is KeyPart.Name -> {
                    (container as ObjectNode).set(part.name, value)
                }

                is KeyPart.Index -> {
                    val list = container as ArrayNode
                    if (part.index < list.size()) {
                        gaps.remove(list.get(part.index))
                        list.set(part.index, value)
                    } else {
                        list.add(value)
                    }
                }
            }
        }

        private fun conflict(
            parts: List<KeyPart>,
            at: Int,
            what: String,
            entry: PropertiesEntry,
        ): Nothing = throw PropertiesException("Key ${render(parts.take(at + 1))} $what", entry.offset)

        private fun render(parts: List<KeyPart>): String =
            parts
                .joinToString("") {
                    when (it) {
                        is KeyPart.Name -> "." + it.name
                        is KeyPart.Index -> "[${it.index}]"
                    }
                }.removePrefix(".")
    }
}
