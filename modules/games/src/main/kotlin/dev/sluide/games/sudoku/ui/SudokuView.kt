package dev.sluide.games.sudoku.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.games.common.formatTime
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.TabVisibility
import dev.sluide.games.sudoku.BoardLayout
import dev.sluide.games.sudoku.Flash
import dev.sluide.games.sudoku.KeyAction
import dev.sluide.games.sudoku.Level
import dev.sluide.games.sudoku.MIN_CELL
import dev.sluide.games.sudoku.PAD_GAP_FACTOR
import dev.sluide.games.sudoku.SudokuModel
import dev.sluide.games.sudoku.SudokuSize
import dev.sluide.games.sudoku.boardLayout
import dev.sluide.games.sudoku.keyAction
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Bounds
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration

/**
 * The Sudoku tab: toolbar, board with the number pad, status line and the pause overlay. It only shows [model] and
 * forwards input; the view that shows a model owns its callbacks until [dispose].
 */
internal class SudokuView(
    private val model: SudokuModel,
) {
    /** Asks a question with a [verb] button and Cancel (the default); true for the verb. Tests replace it. */
    internal var confirm: (title: String, header: String, verb: String, content: String) -> Boolean = ::askConfirm

    internal val board = BoardView()
    internal val pad = PadView()
    private val toolbar =
        SudokuToolbar(
            ToolbarActions(
                newGame = ::newGame,
                restart = ::restart,
                undo = { act(model::undo) },
                redo = { act(model::redo) },
                toggleNotes = { act(model::toggleNotesMode) },
                fillNotes = { act(model::fillNotes) },
                hint = { act(model::hint) },
                pause = { act(model::togglePause) },
                chooseSize = ::chooseSize,
                chooseLevel = ::chooseLevel,
            ),
        )
    internal val sizeMenu get() = toolbar.sizeMenu
    internal val levelMenu get() = toolbar.levelMenu
    internal val undoButton get() = toolbar.undoButton
    internal val redoButton get() = toolbar.redoButton
    internal val restartButton get() = toolbar.restartButton
    internal val newGameButton get() = toolbar.newGameButton
    internal val notesButton get() = toolbar.notesButton
    internal val fillNotesButton get() = toolbar.fillNotesButton
    internal val hintButton get() = toolbar.hintButton
    internal val pauseButton get() = toolbar.pauseButton
    internal val timer get() = toolbar.timer
    internal val status = Label().apply { styleClass += "sdk-status" }
    private val generating =
        Label("Generating…").apply {
            styleClass += "sdk-generating"
            isMouseTransparent = true
            isVisible = false
        }
    private val pauseOverlay =
        PauseOverlay {
            if (model.isManuallyPaused) model.togglePause()
            board.requestFocus()
        }
    internal val overlay: Node get() = pauseOverlay.node
    private val boardArea =
        StackPane(board, generating, pauseOverlay.node).apply {
            // The board alone sets the size: the overlay's hint is wider than a small board.
            pauseOverlay.node.apply {
                minWidth = 0.0
                prefWidth = 0.0
                minHeight = 0.0
                prefHeight = 0.0
            }
        }
    private val besideBox = HBox().apply { alignment = Pos.CENTER }
    private val belowBox = VBox().apply { alignment = Pos.CENTER }
    private val content =
        VBox().apply {
            alignment = Pos.CENTER
            isFillWidth = false
            padding = Insets(PADDING)
        }
    private val ticker =
        Timeline(KeyFrame(Duration.millis(TICK_MILLIS), { refreshTimer() })).apply {
            cycleCount = Timeline.INDEFINITE
        }

    /** The flash duration of the board; tests set it to zero. */
    internal var flashDuration: Duration
        get() = board.flashDuration
        set(value) {
            board.flashDuration = value
        }

    /** True while the pad is beside the board, false below it. */
    internal var padBeside: Boolean = true
        private set
    private var arranged: Boolean? = null
    private var bounds: Bounds? = null
    private var disposed = false

    val node: Node =
        BorderPane().apply {
            styleClass += "sudoku"
            stylesheets +=
                checkNotNull(SudokuView::class.java.getResource("/dev/sluide/games/sudoku/sudoku.css")).toExternalForm()
            top = toolbar.node
            center =
                ScrollPane(content).apply {
                    styleClass += "sdk-scroll"
                    isFitToWidth = true
                    isFitToHeight = true
                    isFocusTraversable = false
                    viewportBoundsProperty().addListener { _, _, b ->
                        bounds = b
                        refresh()
                    }
                }
            bottom = HBox(status).apply { styleClass += "sdk-status-bar" }
        }

    /** The board has no scene yet when the content is focused before its first layout (it is in a scroll pane). */
    private val focusBoard = FocusWhenShown()

    private val installed: () -> Unit = ::refresh
    private val installedFlash: (Flash) -> Unit = { board.flash(it) }
    private val visibility = TabVisibility(node) { model.hidden = it }

    init {
        board.setOnKeyPressed(::keyPressed)
        board.onCellPressed = {
            model.selected = it
            board.requestFocus()
        }
        // Consumed, or the scroll pane around the board takes the focus afterwards.
        board.setOnMousePressed {
            board.requestFocus()
            it.consume()
        }
        pad.onDigit = { digit -> act { model.input(digit) } }
        pad.onErase = { act(model::erase) }
        pad.onNotes = { act(model::toggleNotesMode) }
        model.onChange = installed
        model.onFlash = installedFlash
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        model.ensureGame()
        refresh()
    }

    private fun act(action: () -> Unit) {
        action()
        board.requestFocus()
    }

    private fun guard(
        title: String,
        verb: String = title,
    ): Boolean =
        !model.needsConfirmToAbandon ||
            confirm(title, "Abandon this puzzle?", verb, "Its time of ${formatTime(model.elapsedMillis)} is lost.")

    private fun newGame() {
        if (guard("New Game")) model.newGame()
        board.requestFocus()
    }

    private fun restart() {
        val clear =
            model.game?.hasEntries != true ||
                confirm(
                    "Restart",
                    "Clear all entries?",
                    "Restart",
                    "The puzzle starts over from its givens; the clock keeps running.",
                )
        if (clear) model.restart()
        board.requestFocus()
    }

    private fun chooseSize(size: SudokuSize) {
        if (size != model.size && !guard("Change Size")) {
            refresh()
        } else {
            model.chooseSize(size)
        }
        board.requestFocus()
    }

    private fun chooseLevel(level: Level) {
        if (level != model.level && !guard("Change Level")) {
            refresh()
        } else {
            model.chooseLevel(level)
        }
        board.requestFocus()
    }

    private fun keyPressed(event: KeyEvent) {
        val other =
            event.isAltDown ||
                (event.isControlDown && !event.isShortcutDown) ||
                (event.isMetaDown && !event.isShortcutDown)
        val action = keyAction(event.code, event.isShiftDown, event.isShortcutDown, other) ?: return
        when (action) {
            is KeyAction.Move -> model.moveSelection(action.dRow, action.dCol)
            is KeyAction.Digit -> model.input(action.digit)
            is KeyAction.Note -> model.toggleNoteAt(action.digit)
            KeyAction.Erase -> model.erase()
            KeyAction.ToggleNotes -> model.toggleNotesMode()
            KeyAction.Hint -> model.hint()
            KeyAction.Pause -> model.togglePause()
            KeyAction.NewGame -> newGame()
            KeyAction.Undo -> model.undo()
            KeyAction.Redo -> model.redo()
        }
        event.consume()
    }

    private fun refresh() {
        if (disposed) return
        val layout = layoutBoard()
        val game = model.game
        val playable = game != null && !game.isSolved
        val acting = playable && !model.isPaused
        board.show(game, model.size, model.selected, layout.cell)
        pad.show(game, model.size, model.notesMode, layout.cell)
        pad.isDisable = !acting
        generating.isVisible = model.isGenerating
        pauseOverlay.show(model.isPaused, model.isManuallyPaused)
        status.text = model.statusText
        toolbar.sizeMenu.text = model.size.label
        toolbar.sizeItems.getValue(model.size).isSelected = true
        toolbar.levelMenu.text = model.level.label
        toolbar.levelItems.getValue(model.level).isSelected = true
        toolbar.restartButton.isDisable = !acting
        toolbar.undoButton.isDisable = !acting || game?.canUndo != true
        toolbar.redoButton.isDisable = !acting || game?.canRedo != true
        toolbar.notesButton.isSelected = model.notesMode
        toolbar.notesButton.isDisable = !acting
        toolbar.fillNotesButton.isDisable = !acting
        toolbar.hintButton.isDisable = !acting
        toolbar.pauseButton.isDisable = !model.canPause
        toolbar.pauseButton.isSelected = model.isManuallyPaused
        toolbar.showPaused(model.isManuallyPaused)
        refreshTimer()
        if (playable && !model.isPaused) ticker.play() else ticker.stop()
    }

    private fun refreshTimer() {
        toolbar.timer.text = formatTime(model.elapsedMillis)
    }

    /** Sizes the cells for the viewport and puts the pad beside or below the board. */
    private fun layoutBoard(): BoardLayout {
        val b = bounds
        val layout =
            if (b == null) {
                BoardLayout(MIN_CELL, true)
            } else {
                boardLayout(b.width - 2 * PADDING - SLACK, b.height - 2 * PADDING - SLACK, model.size)
            }
        padBeside = layout.padBeside
        val box: Pane = if (layout.padBeside) besideBox else belowBox
        if (arranged != layout.padBeside) {
            arranged = layout.padBeside
            besideBox.children.clear()
            belowBox.children.clear()
            box.children.setAll(boardArea, pad)
            content.children.setAll(box)
        }
        val gap = layout.cell * PAD_GAP_FACTOR
        besideBox.spacing = gap
        belowBox.spacing = gap
        return layout
    }

    /** The model stops notifying this view (unless a newer view has taken it over) and the game pauses. */
    fun dispose() {
        disposed = true
        focusBoard.cancel()
        ticker.stop()
        board.stopFlashes()
        visibility.dispose()
        if (model.onFlash === installedFlash) model.onFlash = {}
        if (model.onChange === installed) {
            model.onChange = {}
            model.hidden = true
        }
    }

    private fun askConfirm(
        title: String,
        header: String,
        verb: String,
        content: String,
    ): Boolean {
        val confirm = ButtonType(verb, ButtonData.OK_DONE)
        val alert =
            Alert(AlertType.CONFIRMATION, content, confirm, ButtonType.CANCEL).apply {
                this.title = title
                headerText = header
                node.scene?.window?.let { initOwner(it) }
            }
        (alert.dialogPane.lookupButton(confirm) as Button).isDefaultButton = false
        (alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = true
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm
    }

    private companion object {
        const val TICK_MILLIS = 200.0
        const val PADDING = 8.0

        /** The board's outer border and a pixel of rounding. */
        const val SLACK = 6.0
    }
}
