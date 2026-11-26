package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * PlantUML by a `@start<type>` line (`@startuml`, `@startmindmap`, …, with an optional name after a blank or in
 * parentheses) as the first line that is neither blank nor a `'` comment, and a later line starting with `@end`: what
 * PlantUML needs for a block (ADR 0193). 95, above the code heuristics' cap of 90: a class diagram's members (`fun`,
 * `val`) would otherwise tie with Kotlin, which comes first.
 */
internal object PlantUmlRule : TextTypeSource {
    const val SCORE = 95

    private val startLine = Regex("""@start[a-z]+(?:[ \t(].*)?""")

    override fun scores(text: String): List<TypeScore> {
        val lines =
            text
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("'") }
                .toList()
        val first = lines.firstOrNull() ?: return emptyList()
        if (!startLine.matches(first) || lines.drop(1).none { it.startsWith("@end") }) return emptyList()
        return listOf(TypeScore(TextTypes.PLANTUML, SCORE))
    }
}
