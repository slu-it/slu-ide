package dev.sluide.git.cli

import java.nio.charset.Charset

/** The characters with a meaning in a POSIX extended regular expression. */
private const val REGEX_SPECIAL = ".[]()*+?{}|^$\\"

/** [text] as a POSIX extended regular expression that matches it literally. */
internal fun escapeRegex(text: String): String =
    buildString { for (char in text) append(if (char in REGEX_SPECIAL) "\\$char" else char) }

/**
 * The patterns that match an author whose name or email is [value], ignoring case by turning ASCII letters into
 * bracket expressions (`[Aa]`) and escaping the rest: the name from the start of git's `Name <email> …` up to ` <`,
 * the email between `<` and `>`.
 */
internal fun authorPatterns(value: String): List<String> {
    val pattern =
        buildString {
            for (char in value) {
                when {
                    char in 'a'..'z' || char in 'A'..'Z' -> {
                        append(
                            '[',
                        ).append(char.uppercaseChar()).append(char.lowercaseChar()).append(']')
                    }

                    char in REGEX_SPECIAL -> {
                        append('\\').append(char)
                    }

                    else -> {
                        append(char)
                    }
                }
            }
        }
    return listOf("^$pattern <", "<$pattern>")
}

/** The charset the JVM turns process arguments into bytes with. */
internal val argumentCharset: Charset =
    System.getProperty("sun.jnu.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charset.defaultCharset()

/**
 * [pattern] as a process argument for a regular expression of git (ADR 0039): every character [charset] cannot
 * encode, which the JVM would turn into `?`, becomes `.` once per byte of its UTF-8 encoding. In such a locale git
 * matches byte by byte, so it still matches the character (and a little more). Inside a bracket expression it does
 * not work.
 */
internal fun encodableArgument(
    pattern: String,
    charset: Charset = argumentCharset,
): String {
    val encoder = charset.newEncoder()
    if (encoder.canEncode(pattern)) return pattern
    return buildString {
        var index = 0
        while (index < pattern.length) {
            val end = pattern.offsetByCodePoints(index, 1)
            val character = pattern.substring(index, end)
            if (encoder.canEncode(character)) {
                append(character)
            } else {
                repeat(character.toByteArray(Charsets.UTF_8).size) { append('.') }
            }
            index = end
        }
    }
}

/**
 * Whether [argument] reaches git unchanged as a process argument: [charset], the one the JVM encodes arguments with,
 * can encode it. In a plain ASCII (POSIX) locale the JVM would turn other characters into `?` (ADR 0038).
 */
internal fun canPassArgument(
    argument: String,
    charset: Charset = argumentCharset,
): Boolean = charset.newEncoder().canEncode(argument)
