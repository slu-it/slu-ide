package dev.sluide.converting

import dev.sluide.converting.editor.OpenInConvertingExtension
import dev.sluide.converting.model.ConvertingModel
import dev.sluide.converting.model.ConvertingScheduler
import dev.sluide.converting.model.FileConverter
import dev.sluide.converting.ui.ConvertingTab
import dev.sluide.converting.ui.ConvertingView
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.application.Platform
import javafx.stage.FileChooser
import javafx.stage.Window
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The Converting tool of one window (ADR 0115): creates its tab and keeps the two sides, so that they outlive the tab
 * (ADR 0110). The `ide` module sets [showTab] and [openScratch]. Closing it (with its window) stops the background
 * thread of the conversions.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Converting internal constructor(
    families: List<FormatFamily<*>>,
    fontSettings: FontSettings,
    dispatch: (Runnable) -> Unit,
    scheduler: ConvertingScheduler,
) : AutoCloseable {
    /** Converts with all [families] (Spring beans of any module), results handed to the FX thread. */
    @Autowired
    constructor(families: List<FormatFamily<*>>, fontSettings: FontSettings) :
        this(families, fontSettings, Platform::runLater, ConvertingScheduler.singleThread())

    /** Shows the Converting tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** Opens a new scratch file with an extension and a text; null hides the sides' "Open as Scratch". Set by ide. */
    var openScratch: ((extension: String, text: String) -> Unit)? = null

    /** The two sides and the options of the window's tool; outlives the tab. */
    internal val model = ConvertingModel(families, dispatch, scheduler = scheduler)

    private val fileConverter = FileConverter(families)

    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    /** A new Converting tab on [model]. */
    fun createTab(): EditorTab =
        ConvertingTab(ConvertingView(model, editorCss, ::chooseFile, ::chooseSaveFile, openScratch))

    /** Extends the window's code editors with "Open Selection in Converting" and "Open in Converting" (ADR 0115). */
    val editorExtension: CodeEditorExtension = OpenInConvertingExtension(::open)

    /**
     * Puts [text] on the left side as the source, pinned to the format of [extension] (with or without the dot) or
     * detected without one or for an extension no format has, and shows the tab.
     */
    fun open(
        text: String,
        extension: String?,
    ) {
        model.open(text, extension?.let(model::formatOf))
        showTab()
    }

    /**
     * The formats the explorer's "Convert to" offers for [file]: the other formats of the family of its extension's
     * format, none for a family that does not convert files (requests) or an unknown extension.
     */
    fun conversionTargets(file: Path): List<TextFormat> = fileConverter.targets(file)

    /**
     * Converts [file] (up to 1 MiB of UTF-8) to [target] with the window's current options; callable off the FX thread.
     */
    fun convertFile(
        file: Path,
        target: TextFormat,
    ): FileConversion = fileConverter.convert(file, target, model.options)

    /** Stops the conversions; called when the window closes. */
    override fun close() = model.dispose()

    private fun chooseFile(window: Window?): Path? =
        FileChooser()
            .apply {
                title = "Open File"
            }.showOpenDialog(window)
            ?.toPath()

    private fun chooseSaveFile(
        window: Window?,
        suggestedName: String,
    ): Path? =
        FileChooser()
            .apply {
                title = "Save as"
                initialFileName = suggestedName
            }.showSaveDialog(window)
            ?.toPath()
}
