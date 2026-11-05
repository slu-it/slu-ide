package dev.sluide.ide.translation

import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.localmodels.ModelSpecs
import dev.sluide.translation.DownloadSizes
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.engine.TranslationEngine
import dev.sluide.translation.engine.TranslationException
import java.nio.file.Path

/**
 * An engine with two languages and no route between them, so that no window test loads a model or downloads one (the
 * machine may have the real models under `~/.sluide`): a translation never starts.
 */
internal object NoTranslationEngine : TranslationEngine, DownloadSizes {
    override val sources = listOf(Language("de", "German"), Language("en", "English"))
    override val targets = sources

    override fun route(
        source: Language,
        target: Language,
    ): Route? = null

    override fun missing(
        route: Route,
        quality: Quality,
    ): List<PairModel> = emptyList()

    override fun translate(
        sentence: String,
        route: Route,
        quality: Quality,
        cancelled: () -> Boolean,
    ): String = throw TranslationException("The test engine does not translate.")

    override fun bytes(models: List<PairModel>): Long = 0
}

/** Detects nothing. */
internal val NoLanguageDetector = LanguageDetector { _, _ -> null }

/** Specs of models that are never installed and have no files: an installer over them never downloads. */
internal class NoModelSpecs(
    private val folder: Path,
) : ModelSpecs<PairModel> {
    override fun files(key: PairModel): List<ModelFile> = emptyList()

    override fun folder(key: PairModel): Path = folder

    override fun installed(key: PairModel) = false

    override fun taskName(
        key: PairModel,
        percent: Int,
    ) = "Test model ${key.pair}"
}

/** An installer for the window tests. */
internal fun noModelInstaller(folder: Path) = ModelInstaller(NoModelSpecs(folder))
