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
 * Builds a tab titled `title` for `model`: `openScratch` is the tool's callback for Open as Scratch, `onClose` must
 * run when the tab is closed.
 */
internal typealias TabFactory = (
    title: String,
    model: TranslateModel,
    openScratch: (text: String, extension: String) -> Unit,
    onClose: () -> Unit,
) -> EditorTab

/**
 * The Translate tool of one window (ADR 0126, ADR 0129): every [createTab] and [translate] opens a new tab
 * "Translate 1", "Translate 2", … (numbers only increase) with a model of its own: text, languages, translation and
 * sentence cache. Closing a tab ends its work and drops its text; closing the window ends all. The `ide` module sets
 * [openTab] and [openScratch]. Used on the JavaFX thread.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Translate internal constructor(
    private val newModel: () -> TranslateModel,
    private val newTab: TabFactory,
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
        InstallerModelInstalls(installer, sizes::bytes, tasks).let { installs ->
            { TranslateModel(engine, detector, installs, preferences, Platform::runLater) }
        },
        realTabs(fontSettings),
        Platform::runLater,
    )

    /** Opens a tab in the window's editor area; set by ide. */
    var openTab: (EditorTab) -> Unit = {}

    /** Opens a new scratch file with a text and an extension (`md` or `txt`); set by ide. */
    var openScratch: (text: String, extension: String) -> Unit = { _, _ -> }

    private var counter = 0
    private val open = mutableSetOf<TranslateModel>()

    private fun newTabAndModel(): Pair<EditorTab, TranslateModel> {
        val model = newModel()
        open += model
        val tab =
            newTab("Translate ${++counter}", model, { text, extension -> openScratch(text, extension) }) {
                model.dispose()
                open -= model
            }
        return tab to model
    }

    /** A new numbered Translate tab with a model of its own. */
    fun createTab(): EditorTab = newTabAndModel().first

    /** Extends the window's code editors with "Translate Selection" and "Translate" (ADR 0126). */
    val editorExtension: CodeEditorExtension = TranslateExtension(::translate)

    /** Opens a new tab with [text] and translates at once (when a target is chosen and the models are in). */
    fun translate(
        text: String,
        markdown: Boolean,
    ) {
        val (tab, model) = newTabAndModel()
        model.open(text, markdown)
        openTab(tab)
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

    /** Ends the translation work of all open tabs; called when the window closes. */
    override fun close() {
        open.toList().forEach(TranslateModel::dispose)
        open.clear()
    }
}

/** The real tabs: a [TranslateView] in the editor font. */
private fun realTabs(fontSettings: FontSettings): TabFactory {
    val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)
    return {
        title,
        model,
        openScratch,
        onClose,
        ->
        TranslateTab(title, TranslateView(model, editorCss, openScratch), onClose)
    }
}
