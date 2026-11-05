package dev.sluide.translation.session

import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality

/** The source language of the Translate tab: detected from the text, or picked by the user. */
internal sealed interface SourceChoice {
    data object Detect : SourceChoice

    data class Picked(
        val language: Language,
    ) : SourceChoice
}

/**
 * The translation from [source] to [target] at [quality] cannot run: [missing] are its models that are not installed
 * (in route order, as the engine said), [viaEnglish] whether the route goes through English. [bytes] is what a
 * download of the models still missing would fetch. [progress] (0 to 1, over the missing models) is set while one of
 * them downloads; [failure] is the message of a download that failed.
 */
internal data class SetupProblem(
    val source: Language,
    val target: Language,
    val quality: Quality,
    val missing: List<PairModel>,
    val viaEnglish: Boolean,
    val bytes: Long,
    val progress: Double?,
    val failure: String?,
) {
    val downloading: Boolean get() = progress != null
}

/** The status texts of the Translate tab. */
internal object TranslateStatus {
    const val CHOOSE_TARGET = "Choose a target language"
    const val DETECTING = "Detecting…"
    const val NOT_DETECTED = "Language not detected. Choose the source."
    const val TRANSLATED = "Translated"
    const val TOO_LARGE = "Text larger than 1 MiB"
    const val FAILED = "Translation failed"

    fun translating(
        current: Int,
        total: Int,
    ) = "Translating $current of $total…"

    fun already(language: Language) = "Already ${language.name}"

    fun noRoute(
        source: Language,
        target: Language,
    ) = "No translation from ${source.name} to ${target.name}"
}
