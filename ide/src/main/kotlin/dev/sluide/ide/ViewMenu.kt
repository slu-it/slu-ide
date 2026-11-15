package dev.sluide.ide

import dev.sluide.editorarea.EditorArea
import javafx.beans.value.ObservableValue
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** Reveals the selected tab's file in the explorer (ADR 0095); Alt is Option on macOS. */
internal val SELECT_OPENED_KEY = KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN)

/**
 * The View menu: Show Changes (the selected file's editor or its diff against HEAD, ADR 0021), Select Opened File
 * ([selectOpenedFile], enabled while [canSelectOpened]) and the [panels]' items.
 */
internal fun viewMenu(
    editorArea: EditorArea,
    canSelectOpened: ObservableValue<Boolean>,
    selectOpenedFile: () -> Unit,
    panels: List<MenuItem>,
): Menu {
    val showChanges =
        MenuItem("Show Changes").apply {
            accelerator = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
            disableProperty().bind(editorArea.canToggleDiff.map { !it })
            setOnAction { editorArea.toggleDiff() }
        }
    val selectOpened =
        MenuItem("Select Opened File").apply {
            accelerator = SELECT_OPENED_KEY
            disableProperty().bind(canSelectOpened.map { !it })
            setOnAction { selectOpenedFile() }
        }
    return Menu("View", null, showChanges, selectOpened, *panels.toTypedArray())
}
