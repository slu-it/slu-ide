package dev.sluide.httpclient.curl

/** The shells whose `curl` commands are read and written (ADR 0116). */
internal enum class Shell(
    val label: String,
) {
    POSIX("POSIX"),
    CMD("cmd"),
    POWERSHELL("PowerShell"),
    ;

    companion object {
        fun ofLabel(label: String): Shell = entries.firstOrNull { it.label == label } ?: POSIX
    }
}

/** A word of a command after the shell's quoting and escapes, with the offset in the text where it starts. */
internal data class Word(
    val text: String,
    val offset: Int,
)

/**
 * A command of a text: its [words] (the first is the program), the [comment] on the line before it, its [offset] and
 * what follows a shell operator (`| jq .`), which is not part of it.
 */
internal data class ShellCommand(
    val words: List<Word>,
    val comment: String?,
    val offset: Int,
    val ignored: String?,
)

/** Why a text is no command; [offset] is where in it. */
internal class CurlSyntaxException(
    message: String,
    val offset: Int,
) : RuntimeException(message)

/**
 * Splits a text into the words of its commands, as a POSIX shell, Windows cmd or PowerShell would: one command per
 * line, continued on the next line by a trailing `\`, `^` or backtick; comment lines (`#`, `REM`, `::`) and a leading
 * `$ ` prompt are skipped. `$VARIABLES` and `%VARIABLES%` stay as written.
 */
internal object CurlTokenizer {
    private val CURL_START =
        Regex("""^(?:\s*(?:#|REM\b|::)[^\n]*\n)*\s*(?:\$\s+)?curl(?:\.exe)?(?:\s|$)""", RegexOption.IGNORE_CASE)

    /** Whether [text] starts (after blanks, comment lines and a `$ ` prompt) with `curl` or `curl.exe`. */
    fun looksLikeCurl(text: String): Boolean = CURL_START.containsMatchIn(text)

    /**
     * The shell [text] is written for, by its line continuations (`\`, `^`, backtick); without any, cmd for `^"`
     * outside `'…'` quotes, PowerShell for `curl.exe`, else POSIX.
     */
    fun shellOf(text: String): Shell {
        val lines = text.lines().map(String::trimEnd)
        return when {
            lines.any { it.endsWith("\\") } -> Shell.POSIX
            lines.any { it.endsWith("^") } -> Shell.CMD
            lines.any { it.endsWith("`") } -> Shell.POWERSHELL
            "^\"" in text.replace(SINGLE_QUOTED, "") -> Shell.CMD
            CURL_EXE.containsMatchIn(text) -> Shell.POWERSHELL
            else -> Shell.POSIX
        }
    }

    private val SINGLE_QUOTED = Regex("""'[^'\n]*'""")
    private val CURL_EXE = Regex("""\bcurl\.exe\b""", RegexOption.IGNORE_CASE)

    /** The commands of [text]; throws [CurlSyntaxException], e.g. for an unclosed quote. */
    fun tokenize(
        text: String,
        shell: Shell = shellOf(text),
    ): List<ShellCommand> = if (shell == Shell.CMD) CmdScanner(text).scan() else Scanner(text, shell).scan()

