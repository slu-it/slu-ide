package dev.sluide.translation.ui

import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.session.SourceChoice
import dev.sluide.translation.session.TranslateModel
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF

/** What the source picker shows: `Detect`, `Detect (German)` once detected, or the picked language. */
internal fun sourceLabel(
    source: SourceChoice,
    detected: Language?,
): String =
    when (source) {
        SourceChoice.Detect -> detected?.let { "Detect (${it.name})" } ?: "Detect"
        is SourceChoice.Picked -> source.language.name
    }

/** The scratch file's extension for the translation: `md` with the Markdown toggle on, else `txt`. */
internal fun scratchExtension(markdown: Boolean): String = if (markdown) "md" else "txt"

/**
 * The toolbar of the Translate tab: the source and target pickers, quality, the Markdown toggle and the status text,
 * and at the right Copy and Open as Scratch. It forwards the user's choices to [model] and shows what [show] is given.
 */
internal class TranslateToolbar(
    private val model: TranslateModel,
    private val openScratch: (text: String, extension: String) -> Unit,
) {
    private val source =
        ChoicePicker<SourceChoice>(
            "translate-source",
            "Source language",
            listOf(Choice<SourceChoice>(SourceChoice.Detect, "Detect")) +
                model.sources.map { Choice(SourceChoice.Picked(it), it.name) },
        ) { model.chooseSource(it) }
    private val target =
        ChoicePicker<Language>(
            "translate-target",
            "Target language",
            model.targets.map { Choice(it, it.name) },
        ) { model.chooseTarget(it) }
    private val quality =
        ComboBox<Quality>().apply {
            id = "translate-quality"
            isFocusTraversable = false
            tooltip = Tooltip("Quality: Medium is smaller and faster, High is more exact")
            items.setAll(Quality.entries)
            setCellFactory { QualityCell() }
            buttonCell = QualityCell()
            valueProperty().subscribe { _, chosen -> if (!updating && chosen != null) model.chooseQuality(chosen) }
        }
    private val markdown =
        ToggleButton("Markdown").apply {
            id = "translate-markdown"
            isFocusTraversable = false
            tooltip = Tooltip("Keep Markdown code, links and markup, translate only the text")
            setOnAction { model.chooseMarkdown(isSelected) }
        }
    private val status =
        Label().apply {
            id = "translate-status"
            styleClass += "translate-status"
        }
    private val copy =
        Button(null, FontIcon(MaterialDesignC.CONTENT_COPY)).apply {
            id = "translate-copy"
            styleClass += "translate-button"
            isFocusTraversable = false
            tooltip = Tooltip("Copy the translation")
            setOnAction {
                Clipboard.getSystemClipboard().setContent(
                    ClipboardContent().apply { putString(model.output) },
                )
            }
        }
    private val scratch =
        Button(null, FontIcon(MaterialDesignF.FILE_PLUS_OUTLINE)).apply {
            id = "translate-scratch"
            styleClass += "translate-button"
            isFocusTraversable = false
            tooltip = Tooltip("Open the translation as a scratch file")
            setOnAction { openScratch(model.output, scratchExtension(model.markdown)) }
        }
    private var updating = false

    val node: HBox =
        HBox(
            6.0,
            source.node,
            target.node,
            quality,
            markdown,
            status,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            copy,
            scratch,
        ).apply {
            styleClass += "translate-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    /** Shows [model]'s state, with [statusText] as the status. */
    fun show(statusText: String) {
        source.show(sourceLabel(model.source, model.detected), model.source)
        target.show(model.target?.name ?: "Choose target…", model.target)
        updating = true
        try {
            quality.value = model.quality
        } finally {
            updating = false
        }
        markdown.isSelected = model.markdown
        status.text = statusText
        copy.isDisable = model.output.isEmpty()
        scratch.isDisable = model.output.isEmpty()
    }

    private class QualityCell : javafx.scene.control.ListCell<Quality>() {
        override fun updateItem(
            item: Quality?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else qualityName(item)
        }
    }
}
