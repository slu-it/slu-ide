package dev.sluide.languageservers.files

import java.util.regex.PatternSyntaxException

private val log = System.getLogger("dev.sluide.languageservers.files.Globs")

/**
 * The glob patterns of LSP file watchers as regular expressions over `/`-separated paths: `*` is any run of
 * characters in one segment, `?` one character of a segment, `**` any number of segments (also none, so a trailing
 * `**` after `src/` matches `src` too), `{a,b}` either alternative, `[a-z]` and `[!a-z]` one character in or out of
 * the class.
 */
internal object Globs {
    /** [glob] as a regex matching whole paths; `null` (logged) if it cannot be read. */
    fun regex(glob: String): Regex? =
        try {
            Regex(translate(glob))
        } catch (e: PatternSyntaxException) {
            log.log(System.Logger.Level.INFO, "Unreadable file watcher glob $glob: ${e.description}")
            null
        }

    private fun translate(glob: String): String {
        val out = StringBuilder()
        var braces = 0
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '/' && glob.startsWith("/**", i) && endsAlternative(glob, starsEnd(glob, i + 1), braces) -> {
                    // A trailing `/**`: the folder itself or anything below it.
                    out.append("(?:/.*)?")
                    i = starsEnd(glob, i + 1)
                    continue
                }

                c == '*' && glob.startsWith("**", i) -> {
                    var after = starsEnd(glob, i)
                    val previous = glob.getOrNull(i - 1)
                    val segmentStart = previous == null || previous == '/' || (braces > 0 && previous in "{,")
                    if (segmentStart && after < glob.length && glob[after] == '/') {
                        // `**/`: any leading folders, or none.
                        out.append("(?:.*/)?")
                        after++
                    } else {
                        out.append(".*")
                    }
                    i = after
                    continue
                }

                c == '*' -> {
                    out.append("[^/]*")
                }

                c == '?' -> {
                    out.append("[^/]")
                }

                c == '{' -> {
                    braces++
                    out.append("(?:")
                }

                c == '}' && braces > 0 -> {
                    braces--
                    out.append(')')
                }

                c == ',' && braces > 0 -> {
                    out.append('|')
                }

                c == '[' -> {
                    val end = glob.indexOf(']', i + 2)
                    if (end < 0) {
                        out.append("\\[")
                    } else {
                        out.append(characterClass(glob.substring(i + 1, end)))
                        i = end
                    }
                }

                else -> {
                    out.append(Regex.escape(c.toString()))
                }
            }
            i++
        }
        return out.toString()
    }

    /** The index after the run of `*` that starts at [from]. */
    private fun starsEnd(
        glob: String,
        from: Int,
    ): Int {
        var end = from
        while (end < glob.length && glob[end] == '*') end++
        return end
    }

    /** Whether [index] ends the glob, or the alternative inside braces. */
    private fun endsAlternative(
        glob: String,
        index: Int,
        braces: Int,
    ) = index == glob.length || (braces > 0 && glob[index] in "},")

    /**
     * The class between `[` and `]`: `!` or `^` first negates (never matching `/`, which separates segments), regex
     * meta characters inside are taken literally.
     */
    private fun characterClass(body: String): String {
        val negated = body.startsWith('!') || body.startsWith('^')
        val chars = if (negated) body.drop(1) else body
        val escaped =
            chars.map { ch -> if (ch in "\\[]&^") "\\$ch" else ch.toString() }.joinToString("")
        return if (negated) "[^/$escaped]" else "[$escaped]"
    }
}