    /** What scanners share: the words of the command being read, the comment before it, the commands so far. */
    private abstract class Commands(
        protected val text: String,
    ) {
        val commands = mutableListOf<ShellCommand>()
        private val words = mutableListOf<Word>()
        private var word: StringBuilder? = null
        private var wordStart = 0
        private var commandStart = -1
        protected var comment: String? = null
        protected var operatorAt = -1

        val atCommandStart get() = words.isEmpty() && word == null && operatorAt < 0

        /** Starts a word at [at] unless one is open; words after a shell operator are dropped. */
        fun startWord(at: Int) {
            if (word != null) return
            word = StringBuilder()
            wordStart = at
            if (commandStart < 0) commandStart = at
        }

        fun append(
            chars: CharSequence,
            at: Int,
        ) {
            startWord(at)
            word?.append(chars)
        }

        fun endWord() {
            val done = word ?: return
            if (operatorAt < 0) words += Word(done.toString(), wordStart)
            word = null
        }

        fun operator(at: Int) {
            endWord()
            if (operatorAt < 0) {
                operatorAt = at
                if (commandStart < 0) commandStart = at
            }
        }

        fun endCommand(end: Int) {
            endWord()
            if (words.isNotEmpty()) {
                val ignored =
                    operatorAt.takeIf { it >= 0 }?.let {
                        text
                            .substring(it, end)
                            .replace(CONTINUATION, " ")
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                    }
                commands += ShellCommand(words.toList(), comment, commandStart, ignored)
                comment = null
            }
            words.clear()
            commandStart = -1
            operatorAt = -1
        }

        fun lineEnd(from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

        fun unclosed(
            quote: Char,
            at: Int,
        ): Nothing = throw CurlSyntaxException("Unclosed $quote quote", at)
    }

    /** POSIX shells and PowerShell. */
    private class Scanner(
        text: String,
        private val shell: Shell,
    ) : Commands(text) {
        private val escape = if (shell == Shell.POSIX) '\\' else '`'
        private val operators = if (shell == Shell.POSIX) "|;&<>" else "|;<>"

        fun scan(): List<ShellCommand> {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                i =
                    when {
                        c == '\n' -> endCommand(i).let { i + 1 }
                        c == ' ' || c == '\t' || c == '\r' -> endWord().let { i + 1 }
                        atCommandStart && c == '$' && next == ' ' -> i + 2
                        c == '#' && atCommandStart -> lineEnd(i).also { comment = text.substring(i + 1, it).trim() }
                        c == '#' && isWordStart(i) -> lineEnd(i)
                        c == escape -> escaped(i)
                        c == '\'' && shell == Shell.POSIX -> posixSingle(i)
                        c in POWERSHELL_SINGLE && shell == Shell.POWERSHELL -> powerShellSingle(i)
                        c == '$' && next == '\'' && shell == Shell.POSIX -> ansiC(i)
                        isDoubleQuote(c) -> double(i)
                        c in operators -> operator(i).let { i + 1 }
                        else -> append(c.toString(), i).let { i + 1 }
                    }
            }
            endCommand(text.length)
            return commands
        }

        /** Whether a word would start at [at]: a `#` there starts a comment, inside a word it is text. */
        private fun isWordStart(at: Int) = at == 0 || text[at - 1].isWhitespace()

        private fun escaped(at: Int): Int {
            val next = text.getOrNull(at + 1) ?: return append(escape.toString(), at).let { at + 1 }
            if (next == '\n') return at + 2
            if (next == '\r' && text.getOrNull(at + 2) == '\n') return at + 3
            append(next.toString(), at)
            return at + 2
        }

        private fun posixSingle(at: Int): Int {
            val close = text.indexOf('\'', at + 1)
            if (close < 0) unclosed('\'', at)
            append(text.substring(at + 1, close), at)
            return close + 1
        }

        private fun powerShellSingle(at: Int): Int {
            startWord(at)
            var j = at + 1
            while (true) {
                if (j >= text.length) unclosed('\'', at)
                // PowerShell takes the typographic single quotes for quotes too; two of them are one.
                if (text[j] in POWERSHELL_SINGLE) {
                    if (text.getOrNull(j + 1)?.let { it in POWERSHELL_SINGLE } != true) return j + 1
                    append(text[j + 1].toString(), j)
                    j += 2
                } else {
                    append(text[j].toString(), j)
                    j++
                }
            }
        }

        private fun double(at: Int): Int {
            startWord(at)
            var j = at + 1
            while (true) {
                if (j >= text.length) unclosed('"', at)
                val c = text[j]
                val next = text.getOrNull(j + 1)
                when {
                    isDoubleQuote(c) && next != null && isDoubleQuote(next) && shell == Shell.POWERSHELL -> {
                        append(next.toString(), j)
                        j += 2
                    }

                    isDoubleQuote(c) -> {
                        return j + 1
                    }

                    c == escape && next != null -> {
                        doubleQuotedEscape(next)?.let { append(it, j) }
                        j += 2
                    }

                    else -> {
                        append(c.toString(), j)
                        j++
                    }
                }
            }
        }

        /** `"`, and in PowerShell also the typographic double quotes. */
        private fun isDoubleQuote(c: Char) = c == '"' || (shell == Shell.POWERSHELL && c in POWERSHELL_DOUBLE)

        /** What [next] after the escape character in double quotes gives; `null` for a line continuation. */
        private fun doubleQuotedEscape(next: Char): String? =
            when {
                next == '\n' -> null
                shell == Shell.POSIX -> if (next in "$`\"\\") next.toString() else "\\$next"
                else -> POWERSHELL_ESCAPES[next]?.toString() ?: next.toString()
            }

        /** Bash's `$'…'`: backslash escapes like C's. */
        private fun ansiC(at: Int): Int {
            startWord(at)
            var j = at + 2
            while (true) {
                if (j >= text.length) unclosed('\'', at + 1)
                val c = text[j]
                if (c == '\'') return j + 1
                if (c != '\\' || j + 1 >= text.length) {
                    append(c.toString(), j)
                    j++
                    continue
                }
                val (value, length) = ansiCEscape(j + 1)
                append(value, j)
                j += 1 + length
            }
        }

        /** The value of the escape after the backslash at [from] - 1, and how many characters it takes. */
        private fun ansiCEscape(from: Int): Pair<String, Int> {
            val c = text[from]
            ANSI_C_ESCAPES[c]?.let { return it.toString() to 1 }
            val (digits, radix, max) =
                when (c) {
                    'x' -> Triple(HEX, 16, 2)
                    'u' -> Triple(HEX, 16, 4)
                    'U' -> Triple(HEX, 16, 8)
                    in '0'..'7' -> Triple("01234567", 8, 3)
                    else -> return "\\$c" to 1
                }
            val start = if (radix == 8) from else from + 1
            var end = start
            while (end < text.length && end - start < max && text[end] in digits) end++
            if (end == start) return "\\$c" to 1
            val code = text.substring(start, end).toLong(radix)
            if (code > Character.MAX_CODE_POINT) {
                throw CurlSyntaxException("\\$c${text.substring(start, end)} is no Unicode character", from - 1)
            }
            return String(Character.toChars(code.toInt())) to end - from
        }
    }

