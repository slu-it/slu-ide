package dev.sluide.games.sudoku.ui

import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.sudoku.Level
import dev.sluide.games.sudoku.SudokuSize
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.javafx.FontIcon

/** What the toolbar's buttons do; the view implements it. */
internal class ToolbarActions(
    val newGame: () -> Unit,
    val restart: () -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val toggleNotes: () -> Unit,
    val fillNotes: () -> Unit,
    val hint: () -> Unit,
    val pause: () -> Unit,
    val chooseSize: (SudokuSize) -> Unit,
    val chooseLevel: (Level) -> Unit,
)

/** The Sudoku toolbar: the controls, which [SudokuView] updates; the clicks go to [actions]. */
internal class SudokuToolbar(
    actions: ToolbarActions,
) {
    val sizeMenu = MenuButton()
    val levelMenu = MenuButton()
    val newGameButton = button("mdi2r-refresh", "New Game (F2)", actions.newGame)
    val restartButton = button("mdi2r-restore", "Restart Puzzle", actions.restart)
    val undoButton = button("mdi2u-undo", "Undo (U)", actions.undo)
    val redoButton = button("mdi2r-redo", "Redo (Shift+U)", actions.redo)
    val notesButton = toggle("mdi2p-pencil-outline", "Notes (N)", actions.toggleNotes)
    val fillNotesButton = button("mdi2p-pencil-plus-outline", "Fill Notes", actions.fillNotes)
    val hintButton = button("mdi2l-lightbulb-on-outline", "Hint (H)", actions.hint)
    val pauseButton = toggle("mdi2p-pause", "Pause (P)", actions.pause)
    val timer = Label().apply { graphic = icon("mdi2t-timer-outline") }

    private val sizeGroup = ToggleGroup()
    val sizeItems =
        SudokuSize.entries.associateWith { size ->
            RadioMenuItem(size.label).apply {
                toggleGroup = sizeGroup
                setOnAction { actions.chooseSize(size) }
            }
        }
    private val levelGroup = ToggleGroup()
    val levelItems =
        Level.entries.associateWith { level ->
            RadioMenuItem(level.label).apply {
                toggleGroup = levelGroup
                setOnAction { actions.chooseLevel(level) }
            }
        }

    val node: WrappingToolbar =
        WrappingToolbar().apply {
            hgap = 6.0
            sizeMenu.apply {
                isFocusTraversable = false
                items.setAll(sizeItems.values)
            }
            levelMenu.apply {
                isFocusTraversable = false
                items.setAll(levelItems.values)
            }
            leading.setAll(
                sizeMenu,
                levelMenu,
                newGameButton,
                restartButton,
                undoButton,
                redoButton,
                notesButton,
                fillNotesButton,
                hintButton,
                pauseButton,
            )
            trailing.setAll(timer)
            styleClass += "sdk-toolbar"
        }

    /** Shows the pause button as pausing or resuming. */
    fun showPaused(paused: Boolean) {
        (pauseButton.graphic as FontIcon).iconLiteral = if (paused) "mdi2p-play" else "mdi2p-pause"
        pauseButton.tooltip.text = if (paused) "Resume (P)" else "Pause (P)"
    }

    private fun button(
        literal: String,
        tip: String,
        action: () -> Unit,
    ) = Button(null, icon(literal)).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { action() }
    }

    private fun toggle(
        literal: String,
        tip: String,
        action: () -> Unit,
    ) = ToggleButton(null, icon(literal)).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { action() }
    }

    private fun icon(literal: String) = FontIcon(literal).apply { iconSize = ICON_SIZE }

    private companion object {
        const val ICON_SIZE = 16
    }
}
