package dev.sluide.findinfiles.search

import java.nio.file.Path

/**
 * Which files a search reads, from the mask field: comma-separated globs, `!glob` excludes. A file is searched when
 * no exclude matches it and an include does (or there is no include). A glob without `/` matches the file name, one
 * with `/` the path relative to its root (`/`-separated); a leading `/` anchors the glob at the root (`/README.md`
 * matches only the root's). `*` and `?` stay inside one path part, `**` crosses parts (`**` + `/` also matches no
 * folder at all); every other character, `[` and `{` too, is literal. Case-insensitive. Matching never backtracks: it
 * takes time proportional to the glob's length times the name's.
 */
internal class FileMask private constructor(
    private val includes: List<Glob>,
    private val excludes: List<Glob>,
) {
    /** Whether [file], found under [root], is searched. */
    fun matches(
        root: Path,
        file: Path,
    ): Boolean {
        if (includes.isEmpty() && excludes.isEmpty()) return true
        val name = file.fileName?.toString().orEmpty()
        val path by lazy { root.relativize(file).joinToString("/") }

        fun Glob.matches() = matches(if (onPath) path else name)
        return (includes.isEmpty() || includes.any { it.matches() }) && excludes.none { it.matches() }
    }

    companion object {
        /** The mask of [text]; blank (or only blank parts) searches all files. Never throws. */
        fun parse(text: String): FileMask {
            val includes = mutableListOf<Glob>()
            val excludes = mutableListOf<Glob>()
            for (part in text.split(',').map(String::trim)) {
                val excluded = part.startsWith('!')
                val glob = if (excluded) part.substring(1).trim() else part
                val anchored = glob.removePrefix("/")
                if (anchored.isEmpty()) continue
                val compiled = Glob(anchored, onPath = '/' in glob)
                if (excluded) excludes += compiled else includes += compiled
            }
            return FileMask(includes, excludes)
        }
    }
}

/** One glob of a [FileMask], matched against a file name or a relative path ([onPath]). */
private class Glob(
    glob: String,
    val onPath: Boolean,
) {
    private sealed interface Token {
        /** One character, compared ignoring case. */
        data class Literal(
            val char: Char,
        ) : Token

        /** `?`: one character other than `/`. */
        data object AnyChar : Token

        /** `*`: any run of characters other than `/`. */
        data object Star : Token

        /** `**`: any run of characters. */
        data object AnyRun : Token

        /** `**` + `/`: nothing, or any run ending with `/` (no folder or any folders). */
        data object AnyFolders : Token
    }

    private val tokens: List<Token> = tokenize(glob)

    /**
     * Whether the glob matches all of [text]: dynamic programming over the tokens, where `reached[j]` tells whether
     * the tokens so far match the first `j` characters; each token turns one such row into the next in one pass.
     */
    fun matches(text: String): Boolean {
        var reached = BooleanArray(text.length + 1).also { it[0] = true }
        for (token in tokens) {
            val next = BooleanArray(text.length + 1)
            when (token) {
                is Token.Literal -> {
                    for (j in text.indices) next[j + 1] = reached[j] && text[j].equals(token.char, ignoreCase = true)
                }

                Token.AnyChar -> {
                    for (j in text.indices) next[j + 1] = reached[j] && text[j] != '/'
                }

                Token.Star -> {
                    next[0] = reached[0]
                    for (j in 1..text.length) next[j] = reached[j] || (next[j - 1] && text[j - 1] != '/')
                }

                Token.AnyRun -> {
                    next[0] = reached[0]
                    for (j in 1..text.length) next[j] = reached[j] || next[j - 1]
                }

                Token.AnyFolders -> {
                    // Whether the tokens so far match some prefix up to here, where the run may start.
                    var startSeen = reached[0]
                    next[0] = reached[0]
                    for (j in 1..text.length) {
                        next[j] = reached[j] || (startSeen && text[j - 1] == '/')
                        startSeen = startSeen || reached[j]
                    }
                }
            }
            reached = next
        }
        return reached[text.length]
    }

    private companion object {
        /** [glob] as tokens; a run of two or more `*` is one `**`. */
        fun tokenize(glob: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var index = 0
            while (index < glob.length) {
                val char = glob[index]
                if (char != '*') {
                    tokens += if (char == '?') Token.AnyChar else Token.Literal(char)
                    index++
                    continue
                }
                val stars = glob.drop(index).takeWhile { it == '*' }.length
                index += stars
                tokens +=
                    when {
                        stars == 1 -> {
                            Token.Star
                        }

                        glob.getOrNull(index) == '/' -> {
                            index++
                            Token.AnyFolders
                        }

                        else -> {
                            Token.AnyRun
                        }
                    }
            }
            return tokens
        }
    }
}