    /**
     * Windows cmd: cmd's `^` escapes and `"` quoting first, then the C runtime's splitting of the command line into
     * arguments (`"` quotes, `\"` and `""` inside quotes are quotes).
     */
    private class CmdScanner(
        text: String,
    ) : Commands(text) {
        /** The characters of the command after cmd's escapes, with their offsets. */
        private val chars = mutableListOf<Pair<Char, Int>>()

        /** Where a shell operator ends the command's words; known to [Commands] once they are split. */
        private var cmdOperatorAt = -1

        fun scan(): List<ShellCommand> {
            var i = 0
            var quoted = false
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                if (chars.isEmpty() && cmdOperatorAt < 0 && !c.isWhitespace()) {
                    val commentStart =
                        when {
                            text.startsWith("::", i) -> i + 2

                            text.startsWith("REM", i, ignoreCase = true) &&
                                text.getOrNull(i + 3)?.isWhitespace() != false -> i + 3

                            else -> -1
                        }
                    if (commentStart >= 0) {
                        i = lineEnd(i).also { comment = text.substring(commentStart, it).trim() }
                        continue
                    }
                    if (c == '$' && next == ' ') {
                        i += 2
                        continue
                    }
                }
                when {
                    c == '\n' -> {
                        flush(i)
                        quoted = false
                        i++
                    }

                    c == '^' && !quoted -> {
                        i = caret(i)
                    }

                    c == '"' -> {
                        quoted = !quoted
                        add('"', i)
                        i++
                    }

                    c in "|&<>" && !quoted -> {
                        if (cmdOperatorAt < 0) cmdOperatorAt = i
                        i++
                    }

                    else -> {
                        add(c, i)
                        i++
                    }
                }
            }
            flush(text.length)
            return commands
        }

