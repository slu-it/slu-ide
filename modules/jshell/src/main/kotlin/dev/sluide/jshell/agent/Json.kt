package dev.sluide.jshell.agent

/**
 * The agent's JSON (ADR 0191): objects, arrays, strings, numbers, booleans and null, one object per protocol line. The
 * agent runs on JDK 9 with nothing but kotlin-stdlib, so it has no JSON library. Parsing throws
 * [IllegalArgumentException] on anything malformed and on nesting deeper than [MAX_DEPTH].
 */
internal object Json {
    const val MAX_DEPTH = 16

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(
        value: Any?,
        out: StringBuilder,
    ) {
        when (value) {
            null -> {
                out.append("null")
            }

            is String -> {
                quote(value, out)
            }

            is Boolean, is Int, is Long -> {
                out.append(value.toString())
            }

            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    quote(entry.key.toString(), out)
                    out.append(':')
                    write(entry.value, out)
                }
                out.append('}')
            }

            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            else -> {
                quote(value.toString(), out)
            }
        }
    }

    private fun quote(
        text: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for ((index, c) in text.withIndex()) {
            when {
                c == '"' -> out.append("\\\"")

                c == '\\' -> out.append("\\\\")

                c == '\n' -> out.append("\\n")

                c == '\r' -> out.append("\\r")

                c == '\t' -> out.append("\\t")

                // UTF-8 cannot carry a lone surrogate (it would become '?'); the escape keeps it.
                c < ' ' || (c.isSurrogate() && !paired(text, index)) -> out.append(String.format("\\u%04x", c.code))

                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private fun paired(
        text: String,
        index: Int,
    ): Boolean =
        if (text[index].isHighSurrogate()) {
            index + 1 < text.length && text[index + 1].isLowSurrogate()
        } else {
            index > 0 && text[index - 1].isHighSurrogate()
        }

    fun parse(text: String): Map<String, Any?> {
        val parser = Parser(text)
        parser.skipBlanks()
        require(parser.peek() == '{') { "an object expected" }
        @Suppress("UNCHECKED_CAST")
        val value = parser.value(0) as Map<String, Any?>
        parser.skipBlanks()
        require(parser.atEnd()) { "text after the object" }
        return value
    }

    private class Parser(
        val text: String,
    ) {
        var index = 0

        fun atEnd() = index >= text.length

        fun skipBlanks() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun peek(): Char {
            require(index < text.length) { "unexpected end" }
            return text[index]
        }

        fun next(): Char = peek().also { index++ }

        fun expect(c: Char) {
            require(next() == c) { "'$c' expected" }
        }

        fun value(depth: Int): Any? {
            require(depth < MAX_DEPTH) { "nested deeper than $MAX_DEPTH" }
            skipBlanks()
            val c = peek()
            return when {
                c == '{' -> obj(depth)
                c == '[' -> array(depth)
                c == '"' -> string()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> number()
                else -> throw IllegalArgumentException("unexpected '$c'")
            }
        }

        fun literal(
            word: String,
            value: Boolean?,
        ): Boolean? {
            require(text.startsWith(word, index)) { "bad literal" }
            index += word.length
            return value
        }

        fun number(): Any {
            val start = index
            while (index < text.length && (text[index] in '0'..'9' || text[index] in "-+.eE")) index++
            val number = text.substring(start, index)
            // NumberFormatException is an IllegalArgumentException
            return number.toLongOrNull() ?: number.toDouble()
        }

        fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            expect('{')
            skipBlanks()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipBlanks()
                val key = string()
                skipBlanks()
                expect(':')
                map[key] = value(depth + 1)
                skipBlanks()
                val separator = next()
                if (separator == '}') return map
                require(separator == ',') { "',' or '}' expected" }
            }
        }

        fun array(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            expect('[')
            skipBlanks()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                list += value(depth + 1)
                skipBlanks()
                val separator = next()
                if (separator == ']') return list
                require(separator == ',') { "',' or ']' expected" }
            }
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escaped())
                    c < ' ' -> throw IllegalArgumentException("control character in a string")
                    else -> out.append(c)
                }
            }
        }

        private fun escaped(): Char =
            when (val e = next()) {
                'n' -> {
                    '\n'
                }

                'r' -> {
                    '\r'
                }

                't' -> {
                    '\t'
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000c'
                }

                '"', '\\', '/' -> {
                    e
                }

                'u' -> {
                    require(index + 4 <= text.length) { "short \\u escape" }
                    val code = text.substring(index, index + 4).toInt(16)
                    index += 4
                    code.toChar()
                }

                else -> {
                    throw IllegalArgumentException("bad escape '\\$e'")
                }
            }
    }
}
