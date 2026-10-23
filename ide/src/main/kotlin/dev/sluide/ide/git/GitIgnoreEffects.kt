package dev.sluide.ide.git

import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreLineEffect
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.git.GitIgnoreRules
import dev.sluide.git.GitStatusService

/**
 * The window service through which `.gitignore` views learn which of their lines have an effect (ADR 0080): git
 * decides on its diff thread, the answer comes on the JavaFX thread. Tell it [IgnoreRuleEffects.changed] when the git
 * status changes.
 */
internal fun GitStatusService.ignoreRuleEffects(): IgnoreRuleEffects =
    IgnoreRuleEffects().apply {
        lookup = { file, done -> ignoreRulesAsync(file) { done(it?.toEffects()) } }
    }

/** Git counts lines from 1, the editor from 0. */
internal fun GitIgnoreRules.toEffects() =
    IgnoreFileEffects(
        lines.mapKeys { (line, _) -> line - 1 }.mapValues { (_, use) ->
            IgnoreLineEffect(use.ignored, use.unignored, use.tracked, use.examples, use.trackedExamples)
        },
        complete,
    )
