package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.NamedColors
import dev.sluide.coloring.color.NamedMatch
import dev.sluide.coloring.color.nearestNamed
import dev.sluide.coloring.color.outOfSrgb
import dev.sluide.coloring.color.toRgb8
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import java.nio.file.Path
import java.util.Locale

/**
 * The inspector at the left of the Coloring tab (ADR 0111): the inspected color large, its gamut note, its nearest
 * name (a click inspects the named color), its formats, Save, and "Use for <slot>", which writes it into the slot
 * being edited.
 */
internal class InspectorSection(
    private val state: ColoringState,
    private val savedColors: SavedColors,
    private val projectFolder: ObservableValue<Path?>,
) {
    private val preview =
        Swatch(state.inspected, width = InputSection.PREVIEW_MIN_WIDTH, height = InputSection.PREVIEW_HEIGHT).apply {
            styleClass += "coloring-preview"
            maxWidth = Double.MAX_VALUE
        }
    private val gamutNote =
        Label("Outside sRGB — shown mapped into the gamut").apply {
            styleClass += "coloring-note"
            isWrapText = true
        }
    private val nameLabel = Label().apply { styleClass += "coloring-name" }
    private val nameTip = Tooltip()
    private var match: NamedMatch? = null
    private val formats = FormatsSection(state.inspected)
    private val save =
        Button("Save", FontIcon(MaterialDesignS.STAR_PLUS_OUTLINE)).apply {
            id = "coloring-save-color"
            tooltip = Tooltip("Save this color in the project")
            setOnAction { attempt(savedColors.save(projectFolder.value, state.inspected.get())) }
        }
    private val useFor =
        Button().apply {
            id = "coloring-use-for"
            setOnAction { state.editingColor.set(state.inspected.get()) }
        }
    private val error =
        Label("The saved colors of this project cannot be written; see the log.").apply {
            styleClass += "coloring-muted"
            isWrapText = true
            isVisible = false
            isManaged = false
        }

    /** The colors of the project as last loaded: the Save button is decided on it, not on the store. */
    private var loaded: List<CssColor> = emptyList()
    private val changes: Subscription =
        savedColors.onChanged { changed ->
            if (changed == projectFolder.value?.toAbsolutePath()?.normalize()) reload()
        }
    private val folderListener = ChangeListener<Path?> { _, _, _ -> reload() }
    private val subscriptions: List<Subscription>

    val node: Node =
        VBox(
            6.0,
            Label("Color").apply { styleClass += "coloring-section-title" },
            preview,
            gamutNote,
            nameLabel,
            formats.node,
            FlowPane(6.0, 6.0, save, useFor).apply { alignment = Pos.CENTER_LEFT },
            error,
        ).apply { styleClass += "coloring-inspector" }

    init {
        nameLabel.setOnMouseClicked { e ->
            if (e.button == MouseButton.PRIMARY) {
                // Only "Use for" writes into a slot (ADR 0111); the name shows the named color here.
                match?.let { NamedColors.byName(it.name) }?.let {
                    val alpha =
                        state.inspected
                            .get()
                            .toRgb8()
                            .a
                    state.inspect(CssColor.fromRgb8(it.r, it.g, it.b, alpha))
                }
            }
        }
        projectFolder.addListener(folderListener)
        subscriptions =
            listOf(
                state.inspected.subscribe { color -> showDetails(color) },
                state.inspected.subscribe { _ -> refreshButtons() },
                state.editingColor.subscribe { _ -> refreshButtons() },
                state.editing.subscribe { slot -> useFor.text = "Use for ${slot.title}" },
            )
        reload()
    }

    private fun reload() {
        error.isVisible = false
        error.isManaged = false
        loaded = savedColors.saved(projectFolder.value)
        refreshButtons()
    }

    private fun refreshButtons() {
        val color = state.inspected.get()
        save.isDisable = SavedColors.containsColor(loaded, color)
        useFor.isDisable = color == state.editingColor.get()
    }

    private fun showDetails(color: CssColor) {
        gamutNote.isVisible = color.outOfSrgb
        gamutNote.isManaged = color.outOfSrgb
        val m = nearestNamed(color)
        match = m
        nameLabel.text = if (m.exact) "Name: ${m.name}" else "Nearest name: ${m.name}"
        nameLabel.tooltip =
            if (m.exact) null else nameTip.apply { text = "ΔE OK ${"%.3f".format(Locale.ROOT, m.distance)}" }
    }

    /** A refused change is shown; a successful one reloads through [SavedColors.onChanged]. */
    private fun attempt(done: Boolean) {
        error.isVisible = !done
        error.isManaged = !done
    }

    /** Releases the listeners on the window's state, the project folder and the saved colors. */
    fun dispose() {
        changes.unsubscribe()
        projectFolder.removeListener(folderListener)
        subscriptions.forEach(Subscription::unsubscribe)
        formats.dispose()
        preview.dispose()
    }
}
