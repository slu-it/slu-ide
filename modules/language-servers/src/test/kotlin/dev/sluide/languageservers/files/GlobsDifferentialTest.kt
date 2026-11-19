package dev.sluide.languageservers.files

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random
import java.util.regex.PatternSyntaxException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The linear [GlobMatcher] reads, refuses and matches exactly like the regex translation it replaced. */
class GlobsDifferentialTest {
    private val random = Random(20261119)

    @Test
    fun `random globs are refused and matched like the old regex translation`() {
        var compared = 0
        var refused = 0
        var matched = 0
        repeat(GLOBS) {
            val glob = randomString(GLOB_ALPHABET, MAX_GLOB)
            val old = OldGlobs.regex(glob)
            val matcher = Globs.matcher(glob)
            assertEquals(old == null, matcher == null, "refusal of '$glob'")
            if (old == null) {
                refused++
            } else {
                requireNotNull(matcher)
                repeat(PATHS) {
                    val path = randomString(PATH_ALPHABET, MAX_PATH)
                    val expected = old.matches(path)
                    assertEquals(expected, matcher.matches(path), "'$glob' against '$path'")
                    compared++
                    if (expected) matched++
                }
            }
        }
        // The generator must reach both outcomes often, or the comparison proves little.
        assertTrue(refused > GLOBS / 50, "refused $refused")
        assertTrue(matched > compared / 50, "matched $matched of $compared")
    }

    @Test
    fun `random character classes are refused and matched like the old regex translation`() {
        repeat(GLOBS) {
            val glob = "[" + randomString(CLASS_ALPHABET, 5) + "]"
            val old = OldGlobs.regex(glob)
            val matcher = Globs.matcher(glob)
            assertEquals(old == null, matcher == null, "refusal of '$glob'")
            if (old != null) {
                requireNotNull(matcher)
                for (path in CLASS_PATHS) {
                    assertEquals(old.matches(path), matcher.matches(path), "'$glob' against '$path'")
                }
            }
        }
    }

    // `.` of the old regex stops at every line terminator, so `**` does; `*` and `?` take them.
    @ParameterizedTest(name = "{0} does not match a path with a line terminator in it")
    @ValueSource(strings = ["\r", "\u0085", "\u2028", "\u2029", "\n"])
    fun `a double star stops at every line terminator the old regex dot stops at`(terminator: String) {
        for (glob in listOf("**", "a**b")) {
            val path = "a${terminator}b"
            assertEquals(false, OldGlobs.regex(glob)?.matches(path), "oracle: '$glob'")
            assertEquals(
                false,
                Globs.matcher(glob)?.matches(path),
                "'$glob' against a path with ${terminator.codePointAt(0)}",
            )
        }
    }

    @Test
    fun `characters outside the basic plane count as one character like in the old regex translation`() {
        val globs =
            listOf("?", "*", "**", "[\uD83D\uDE00-\uD83D\uDE0F]", "[!a]", "a?", "[\uD83D\uDE00]")
        val paths = listOf("\uD83D\uDE00", "\uD83D\uDE05", "a\uD83D\uDE00", "\uD83D", "\uDE00", "a")
        for (glob in globs) {
            val old = requireNotNull(OldGlobs.regex(glob))
            val matcher = requireNotNull(Globs.matcher(glob))
            for (path in paths) assertEquals(old.matches(path), matcher.matches(path), "'$glob' against '$path'")
        }
    }

    private fun randomString(
        alphabet: String,
        max: Int,
    ) = String(CharArray(random.nextInt(max + 1)) { alphabet[random.nextInt(alphabet.length)] })

    private companion object {
        const val GLOBS = 30_000
        const val PATHS = 8
        const val MAX_GLOB = 9
        const val MAX_PATH = 8
        const val GLOB_ALPHABET = "ab/**??{{}},,[]!-.^&\\"
        const val PATH_ALPHABET = "ab/.[-\n\r\u0085\u2028\u2029\\],}!^&"
        const val CLASS_ALPHABET = "ab/!^-.[&\\]"
        val CLASS_PATHS = listOf("a", "b", "/", "-", ".", "!", "^", "[", "&", "\\", "]", "0", "~", "\n", "é")
    }
}

// The oracle: the regex translation of Globs as it was before the linear matcher, copied unchanged.
private object OldGlobs {
    fun regex(glob: String): Regex? =
        try {
            Regex(translate(glob))
        } catch (e: PatternSyntaxException) {
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
                    out.append("(?:/.*)?")
                    i = starsEnd(glob, i + 1)
                    continue
                }

                c == '*' && glob.startsWith("**", i) -> {
                    var after = starsEnd(glob, i)
                    val previous = glob.getOrNull(i - 1)
                    val segmentStart = previous == null || previous == '/' || (braces > 0 && previous in "{,")
                    if (segmentStart && after < glob.length && glob[after] == '/') {
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

    private fun starsEnd(
        glob: String,
        from: Int,
    ): Int {
        var end = from
        while (end < glob.length && glob[end] == '*') end++
        return end
    }

    private fun endsAlternative(
        glob: String,
        index: Int,
        braces: Int,
    ) = index == glob.length || (braces > 0 && glob[index] in "},")

    private fun characterClass(body: String): String {
        val negated = body.startsWith('!') || body.startsWith('^')
        val chars = if (negated) body.drop(1) else body
        val escaped =
            chars.map { ch -> if (ch in "\\[]&^") "\\$ch" else ch.toString() }.joinToString("")
        return if (negated) "[^/$escaped]" else "[$escaped]"
    }
}
