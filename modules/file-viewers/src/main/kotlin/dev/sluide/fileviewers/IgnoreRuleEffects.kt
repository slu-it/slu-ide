package dev.sluide.fileviewers

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which lines of a window's `.gitignore` files have an effect (ADR 0080); a window service (ADR 0070) the window sets
 * up with git. Used on the FX thread.
 */
class IgnoreRuleEffects {
    /**
     * Looks up the effects of the lines of [file] as saved; answers [done] later on the FX thread, `null` when unknown
     * (no repository, no git, no trust, failure). Set by the window; by default nothing is known.
     */
    var lookup: (file: Path, done: (IgnoreFileEffects?) -> Unit) -> Unit = { _, done -> done(null) }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Tells the views that the effects may have changed (the window's git status changed), so they look them up again. */
    fun changed() = listeners.forEach { it() }

    /** Calls [listener] on each [changed] until the returned handle is closed. */
    fun onChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

/**
 * The effects of the lines of one ignore file: [lines] by 0-based line index, only lines that decide at least one path;
 * [complete] is false when git checked only part of the paths.
 */
class IgnoreFileEffects(
    val lines: Map<Int, IgnoreLineEffect>,
    val complete: Boolean,
)

/**
 * One line's effect: untracked paths it [ignored] or, a `!` line, [unignored]; tracked files it [tracked]; up to 10
 * [examples] of the untracked paths and 10 [trackedExamples].
 */
data class IgnoreLineEffect(
    val ignored: Int,
    val unignored: Int,
    val tracked: Int,
    val examples: List<String>,
    val trackedExamples: List<String>,
)
