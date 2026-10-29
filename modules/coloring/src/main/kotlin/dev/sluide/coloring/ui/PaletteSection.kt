package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import javafx.util.Subscription
import java.nio.file.Path

/**
 * The colors saved in the project; a click on one writes it into the slot being edited (ADR 0111). Colors are saved
 * in the inspector.
 */
internal class PaletteSection(
    private val state: ColoringState,
    private val savedColors: SavedColors,
    private val projectFolder: ObservableValue<Path?>,
) {
    private val swatches = FlowPane(6.0, 6.0).apply { styleClass += "coloring-saved-colors" }
    private val empty = Label("No saved colors yet").apply { styleClass += "coloring-muted" }
    private val error =
        Label("The saved colors of this project cannot be written; see the log.").apply {
            styleClass += "coloring-muted"
            isWrapText = true
        }
    private val shown = mutableListOf<Swatch>()
    private val changes: Subscription =
        savedColors.onChanged { changed ->
            if (changed == projectFolder.value?.toAbsolutePath()?.normalize()) refresh()
        }
    private val folderListener = ChangeListener<Path?> { _, _, _ -> refresh() }

    val node: Node =
        VBox(
            6.0,
            Label("Saved colors").apply { styleClass += "coloring-section-title" },
            swatches,
            empty,
            error,
        )

    init {
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
        swatches.children.setAll(
            colors.map { color ->
                Swatch(SimpleObjectProperty(color)) { state.editingColor.set(it) }.also { swatch ->
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
    }

    /** A refused change is shown; a successful one reloads through [SavedColors.onChanged]. */
    private fun attempt(done: Boolean) {
        error.isVisible = !done
        error.isManaged = !done
    }

    /** Releases the listeners on the project folder and on the saved colors, and the swatches. */
    fun dispose() {
        changes.unsubscribe()
        projectFolder.removeListener(folderListener)
        shown.forEach(Swatch::dispose)
        shown.clear()
    }
}
