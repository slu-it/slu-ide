package dev.sluide.httpclient.curl

/**
 * What curl makes of the content of `-F name=content`: text, a `"quoted string"` (quotes stripped, `\"` and `\\` inside),
 * `@file` or `<file` (the name may be quoted), each with `;type=` and (files) `;filename=` attributes, which may be
 * quoted as well. Leading blanks of the content go.
 */
internal object FormOption {
    fun part(
        name: String,
        raw: String,
    ): FormPart {
        val content = raw.trimStart()
        return when (content.firstOrNull()) {
            '@' -> {
                val (path, end) = word(content, 1)
                val attributes = attributes(content, end)
                FormPart(name, FormContent.File(path, attributes["filename"]), attributes["type"])
            }

            '<' -> {
                val (path, end) = word(content, 1)
                FormPart(name, FormContent.FileText(path), attributes(content, end)["type"])
            }

            '"' -> {
                val quoted = quoted(content, 0)
                if (quoted ==
                    null
                ) {
                    value(name, content)
                } else {
                    FormPart(name, FormContent.Value(quoted.first), type(content.substring(quoted.second)))
                }
            }

            else -> {
                value(name, content)
            }
        }
    }

    // An unquoted value keeps its semicolons unless they start type=.
    private fun value(
        name: String,
        content: String,
    ) = FormPart(name, FormContent.Value(content.substringBefore(";type=")), type(content))

    private fun type(content: String) = content.substringAfter(";type=", "").ifEmpty { null }

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

    /** The `;key=value` attributes of [text] from [from] on, keys in lower case. */
    private fun attributes(
        text: String,
        from: Int,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var semicolon = text.indexOf(';', from)
        while (semicolon >= 0) {
            val start = semicolon + 1
            val next = text.indexOf(';', start)
            val equals = text.indexOf('=', start)
            if (equals in start until (if (next < 0) text.length else next)) {
                val (value, end) = word(text, equals + 1)
                result[text.substring(start, equals).trim().lowercase()] = value.trim()
                semicolon = text.indexOf(';', end)
            } else {
                // An attribute without a value says nothing.
                semicolon = next
            }
        }
        return result
    }
}