        private fun add(
            c: Char,
            at: Int,
        ) {
            if (cmdOperatorAt < 0) chars += c to at
        }

        /** `^x` is `x`; `^` at the end of a line continues it, escaping the next line's first character. */
        private fun caret(at: Int): Int {
            val next = text.getOrNull(at + 1) ?: return at + 1
            val lineBreak = lineBreakAt(at + 1)
            if (lineBreak == 0) {
                add(next, at + 1)
                return at + 2
            }
            val afterAt = at + 1 + lineBreak
            // An empty line after the continuation: the line break itself is the escaped character.
            val emptyLine = lineBreakAt(afterAt)
            if (emptyLine > 0) {
                add('\n', afterAt)
                return afterAt + emptyLine
            }
            val after = text.getOrNull(afterAt) ?: return afterAt
            add(after, afterAt)
            return afterAt + 1
        }

        /** The length of the line break (`\n` or `\r\n`) at [at], 0 if there is none. */
        private fun lineBreakAt(at: Int): Int =
            when {
                text.getOrNull(at) == '\n' -> 1
                text.getOrNull(at) == '\r' && text.getOrNull(at + 1) == '\n' -> 2
                else -> 0
            }

        private fun flush(end: Int) {
            var k = 0
            var quoted = false
            var quoteAt = 0
            while (k < chars.size) {
                val (c, at) = chars[k]
                when {
                    c == '\\' -> {
                        var run = 0
                        while (k + run < chars.size && chars[k + run].first == '\\') run++
                        if (chars.getOrNull(k + run)?.first == '"') {
                            append("\\".repeat(run / 2), at)
                            if (run % 2 == 1) append("\"", at)
                            k += run + run % 2
                        } else {
                            append("\\".repeat(run), at)
                            k += run
                        }
                    }

                    c == '"' && quoted && chars.getOrNull(k + 1)?.first == '"' -> {
                        append("\"", at)
                        k += 2
                    }

                    c == '"' -> {
                        startWord(at)
                        quoted = !quoted
                        quoteAt = at
                        k++
                    }

                    !quoted && (c == ' ' || c == '\t' || c == '\r') -> {
                        endWord()
                        k++
                    }

                    else -> {
                        append(c.toString(), at)
                        k++
                    }
                }
            }
            if (quoted) unclosed('"', quoteAt)
            chars.clear()
            endWord()
            if (cmdOperatorAt >= 0) operator(cmdOperatorAt)
            cmdOperatorAt = -1
            endCommand(end)
        }
    }

    private const val HEX = "0123456789abcdefABCDEF"

    /** PowerShell's single quotes: `'` and the typographic ones, U+2018 to U+201B. */
    const val POWERSHELL_SINGLE = "'‘’‚‛"

    /** PowerShell's double quotes besides `"`: the typographic ones, U+201C to U+201E. */
    private const val POWERSHELL_DOUBLE = "“”„"
    private val CONTINUATION = Regex("""[\\`^]?\r?\n""")
    private val ANSI_C_ESCAPES =
        mapOf(
            'n' to '\n',
            't' to '\t',
            'r' to '\r',
            '\\' to '\\',
            '\'' to '\'',
            '"' to '"',
            '?' to '?',
            'a' to '\u0007',
            'b' to '\b',
            'e' to '\u001b',
            'E' to '\u001b',
            'f' to '\u000c',
            'v' to '\u000b',
        )
    private val POWERSHELL_ESCAPES =
        mapOf(
            'n' to '\n',
            't' to '\t',
            'r' to '\r',
            '0' to '\u0000',
            'a' to '\u0007',
            'b' to '\b',
            'e' to '\u001b',
            'f' to '\u000c',
            'v' to '\u000b',
        )
}
