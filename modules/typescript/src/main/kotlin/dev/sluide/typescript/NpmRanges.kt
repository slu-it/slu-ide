package dev.sluide.typescript

/** Whether Thoreau serves a TypeScript version or range: TypeScript 6 or later only. */
enum class Verdict { SUPPORTED, TOO_OLD, UNDECIDABLE }

private const val FIRST_SUPPORTED_MAJOR = 6
private val MAJOR = Regex("""^(\d+)(?:[.+\-].*)?$""")
private val OPERATOR_SPACE = Regex("""([<>=~^]+)\s+""")
private val WHITESPACE = Regex("""\s+""")
private const val NATIVE_PREVIEW = "@typescript/native-preview"

/** The major of a version or lower bound such as `6.0.3`, `v7`, `=7.0.0`, `6.x`, `7.1.0-beta`; null when it has none. */
fun majorOf(version: String): Int? {
    val text = version.trim().trimStart('v', 'V', '=').trim()
    return MAJOR
        .matchEntire(text)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
}

private fun verdictOfMajor(major: Int?): Verdict =
    when {
        major == null -> Verdict.UNDECIDABLE
        major >= FIRST_SUPPORTED_MAJOR -> Verdict.SUPPORTED
        else -> Verdict.TOO_OLD
    }

/** The verdict of an installed version: its major. */
internal fun versionVerdict(version: String): Verdict = verdictOfMajor(majorOf(version))

/**
 * The verdict of a package.json range for `typescript`: the lower bound's major per `||` alternative; all alternatives
 * agreeing gives that verdict, a mix or a range without a lower bound (`*`, `<7`, dist-tags, `workspace:`, `file:`,
 * git and URL specs) gives UNDECIDABLE. `npm:typescript@<range>` is that range; `npm:@typescript/native-preview@...`
 * is TypeScript 7 and SUPPORTED.
 */
fun rangeVerdict(range: String): Verdict {
    var text = range.trim()
    if (text.startsWith("npm:")) {
        val spec = text.removePrefix("npm:")
        if (spec == NATIVE_PREVIEW || spec.startsWith("$NATIVE_PREVIEW@")) return Verdict.SUPPORTED
        if (!spec.startsWith("typescript@")) return Verdict.UNDECIDABLE
        text = spec.removePrefix("typescript@")
    }
    if (text.contains(':') || text.contains('/')) return Verdict.UNDECIDABLE
    val verdicts = text.split("||").map { alternativeVerdict(it) }.toSet()
    return verdicts.singleOrNull() ?: Verdict.UNDECIDABLE
}

/** The major of a comparator's lower bound; a bare `>` on a major-only or x-range version (`>5`, `>5.x`) is the next major. */
private fun lowerBound(comparator: String): Int? {
    val version = comparator.trimStart('>', '=', '~', '^')
    val major = majorOf(version) ?: return null
    val exclusive = comparator.startsWith(">") && !comparator.startsWith(">=")
    val minor = version.trimStart('v', 'V', '=').split('.', limit = 3).getOrNull(1)
    return if (exclusive && (minor == null || minor == "x" || minor == "X" || minor == "*")) major + 1 else major
}

private fun alternativeVerdict(alternative: String): Verdict {
    val text = alternative.trim()
    if (text.isEmpty()) return Verdict.UNDECIDABLE
    val majors =
        if (text.contains(" - ")) {
            listOf(lowerBound(text.substringBefore(" - ").trim()))
        } else {
            OPERATOR_SPACE
                .replace(text, "$1")
                .split(WHITESPACE)
                .filter { !it.startsWith("<") }
                .map { lowerBound(it) }
        }
    if (majors.isEmpty() || majors.any { it == null }) return Verdict.UNDECIDABLE
    return verdictOfMajor(majors.filterNotNull().min())
}
