package dev.sluide.translation

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.translation.editor.TranslateExtension
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.TranslationEngine
import dev.sluide.translation.session.InstallerModelInstalls
import dev.sluide.translation.session.ModelInstalls
import dev.sluide.translation.session.TranslateModel
import dev.sluide.translation.ui.TranslateTab
import dev.sluide.translation.ui.TranslateView
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/** How many bytes downloading [models] would still fetch (the engine's catalog); cheap, called on the FX thread. */
fun interface DownloadSizes {
    fun bytes(models: List<PairModel>): Long
}

/**
 * The Translate tool of one window (ADR 0126): creates its tab and keeps the text, languages, quality and translation,
 * so that they outlive the tab (ADR 0110). The `ide` module sets [showTab] and [openScratch]. Closing it (with its
 * window) ends the translation work.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Translate internal constructor(
    engine: TranslationEngine,
    detector: LanguageDetector,
    installs: ModelInstalls,
    preferences: TranslationPreferences,
    fontSettings: FontSettings,
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor(
        engine: TranslationEngine,
        detector: LanguageDetector,
        installer: ModelInstaller<PairModel>,
        sizes: DownloadSizes,
        tasks: BackgroundTasks,
        preferences: TranslationPreferences,
        fontSettings: FontSettings,
    ) : this(
        engine,
        detector,
        InstallerModelInstalls(installer, sizes::bytes, tasks),
        preferences,
        fontSettings,
        Platform::runLater,
    )

    /** Shows the Translate tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** Opens a new scratch file with a text and an extension (`md` or `txt`); set by ide. */
    var openScratch: (text: String, extension: String) -> Unit = { _, _ -> }

    /** The text, languages and translation of the window's tool; outlives the tab. */
    internal val model = TranslateModel(engine, detector, installs, preferences, dispatch)

    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    /** A new Translate tab on the window's model. */
    fun createTab(): EditorTab =
        TranslateTab(TranslateView(model, editorCss) { text, extension -> openScratch(text, extension) })

    /** Extends the window's code editors with "Translate Selection" and "Translate" (ADR 0126). */
    val editorExtension: CodeEditorExtension = TranslateExtension(::translate)

    /** Puts [text] in the tab, shows it and translates at once (when a target is chosen and the models are in). */
    fun translate(
        text: String,
        markdown: Boolean,
    ) {
        model.open(text, markdown)
        showTab()
    }

    /**
     * Reads the saved [file] (UTF-8, up to 1 MiB) off the FX thread, then [translate]s it (Markdown for `.md`);
     * [onError] gets a readable message on the FX thread when it cannot be read.
     */
    fun readFile(
        file: Path,
        onError: (String) -> Unit,
    ) {
        NamedThreads.virtual("sluide-translate-read-file") {
            val result = readableFile(file)
            dispatch(
                Runnable {
                    when (result) {
                        is ReadableFile.Text -> translate(result.text, result.markdown)
                        is ReadableFile.Unreadable -> onError(result.message)
                    }
                },
            )
        }
    }

    /** Ends the translation work; called when the window closes. */
    override fun close() = model.dispose()
}
