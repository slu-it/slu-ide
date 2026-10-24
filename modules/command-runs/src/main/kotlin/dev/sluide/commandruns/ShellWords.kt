package dev.sluide.commandruns

/** Words of a POSIX shell command line: splitting what the user typed, and quoting words to pass on. */
object ShellWords {
    /** Characters that need no quoting in a POSIX shell word. */
    private val SAFE = Regex("[A-Za-z0-9_./:-][A-Za-z0-9_./:=@%+,-]*")

    /**
     * Splits [text] into words at white space; single and double quotes group (and are removed), `\` escapes the next
     * character (in `"` only `"` and `\`). Throws [IllegalArgumentException] with a message to show if a quote is not
     * closed.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' -> {
                    if (c == '\'') quote = null else current.append(c)
                }

                quote == '"' -> {
                    when {
                        c == '"' -> quote = null
                        c == '\\' && i + 1 < text.length && text[i + 1] in "\"\\" -> current.append(text[++i])
                        else -> current.append(c)
                    }
                }

                c == '\\' && i + 1 < text.length -> {
                    current.append(text[++i])
                    inToken = true
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    inToken = true
                }

                c.isWhitespace() -> {
                    if (inToken) {
                        tokens += current.toString()
                        current.clear()
                        inToken = false
                    }
                }

                else -> {
                    current.append(c)
                    inToken = true
                }
            }
            i++
        }
        require(quote == null) { "Unbalanced $quote quote in the arguments" }
        if (inToken) tokens += current.toString()
        return tokens
    }

    /** [word] as one word of a POSIX shell: as is if it is plain, else in single quotes. */
    fun quote(word: String): String = if (SAFE.matches(word)) word else "'" + word.replace("'", "'\\''") + "'"
}
