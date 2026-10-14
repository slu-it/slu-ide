package dev.sluide.httpclient.vars

/** A text split into literal text and the `{{…}}` variables in it. */
internal data class Template(
    val parts: List<Part>,
) {
    sealed interface Part {
        /** The text as written. */
        val raw: String

        data class Literal(
            val text: String,
        ) : Part {
            override val raw get() = text
        }

        /** `{{a.b.c}}`: a variable, possibly a path into an environment object. */
        data class Ref(
            val path: List<String>,
            override val raw: String = "{{${path.joinToString(".")}}}",
        ) : Part {
            val name get() = path.joinToString(".")
        }

        /** `{{$random.integer(1, 10)}}`: a function call; quoted arguments lose their quotes. */
        data class Call(
            val name: String,
            val args: List<String>,
            override val raw: String = "{{$name(${args.joinToString(", ")})}}",
        ) : Part {
            /** The call for messages, without braces and quotes. */
            val label get() = "$name(${args.joinToString(", ")})"
        }
    }

    companion object {
        private val CALL = Regex("""([^\s(){}]+)\s*\((.*)\)""")
        private val NAME = Regex("""[^\s(){}]+""")

        fun parse(text: String): Template {
            val parts = mutableListOf<Part>()
            val literal = StringBuilder()
            var index = 0
            while (index < text.length) {
                val open = text.indexOf("{{", index)
                val close = if (open < 0) -1 else text.indexOf("}}", open + 2)
                if (close < 0) {
                    literal.append(text, index, text.length)
                    break
                }
                literal.append(text, index, open)
                val variable = variable(text.substring(open + 2, close).trim(), text.substring(open, close + 2))
                if (variable == null) {
                    literal.append(text, open, close + 2)
                } else {
                    if (literal.isNotEmpty()) parts += Part.Literal(literal.toString())
                    literal.clear()
                    parts += variable
                }
                index = close + 2
            }
            if (literal.isNotEmpty()) parts += Part.Literal(literal.toString())
            return Template(parts)
        }

        private fun variable(
            inner: String,
            raw: String,
        ): Part? {
            CALL.matchEntire(inner)?.let { return Part.Call(it.groupValues[1], arguments(it.groupValues[2]), raw) }
            if (!NAME.matches(inner)) return null
            return Part.Ref(inner.split('.'), raw)
        }

        /** Splits on commas outside quotes and removes the quotes. */
        private fun arguments(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (char in text) {
                when {
                    quote != null && char == quote -> {
                        quote = null
                    }

                    quote == null && (char == '"' || char == '\'') -> {
                        quote = char
                    }

                    quote == null && char == ',' -> {
                        args += current.toString().trim().also { current.clear() }
                    }

                    quote == null && char.isWhitespace() -> {}

                    else -> {
                        current.append(char)
                    }
                }
            }
            args += current.toString().trim()
            return args
        }
    }
}
