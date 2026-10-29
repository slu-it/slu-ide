package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import dev.sluide.coloring.color.CssColor
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import java.nio.file.Path

/** The colors saved in the project: a Save button for the current color and the saved swatches. */
internal class PaletteSection(
    private val state: ColoringState,
    private val savedColors: SavedColors,
    private val projectFolder: ObservableValue<Path?>,
) {
    private val save =
        Button("Save", FontIcon(MaterialDesignS.STAR_PLUS_OUTLINE)).apply {
            id = "coloring-save-color"
            tooltip = Tooltip("Save the current color")
            setOnAction { attempt(savedColors.save(projectFolder.value, state.current.get())) }
        }
    private val swatches = FlowPane(6.0, 6.0).apply { styleClass += "coloring-saved-colors" }
    private val empty = Label("No saved colors yet").apply { styleClass += "coloring-muted" }
    private val error =
        Label("The saved colors of this project cannot be written; see the log.").apply {
            styleClass += "coloring-muted"
            isWrapText = true
        }
    private val shown = mutableListOf<Swatch>()

    /** The colors of the project as last loaded: the Save button is decided on it, not on the store. */
    private var loaded: List<CssColor> = emptyList()
    private val changes: Subscription =
        savedColors.onChanged { changed ->
            if (changed == projectFolder.value?.toAbsolutePath()?.normalize()) refresh()
        }
    private val currentListener = ChangeListener<Any?> { _, _, _ -> refreshSave() }
    private val folderListener = ChangeListener<Path?> { _, _, _ -> refresh() }

    val node: Node =
        VBox(
            6.0,
            HBox(
                6.0,
                Label("Saved colors").apply { styleClass += "coloring-section-title" },
                Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                save,
            ).apply { alignment = Pos.CENTER_LEFT },
            swatches,
            empty,
            error,
        )

    init {
        state.current.addListener(currentListener)
        projectFolder.addListener(folderListener)
        error.isVisible = false
        error.isManaged = false
        refresh()
    }

    private fun refresh() {
        shown.forEach(Swatch::dispose)
        shown.clear()
        error.isVisible = false
        error.isManaged = false
        val colors = savedColors.saved(projectFolder.value)
        loaded = colors
        swatches.children.setAll(
            colors.map { color ->
                Swatch.of(color, state).also { swatch ->
                    swatch.setOnContextMenuRequested { e ->
                        ContextMenu(
                            MenuItem(
                                "Remove",
                            ).apply { setOnAction { attempt(savedColors.remove(projectFolder.value, color)) } },
                        ).show(swatch, e.screenX, e.screenY)
                        e.consume()
                    }
                    shown += swatch
                }
            },
        )
        empty.isVisible = colors.isEmpty()
        empty.isManaged = colors.isEmpty()
        refreshSave()
    }

    private fun refreshSave() {
        save.isDisable = SavedColors.containsColor(loaded, state.current.get())
    }

    /** A refused change is shown; a successful one reloads through [SavedColors.onChanged]. */
    private fun attempt(done: Boolean) {
        error.isVisible = !done
        error.isManaged = !done
    }

    /** Releases the listeners on the window's state and on the saved colors, and the swatches. */
    fun dispose() {
        changes.unsubscribe()
        state.current.removeListener(currentListener)
        projectFolder.removeListener(folderListener)
        shown.forEach(Swatch::dispose)
        shown.clear()
    }
}
