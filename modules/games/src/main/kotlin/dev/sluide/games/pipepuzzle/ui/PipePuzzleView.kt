package dev.sluide.games.pipepuzzle.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.games.common.formatTime
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.TabVisibility
import dev.sluide.games.common.ui.focusBoardOnPress
import dev.sluide.games.pipepuzzle.BoardSize
import dev.sluide.games.pipepuzzle.KeyAction
import dev.sluide.games.pipepuzzle.Mode
import dev.sluide.games.pipepuzzle.PipePuzzleModel
import dev.sluide.games.pipepuzzle.Pos
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.util.Duration

/**
 * The Pipe Puzzle tab: toolbar, board, status line and the pause overlay. It only shows [model] and forwards input;
 * the view that shows a model owns its callbacks until [dispose].
 */
internal class PipePuzzleView(
    private val model: PipePuzzleModel,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** Asks a question with a [verb] button and Cancel (the default); true for the verb. Tests replace it. */
    internal var confirm: (title: String, header: String, verb: String, content: String) -> Boolean = ::askConfirm

    internal val board = BoardCanvas(model, ::keyAction, clock)
    private val toolbar =
        PipePuzzleToolbar(
            ToolbarActions(
                newGame = ::newGame,
                restart = ::restart,
                hint = { act(model::hint) },
                pause = { act(model::togglePause) },
                chooseMode = ::chooseMode,
                chooseSize = ::chooseSize,
                chooseLook = { look -> act { model.look = look } },
            ),
        )
    internal val modeMenu get() = toolbar.modeMenu
    internal val sizeMenu get() = toolbar.sizeMenu
    internal val lookMenu get() = toolbar.lookMenu
    internal val sizeItems get() = toolbar.sizeItems
    internal val lookItems get() = toolbar.lookItems
    internal val modeItems get() = toolbar.modeItems
    internal val newGameButton get() = toolbar.newGameButton
    internal val restartButton get() = toolbar.restartButton
    internal val hintButton get() = toolbar.hintButton
    internal val pauseButton get() = toolbar.pauseButton
    internal val timer get() = toolbar.timer
    internal val status = Label().apply { styleClass += "pp-status" }
    private val generating =
        Label("Generating…").apply {
            styleClass += "pp-generating"
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
    private val ticker =
        Timeline(KeyFrame(Duration.millis(TICK_MILLIS), { refreshTimer() })).apply {
            cycleCount = Timeline.INDEFINITE
        }
    private var disposed = false

    val node: Node =
        BorderPane().apply {
            styleClass += "pipe-puzzle"
            stylesheets +=
                checkNotNull(PipePuzzleView::class.java.getResource("/dev/sluide/games/pipepuzzle/pipe-puzzle.css"))
                    .toExternalForm()
            top = toolbar.node
            center = boardArea
            bottom = HBox(status).apply { styleClass += "pp-status-bar" }
        }

    private val focusBoard = FocusWhenShown()

    private val installed: () -> Unit = ::refresh
    private val installedTurned: (Pos, Boolean) -> Unit = { pos, clockwise -> board.turned(pos, clockwise) }
    private val installedSolved: () -> Unit = { board.solved() }
    private val visibility = TabVisibility(node) { model.hidden = it }

    init {
        model.onChange = installed
        model.onTurned = installedTurned
        model.onSolved = installedSolved
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        focusBoardOnPress(node, board)
        model.ensureGame()
        refresh()
    }

    private fun act(action: () -> Unit) {
        action()
        board.requestFocus()
    }

    private fun guard(title: String): Boolean =
        !model.needsConfirmToAbandon ||
            confirm(title, "Abandon this puzzle?", title, "Its time of ${formatTime(model.elapsedMillis)} is lost.")

    private fun newGame() {
        if (guard("New Game")) model.newGame()
        board.requestFocus()
    }

    private fun restart() {
        // Where the button is off, the key does nothing either, and asks nothing.
        if (model.game == null || model.isPaused) return
        val go =
            !model.needsConfirmToRestart ||
                confirm(
                    "Restart",
                    "Restart this puzzle?",
                    "Restart",
                    "The tiles turn back to how they started and the clock begins again at 0:00.",
                )
        if (go) model.restart()
        board.requestFocus()
    }

    private fun chooseMode(mode: Mode) {
        if (mode != model.mode && !guard("Change Mode")) refresh() else model.chooseMode(mode)
        board.requestFocus()
    }

    private fun chooseSize(size: BoardSize) {
        if (size != model.size && !guard("Change Size")) refresh() else model.chooseSize(size)
        board.requestFocus()
    }

    private fun keyAction(action: KeyAction) {
        when (action) {
            KeyAction.Restart -> restart()
            KeyAction.NewGame -> newGame()
            else -> model.handle(action)
        }
    }

    private fun refresh() {
        if (disposed) return
        val game = model.game
        val playable = game != null && !game.isSolved
        val acting = playable && !model.isPaused
        // Animations end at once when the board is covered or the tab is hidden.
        if (model.isPaused || model.hidden) board.finishAnimations()
        board.redraw()
        generating.isVisible = model.isGenerating
        pauseOverlay.show(model.isPaused, model.isManuallyPaused)
        status.text = model.statusText
        toolbar.modeMenu.text = model.mode.label
        toolbar.modeItems.getValue(model.mode).isSelected = true
        toolbar.sizeMenu.text = model.size.label
        toolbar.sizeItems.getValue(model.size).isSelected = true
        toolbar.lookMenu.text = model.look.label
        toolbar.lookItems.getValue(model.look).isSelected = true
        // Restart stays available after a win: replaying a known puzzle for a better time.
        toolbar.restartButton.isDisable = game == null || model.isPaused
        toolbar.hintButton.isDisable = !acting
        toolbar.pauseButton.isDisable = !model.canPause
        toolbar.pauseButton.isSelected = model.isManuallyPaused
        toolbar.showPaused(model.isManuallyPaused)
        refreshTimer()
        if (acting) ticker.play() else ticker.stop()
    }

    private fun refreshTimer() {
        toolbar.timer.text = formatTime(model.elapsedMillis)
    }

    /** The model stops notifying this view (unless a newer view has taken it over) and the game pauses. */
    fun dispose() {
        disposed = true
        focusBoard.cancel()
        ticker.stop()
        board.dispose()
        visibility.dispose()
        if (model.onTurned === installedTurned) model.onTurned = { _, _ -> }
        if (model.onSolved === installedSolved) model.onSolved = {}
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
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
