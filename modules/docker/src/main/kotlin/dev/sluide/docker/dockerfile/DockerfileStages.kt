package dev.sluide.docker.dockerfile

/** What the Build pane needs of a Dockerfile: its named [stages] and its [args], in the order of the file. */
internal data class DockerfileInfo(
    val stages: List<String>,
    val args: List<BuildArg>,
)

/** An `ARG` of a Dockerfile: its [name] and the [default] after `=`, if it has one. */
internal data class BuildArg(
    val name: String,
    val default: String?,
)

/**
 * The stage names (`FROM [--platform=…] image AS name`) and the `ARG`s (`ARG a=1 b`, several per line, quoted defaults)
 * of the Dockerfile [text], in file order. ARGs before the first `FROM` and inside stages alike, each name once at its
 * first place. Instructions are case-insensitive; comments, line continuations (the escape character, `\` or the
 * backtick of a `# escape=` directive) and heredoc bodies (`RUN <<EOF … EOF`, which are no instructions) are taken
 * into account. Best effort, never throws.
 */
internal fun parseDockerfile(text: String): DockerfileInfo {
    val lines = text.lines()
    val escape = escapeCharacter(lines)
    val stages = mutableListOf<String>()
    val args = LinkedHashMap<String, BuildArg>()
    var index = 0
    while (index < lines.size) {
        val first = lines[index]
        index++
        if (isCommentOrBlank(first)) continue
        val instruction = StringBuilder()
        var line = first
        while (true) {
            val trimmed = line.trimEnd()
            if (!trimmed.endsWith(escape) || index >= lines.size) {
                instruction.append(trimmed)
                break
            }
            instruction.append(trimmed.dropLast(1)).append(' ')
            // Comment and empty lines inside a continued instruction are dropped, as Docker does.
            while (index < lines.size && isCommentOrBlank(lines[index])) index++
            if (index >= lines.size) break
            line = lines[index]
            index++
        }
        val logical = instruction.toString().trim()
        val keyword = logical.substringBefore(' ').substringBefore('\t').uppercase()
        val rest = logical.substring(keyword.length).trim()
        when (keyword) {
            "FROM" -> stageName(rest)?.let { stages += it }
            "ARG" -> argsOf(rest, escape).forEach { args.putIfAbsent(it.name, it) }
            "RUN", "COPY", "ADD" -> index = skipHeredocs(rest, lines, index)
        }
    }
    return DockerfileInfo(stages.distinctBy { it.lowercase() }, args.values.toList())
}

/** `\`, or the backtick of an `# escape=`` ` `` directive among the parser directives at the top of the file. */
private fun escapeCharacter(lines: List<String>): Char {
    for (line in lines) {
        val match = DIRECTIVE.matchEntire(line) ?: return '\\'
        val (key, value) = match.destructured
        if (key.equals("escape", ignoreCase = true)) return if (value == "`") '`' else '\\'
    }
    return '\\'
}

private fun isCommentOrBlank(line: String): Boolean = line.isBlank() || line.trimStart().startsWith("#")

/** The name after `AS` in the arguments of a `FROM`, flags (`--platform=…`) skipped; `null` for an unnamed stage. */
private fun stageName(rest: String): String? {
    val words = rest.split(WHITESPACE).filter { it.isNotEmpty() && !it.startsWith("--") }
    return if (words.size >= 3 && words[1].equals("AS", ignoreCase = true)) words[2] else null
}

/** The `name[=default]` words of an `ARG`, with the quotes of the defaults removed. */
private fun argsOf(
    rest: String,
    escape: Char,
): List<BuildArg> =
    words(rest, escape).mapNotNull { word ->
        val name = word.substringBefore('=').trim()
        if (name.isEmpty()) null else BuildArg(name, if ('=' in word) word.substringAfter('=') else null)
    }

/** [text] split at whitespace outside quotes, the quotes removed and escaped characters taken as they are. */
private fun words(
    text: String,
    escape: Char,
): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var inWord = false
    var quote: Char? = null
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == escape && quote != '\'' && index + 1 < text.length -> {
                word.append(text[index + 1])
                inWord = true
                index++
            }

            quote != null && char == quote -> {
                quote = null
            }

            quote != null -> {
                word.append(char)
            }

            char == '"' || char == '\'' -> {
                quote = char
                inWord = true
            }

            char.isWhitespace() -> {
                if (inWord) words += word.toString()
                word.clear()
                inWord = false
            }

            else -> {
                word.append(char)
                inWord = true
            }
        }
        index++
    }
    if (inWord) words += word.toString()
    return words
}

/**
 * Skips the bodies of the heredocs ([rest] of a `RUN`, `COPY` or `ADD` with words `<<EOF` or `<<-"EOF"`) in [lines] from
 * [start], each up to its delimiter line; returns the index after the last body.
 */
private fun skipHeredocs(
    rest: String,
    lines: List<String>,
    start: Int,
): Int {
    var index = start
    for (match in HEREDOC.findAll(rest)) {
        val stripTabs = match.groupValues[1] == "-"
        val delimiter = match.groupValues[3]
        while (index < lines.size) {
            val line = lines[index]
            index++
            if ((if (stripTabs) line.trimStart('\t') else line) == delimiter) break
        }
    }
    return index
}

private val WHITESPACE = Regex("""\s+""")
private val DIRECTIVE = Regex("""#\s*([A-Za-z]+)\s*=\s*(\S+)\s*""")

/** A heredoc word, as BuildKit takes them: a word starting with `<<` (`3<<EOF` too), not `$((1<<N))` or `<<<word`. */
private val HEREDOC = Regex("""(?:^|\s)\d*<<(-?)(["']?)([A-Za-z_][A-Za-z0-9_]*)\2(?=\s|$)""")
