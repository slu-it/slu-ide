package dev.sluide.ide.clipboard

/**
 * The guards that keep prose from counting as a data format (ADR 0187). Converting scores "Hello, world" as CSV 70,
 * "Note: buy milk" as YAML 80 and a bare URL as an HTTP request, but a pasted note must become `.txt`. They decide by
 * the id of Converting's format, not by the extension it maps to. Plain line scans, each linear in the text.
 */
internal object DataTextGuards {
    private const val SENTENCE_ENDS = ".!?:"
    private val CSV_DELIMITERS = listOf(',', ';')
    private val WHITESPACE = Regex("\\s+")

    /**
     * The methods of a `.http` request line (`HttpFileParser.METHODS` in http-client, which is internal there): a copy,
     * since `ide` cannot read it.
     */
    private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "PATCH", "OPTIONS", "TRACE")

    /** The rules of formats that decide on the whole text, also when it is a single line. */
    private val textRules: Map<String, (String) -> Boolean> =
        mapOf("json" to { true }, "curl" to ::curlAccepts, "http" to ::httpAccepts)

    /** The rules of the other formats, on the non-blank lines of a text that has at least two. */
    private val lineRules: Map<String, (List<String>) -> Boolean> =
        mapOf("yaml" to ::yamlAccepts, "csv" to ::csvAccepts, "properties" to ::propertiesAccepts)

    /**
     * Whether Converting's match of the format [formatId] on [text] may count: JSON always; cURL and HTTP requests by
     * their shape (also on one line); any other format needs two non-blank lines, and YAML, CSV and Properties their own
     * rule (TSV and TOML none).
     */
    fun accepts(
        formatId: String,
        text: String,
    ): Boolean {
        textRules[formatId]?.let { return it(text) }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return false
        return lineRules[formatId]?.invoke(lines) ?: true
    }

    /**
     * After the comment lines the requests family skips (`#`, `::`, `REM`) and one `$ ` prompt, the first word is exactly
     * `curl` and a later token holds `://` or is an option (starts with `-` and is longer): "Curl up with a good book"
     * is prose, which the requests family reads as cURL (its pattern ignores case).
     */
    private fun curlAccepts(text: String): Boolean {
        val tokens =
            text
                .lineSequence()
                .dropWhile { it.isBlank() || isShellComment(it) }
                .joinToString(" ")
                .trim()
                .split(WHITESPACE)
        val start = if (tokens.first() == "$") 1 else 0
        if (tokens.getOrNull(start) != "curl") return false
        return tokens.drop(start + 1).any { "://" in it || (it.startsWith("-") && it.length > 1) }
    }

    /** A comment line of a shell script or batch file, as the requests family's `curl` pattern skips them. */
    private fun isShellComment(line: String): Boolean {
        val trimmed = line.trimStart()
        val remark =
            trimmed.startsWith("REM", ignoreCase = true) &&
                trimmed.getOrNull(3)?.let { !it.isLetterOrDigit() && it != '_' } != false
        return trimmed.startsWith("#") || trimmed.startsWith("::") || remark
    }

    /**
     * The first line that is no blank or comment (`#`, `//`) is `METHOD target` or `METHOD target HTTP/x` and nothing
     * more. The target is `http://…`, `https://…` or `{{…`, or a path (`/…`) of a raw request, which needs the HTTP
     * version or a `Host:` header line. The requests family also reads a bare URL line, "GET milk" and a request line
     * with words after it as a request.
     */
    private fun httpAccepts(text: String): Boolean {
        val lines =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        val parts = lines.firstOrNull().orEmpty().split(WHITESPACE)
        if (parts.first() !in HTTP_METHODS || parts.size !in 2..3) return false
        if (parts.size == 3 && !isHttpVersion(parts[2])) return false
        val target = parts[1]
        return target.startsWith("http://") || target.startsWith("https://") || target.startsWith("{{") ||
            (
                target.startsWith("/") &&
                    (parts.size == 3 || lines.drop(1).any { it.startsWith("host:", ignoreCase = true) })
            )
    }

    private fun isHttpVersion(token: String): Boolean =
        token.startsWith("HTTP/") && token.length > 5 && token.drop(5).all { it.isDigit() || it == '.' }

    /**
     * Every line has at least two delimiters (`,` or `;`, the same on all lines) outside quotes, so three fields, and
     * none ends in sentence punctuation: a letter, an address or a log line has one comma.
     */
    private fun csvAccepts(lines: List<String>): Boolean =
        lines.none { it.trimEnd().last() in SENTENCE_ENDS } &&
            CSV_DELIMITERS.any { delimiter -> lines.all { delimitersOutsideQuotes(it, delimiter) >= 2 } }

    private fun delimitersOutsideQuotes(
        line: String,
        delimiter: Char,
    ): Int {
        var quoted = false
        var count = 0
        for (char in line) {
            if (char == '"') {
                quoted = !quoted
            } else if (char == delimiter && !quoted) {
                count++
            }
        }
        return count
    }

    /**
     * A structural sign and no top-level key with whitespace in it. The sign: the first line indented under a bare `key:`
     * line is itself a `key:` entry (key without whitespace) or a `- ` item, or the key opens a block scalar (`|`, `>`)
     * and a line is indented under it; or a `---` line; or a flow collection starting the text; or a `- ` item that holds a
     * `key:`. A continued plain scalar ("Todo:" and an indented sentence) and a list of plain items are not
     * YAML.
     */
    private fun yamlAccepts(lines: List<String>): Boolean {
        val content = lines.filterNot { it.trimStart().startsWith("#") }
        if (content.isEmpty()) return false
        val structured =
            content.first().trimStart().first() in "{[" ||
                content.any { it.trimEnd() == "---" } ||
                content.any { isListItem(it) && keyOf(it) != null } ||
                content.zipWithNext().any { (above, below) -> indent(below) > indent(above) && opens(above, below) }
        return structured && content.none { topLevelKeyHasWhitespace(it) }
    }

    /** Whether [below], indented under [above], is what a key's value starts with in YAML. */
    private fun opens(
        above: String,
        below: String,
    ): Boolean = opensBlockScalar(above) || (isBareKey(above) && (isListItem(below) || isKeyEntry(below)))

    private fun isListItem(line: String): Boolean = line.trimStart().let { it == "-" || it.startsWith("- ") }

    private fun isBareKey(line: String): Boolean = line.trimEnd().endsWith(":")

    private fun isKeyEntry(line: String): Boolean =
        keyOf(line.trimStart())?.let { it.isNotEmpty() && it.none(Char::isWhitespace) } == true

    /** `key: |` or `key: >`, with a chomping mark or an indent number, and a key without whitespace. */
    private fun opensBlockScalar(line: String): Boolean {
        val trimmed = line.trimEnd()
        val mark = trimmed.takeLastWhile { !it.isWhitespace() }
        if (mark.firstOrNull() !in listOf('|', '>') || !mark.drop(1).all { it in "+-0123456789" }) return false
        val before = trimmed.dropLast(mark.length).trimEnd()
        val key = before.trimStart().removeSuffix(":")
        return before.endsWith(":") && key.isNotEmpty() && key.none(Char::isWhitespace)
    }

    private fun indent(line: String): Int = line.indexOfFirst { it != ' ' && it != '\t' }

    /** Whether [line] is at the top level with `key:` and the key has whitespace; list items and flow lines are not. */
    private fun topLevelKeyHasWhitespace(line: String): Boolean {
        if (line.first().isWhitespace() || line.first() in "-{[") return false
        return keyOf(line)?.any { it.isWhitespace() } == true
    }

    /** The text before the first `:` that ends a key (followed by whitespace or the line's end), else null. */
    private fun keyOf(line: String): String? {
        var from = line.indexOf(':')
        while (from >= 0) {
            if (from + 1 == line.length || line[from + 1].isWhitespace()) return line.substring(0, from)
            from = line.indexOf(':', from + 1)
        }
        return null
    }

    /** Every entry is `key=value` or `key: value` with one token as key; comments, blank and continued lines pass. */
    private fun propertiesAccepts(lines: List<String>): Boolean {
        var continued = false
        for (line in lines) {
            val wasContinued = continued
            continued = line.trimEnd().endsWith("\\")
            val entry = line.trimStart()
            if (wasContinued || entry.startsWith("#") || entry.startsWith("!")) continue
            val parts = entry.split('=', ':', limit = 2)
            if (parts.size < 2) return false
            val key = parts[0].trimEnd()
            if (key.isEmpty() || key.any { it.isWhitespace() }) return false
        }
        return true
    }
}
