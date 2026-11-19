package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/** A git diff by its `diff --git` header; a unified diff by its `---`, `+++` and `@@` hunk lines. */
internal object DiffRule : TextTypeSource {
    const val GIT_SCORE = 95

    /** Above Markdown's and the code heuristics' highest score: a diff of a README or of code stays a diff. */
    const val UNIFIED_SCORE = 92

    private val git = Signal("""^diff --git \S""", 1)
    private val unified =
        listOf(
            Signal("""^--- \S""", 1),
            Signal("""^\+\+\+ \S""", 1),
            Signal("""^@@ -\d++(?:,\d++)? \+\d++(?:,\d++)? @@""", 1),
        )

    override fun scores(text: String): List<TypeScore> =
        when {
            git.foundIn(text) -> listOf(TypeScore(TextTypes.DIFF, GIT_SCORE))
            weigh(text, unified) == unified.size -> listOf(TypeScore(TextTypes.DIFF, UNIFIED_SCORE))
            else -> emptyList()
        }
}
