package dev.sluide.git

/**
 * How the lines of one ignore file decide paths (ADR 0080); [lines] by 1-based line number, only lines that decide at
 * least one path. [complete] is false when the candidates were capped.
 */
class GitIgnoreRules(
    val lines: Map<Int, GitIgnoreLineUse>,
    val complete: Boolean,
)

/**
 * One line's use: untracked paths it [ignored] or (a `!` line) [unignored], tracked files it [tracked] (git keeps
 * tracking them); up to 10 [examples] of the untracked ones and 10 [trackedExamples], relative to the ignore file's
 * folder with `/`, folders ending in `/`, in git's order.
 */
data class GitIgnoreLineUse(
    val ignored: Int,
    val unignored: Int,
    val tracked: Int,
    val examples: List<String>,
    val trackedExamples: List<String>,
)
