package dev.sluide.httpclient.curl

/**
 * What curl makes of the content of `-F name=content`: text up to the first `;`, a `"quoted string"` (quotes stripped,
 * `\"` and `\\` inside), `@file` or `<file` (the name may be quoted), each followed by `;key=value` attributes, whose
 * values may be quoted as well. Leading blanks of the content go. The model has `type=` and (for `@file`) `filename=`;
 * every other attribute is left out and named in [Field.ignored].
 */
internal object FormOption {
    /** The [part] and the attributes ([ignored], as written) that it could not take. */
    data class Field(
        val part: FormPart,
        val ignored: List<String>,
    )

    private class Attribute(
        val key: String,
        var value: String?,
        val raw: String,
    ) {
        val written get() = ";" + if (value == null) raw else "$key=$value"
    }

    fun part(
        name: String,
        raw: String,
    ): Field {
        val content = raw.trimStart()
        return when (content.firstOrNull()) {
            '@' -> {
                val (path, end) = word(content, 1)
                val attributes = attributes(content, end)
                field(attributes, setOf(FILENAME, TYPE)) {
                    FormPart(name, FormContent.File(path, it[FILENAME]), it[TYPE])
                }
            }

            '<' -> {
                val (path, end) = word(content, 1)
                field(attributes(content, end), setOf(TYPE)) {
                    FormPart(name, FormContent.FileText(path), it[TYPE])
                }
            }

            '"' -> {
                val quoted = quoted(content, 0)
                if (quoted ==
                    null
                ) {
                    value(name, content)
                } else {
                    value(name, quoted.first, attributes(content, quoted.second))
                }
            }

            else -> {
                value(name, content)
            }
        }
    }

    private fun value(
        name: String,
        content: String,
    ): Field {
        val end = content.indexOf(';').let { if (it < 0) content.length else it }
        return value(name, content.substring(0, end), attributes(content, end))
    }

    private fun value(
        name: String,
        text: String,
        attributes: List<Attribute>,
    ) = field(attributes, setOf(TYPE)) { FormPart(name, FormContent.Value(text), it[TYPE]) }

    private fun field(
        attributes: List<Attribute>,
        known: Set<String>,
        part: (Map<String, String>) -> FormPart,
    ): Field {
        val (taken, left) = attributes.partition { it.key in known && it.value != null }
        return Field(part(taken.associate { it.key to it.value.orEmpty() }), left.map { it.written })
    }

    /** The quoted string at [from] and the index after its closing quote; null if it is never closed. */
    private fun quoted(
        text: String,
        from: Int,
    ): Pair<String, Int>? {
        val result = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '"' -> return result.toString() to i + 1
                c == '\\' && (next == '"' || next == '\\') -> result.append(next).also { i++ }
                else -> result.append(c)
            }
            i++
        }
        return null
    }

    /** The word at [from], a quoted string or the text up to the next `;`, and the index after it. */
    private fun word(
        text: String,
        from: Int,
    ): Pair<String, Int> {
        if (text.getOrNull(from) == '"') quoted(text, from)?.let { return it }
        val end = text.indexOf(';', from).let { if (it < 0) text.length else it }
        return text.substring(from, end) to end
    }

    /**
     * The `;key=value` attributes of [text] from [from] on, keys in lower case. curl sends a `type` whole, with the
     * `;` and what follows up to the next attribute it knows (`text/plain;charset=utf-8`).
     */
    private fun attributes(
        text: String,
        from: Int,
    ): List<Attribute> {
        val result = mutableListOf<Attribute>()
        for (attribute in segments(text, from)) {
            val type = result.lastOrNull()?.takeIf { it.key == TYPE }
            val typeValue = type?.value
            if (type != null && typeValue != null && attribute.key !in FOLLOWING) {
                type.value = typeValue + ";" + attribute.raw
            } else {
                result += attribute
            }
        }
        return result
    }

    /** The `;`-separated attributes of [text] from [from] on, a value may be quoted and hold a `;`. */
    private fun segments(
        text: String,
        from: Int,
    ): List<Attribute> {
        val result = mutableListOf<Attribute>()
        var semicolon = text.indexOf(';', from)
        while (semicolon >= 0) {
            val start = semicolon + 1
            val next = text.indexOf(';', start)
            val stop = if (next < 0) text.length else next
            val equals = text.indexOf('=', start)
            if (equals in start until stop) {
                val (value, end) = word(text, equals + 1)
                result +=
                    Attribute(
                        text.substring(start, equals).trim().lowercase(),
                        value.trim(),
                        text.substring(start, end),
                    )
                semicolon = text.indexOf(';', end)
            } else {
                val raw = text.substring(start, stop)
                result += Attribute(raw.trim(), null, raw)
                semicolon = next
            }
        }
        return result
    }

    private const val TYPE = "type"
    private const val FILENAME = "filename"
    private val FOLLOWING = setOf(TYPE, FILENAME, "headers", "encoder")
}
