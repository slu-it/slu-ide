package dev.sluide.translation.engine

/** One Opus-MT model to download and run: a direction (`de`→`en`) at a [quality]; the key of its install state. */
data class PairModel(
    val source: String,
    val target: String,
    val quality: Quality,
) {
    /** The model's name in the catalog and on disk, `de-en`. */
    val pair: String get() = "$source-$target"
}

/** How a source language reaches a target: one model, or two chained through English. */
sealed interface Route {
    /** The models in the order they run, as pairs like `de-en`. */
    val pairs: List<String>

    data class Direct(
        val model: String,
    ) : Route {
        override val pairs get() = listOf(model)
    }

    data class ViaEnglish(
        val first: String,
        val second: String,
    ) : Route {
        override val pairs get() = listOf(first, second)
    }
}

/** A translation that failed for a reason worth showing (a broken model file, an ONNX Runtime error). */
class TranslationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Translates sentences locally; FX-free and thread-safe. */
interface TranslationEngine {
    /** The languages text can be translated from, by English name. */
    val sources: List<Language>

    /** The languages text can be translated into, by English name. */
    val targets: List<Language>

    /** The way from [source] to [target], or null when there is none (or both are the same language). */
    fun route(
        source: Language,
        target: Language,
    ): Route?

    /** The models of [route] at [quality] that are not installed yet, in route order; empty when it can run. */
    fun missing(
        route: Route,
        quality: Quality,
    ): List<PairModel>

    /**
     * Translates one [sentence] along [route] at [quality]. Blocking; call it on a platform thread, never the FX
     * thread (native calls pin virtual threads). Polls [cancelled] between decoder steps and throws
     * [java.util.concurrent.CancellationException] when it turns true; throws [TranslationException] when a model is
     * missing or fails.
     */
    fun translate(
        sentence: String,
        route: Route,
        quality: Quality,
        cancelled: () -> Boolean,
    ): String
}
