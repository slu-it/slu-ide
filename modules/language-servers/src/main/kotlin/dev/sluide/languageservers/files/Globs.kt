package dev.sluide.languageservers.files

private val log = System.getLogger("dev.sluide.languageservers.files.Globs")

/**
 * The glob patterns of LSP file watchers over `/`-separated paths: `*` is any run of characters in one segment, `?`
 * one character of a segment, `**` any number of segments (also none, so a trailing `**` after `src/` matches `src`
 * too), `{a,b}` either alternative, `[a-z]` and `[!a-z]` one character in or out of the class.
 *
 * A glob is read into a [GlobNode] tree and matched by a [GlobMatcher], whose time is bounded by path length times
 * program size (up to 3 instructions per glob character). A language server is untrusted: no glob may make a match
 * slow, and none may nest deep enough to overflow the stack.
 *
 * Globs and paths are read by code point, like the regex this replaced, so `?` is one emoji. A literal character
 * outside the basic plane matches itself; the regex quoted each half of its surrogate pair alone and matched nothing.
 */
internal object Globs {
    /**
     * The longest glob read. A match takes at most path length times the glob's length in steps, so this keeps one
     * registration of a server from stalling the window.
     */
    const val MAX_GLOB_LENGTH = 1000

    /** A matcher for [glob]; `null` (logged) if it is longer than [MAX_GLOB_LENGTH] or cannot be read. */
    fun matcher(glob: String): GlobMatcher? {
        if (glob.length > MAX_GLOB_LENGTH) {
            log.log(
                System.Logger.Level.INFO,
                "Refused file watcher glob of ${glob.length} characters (more than $MAX_GLOB_LENGTH)",
            )
            return null
        }
        return try {
            GlobMatcher(parse(glob))
        } catch (e: UnreadableGlob) {
            log.log(System.Logger.Level.INFO, "Unreadable file watcher glob $glob: ${e.message}")
            null
        }
    }

    private class UnreadableGlob(
        message: String,
    ) : Exception(message)

    /** The braces being read: the finished alternatives, and the parts of the one in progress. */
    private class Group {
        val options = mutableListOf<GlobNode>()
        var parts = mutableListOf<GlobNode>()

        fun endOption() {
            options += GlobNode.Sequence(parts)
            parts = mutableListOf()
        }
    }

    // What `.` matches in a regex: everything but line terminators.
    private val lineTerminators = intArrayOf('\n'.code, '\r'.code, 0x85, 0x2028, 0x2029)
    private val anyCharacter = GlobNode.Atom { it !in lineTerminators }
    private val notSlash = GlobNode.Atom { it != '/'.code }
    private val slash = GlobNode.Atom { it == '/'.code }

    private fun parse(glob: String): GlobNode {
        val groups = mutableListOf(Group())
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            val braces = groups.size - 1
            val parts = groups.last().parts
            when {
                c == '/' && glob.startsWith("/**", i) && endsAlternative(glob, starsEnd(glob, i + 1), braces) -> {
                    // A trailing `/**`: the folder itself or anything below it.
                    parts += GlobNode.Optional(GlobNode.Sequence(listOf(slash, GlobNode.Star(anyCharacter))))
                    i = starsEnd(glob, i + 1)
                    continue
                }

                c == '*' && glob.startsWith("**", i) -> {
                    var after = starsEnd(glob, i)
                    val previous = glob.getOrNull(i - 1)
                    val segmentStart = previous == null || previous == '/' || (braces > 0 && previous in "{,")
                    if (segmentStart && after < glob.length && glob[after] == '/') {
                        // `**/`: any leading folders, or none.
                        parts += GlobNode.Optional(GlobNode.Sequence(listOf(GlobNode.Star(anyCharacter), slash)))
                        after++
                    } else {
                        parts += GlobNode.Star(anyCharacter)
                    }
                    i = after
                    continue
                }

                c == '*' -> {
                    parts += GlobNode.Star(notSlash)
                }

                c == '?' -> {
                    parts += notSlash
                }

                c == '{' -> {
                    groups += Group()
                }

                c == '}' && braces > 0 -> {
                    val group = groups.removeLast()
                    group.endOption()
                    groups.last().parts += GlobNode.Alternation(group.options)
                }

                c == ',' && braces > 0 -> {
                    groups.last().endOption()
                }

                c == '[' -> {
                    val end = glob.indexOf(']', i + 2)
                    if (end < 0) {
                        parts += literal('['.code)
                    } else {
                        parts += characterClass(glob.substring(i + 1, end))
                        i = end
                    }
                }

                else -> {
                    val codePoint = glob.codePointAt(i)
                    parts += literal(codePoint)
                    i += Character.charCount(codePoint) - 1
                }
            }
            i++
        }
        if (groups.size > 1) throw UnreadableGlob("Unclosed group")
        return GlobNode.Sequence(groups.single().parts)
    }

    private fun literal(codePoint: Int) = GlobNode.Atom { it == codePoint }

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
     * The class between `[` and `]`: `!` or `^` first negates, every other character is itself, and `a-c` is a range.
     * A negated class never matches `/`, which separates segments: the `/` is put in front of its characters, so a
     * leading `-` makes a range from `/` (as the regex this replaced did).
     */
    private fun characterClass(body: String): GlobNode.Atom {
        val negated = body.startsWith('!') || body.startsWith('^')
        val chars = (if (negated) "/" + body.drop(1) else body).codePoints().toArray()
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < chars.size) {
            if (i + 2 < chars.size && chars[i + 1] == '-'.code) {
                if (chars[i + 2] < chars[i]) throw UnreadableGlob("Illegal character range")
                ranges += chars[i]..chars[i + 2]
                i += 3
            } else {
                ranges += chars[i]..chars[i]
                i++
            }
        }
        return GlobNode.Atom { codePoint -> ranges.any { codePoint in it } != negated }
    }
}
