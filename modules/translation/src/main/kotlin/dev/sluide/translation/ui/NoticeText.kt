package dev.sluide.translation.ui

import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.session.SetupProblem
import kotlin.math.roundToLong

/**
 * What the setup notice says: `German → English (Medium) is not installed`, or for a route through English what is
 * still missing: `German → Swedish (Medium) needs German → English and English → Swedish`. [name] gives the English
 * name of a language code.
 */
internal fun noticeText(
    problem: SetupProblem,
    name: (String) -> String,
): String {
    val what = "${problem.source.name} → ${problem.target.name} (${qualityName(problem.quality)})"
    if (!problem.viaEnglish) return "$what is not installed"
    return "$what needs " + problem.missing.joinToString(" and ") { pairName(it, name) }
}

private fun pairName(
    model: PairModel,
    name: (String) -> String,
) = "${name(model.source)} → ${name(model.target)}"

internal fun qualityName(quality: Quality): String = quality.name.lowercase().replaceFirstChar { it.uppercase() }

/** `Download (220 MB)`, or `Download again` after a failure. */
internal fun downloadLabel(problem: SetupProblem): String =
    if (problem.failure != null) "Download again" else "Download (${formatMegabytes(problem.bytes)})"

/** [bytes] in decimal megabytes, rounded: `220 MB`. */
internal fun formatMegabytes(bytes: Long): String = "${(bytes / 1_000_000.0).roundToLong()} MB"
