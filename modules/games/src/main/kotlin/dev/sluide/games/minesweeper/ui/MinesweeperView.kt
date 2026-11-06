package dev.sluide.games.minesweeper.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.TabVisibility
import dev.sluide.games.minesweeper.BoardSize
import dev.sluide.games.minesweeper.MinesweeperModel
import dev.sluide.games.minesweeper.Preset
import dev.sluide.games.minesweeper.SizeChoice
import dev.sluide.games.minesweeper.keyAction
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Window
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The Minesweeper tab: toolbar, board, status line and the pause overlay. It only shows [model] and forwards input;
 * the view that shows a model owns its [MinesweeperModel.onChange] until [dispose].
 */
internal class MinesweeperView(
    private val model: MinesweeperModel,
) {
    /** Asks for a custom board size; null on Cancel. Tests replace it. */
    internal var askCustomSize: (Window?, BoardSize) -> BoardSize? =
        { owner, initial -> CustomSizeDialog(owner, initial).showAndWait().orElse(null) }

    internal val board = BoardView(model)
    internal val sizeMenu = MenuButton()
    private val pauseButton = ToggleButton()
    private val pauseIcon = icon("mdi2p-pause")
    private val pauseTip = Tooltip("Pause (P)")
    private val counter = Label().apply { graphic = icon("mdi2b-bomb") }
    private val timer = Label().apply { graphic = icon("mdi2t-timer-outline") }
    private val status = Label().apply { styleClass += "ms-status" }
    private val pauseOverlay =
        PauseOverlay {
            if (model.isManuallyPaused) model.togglePause()
            board.requestFocus()
        }
    internal val overlay: Node get() = pauseOverlay.node
    private val ticker =
        Timeline(KeyFrame(Duration.millis(TICK_MILLIS), { refreshTimer() })).apply {
            cycleCount = Timeline.INDEFINITE
        }

    val node: Node =
        BorderPane().apply {
            styleClass += "minesweeper"
            stylesheets +=
                checkNotNull(MinesweeperView::class.java.getResource("/dev/sluide/games/minesweeper/minesweeper.css"))
                    .toExternalForm()
            top = toolbar()
            center =
                StackPane(
                    ScrollPane(board).apply {
                        styleClass += "ms-scroll"
                        isFitToWidth = true
                        isFitToHeight = true
                        isFocusTraversable = false
                    },
                    overlay,
                )
            bottom = HBox(status).apply { styleClass += "ms-status-bar" }
        }

    /** The board has no scene yet when the content is focused before its first layout (it is in a scroll pane). */
    private val focusBoard = FocusWhenShown()

    private val installed: () -> Unit = ::refresh
    private val visibility = TabVisibility(node) { model.hidden = it }

    init {
        board.setOnMousePressed(::mousePressed)
        board.setOnKeyPressed(::keyPressed)
        model.onChange = installed
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        refresh()
    }

    private fun toolbar(): Node {
        sizeMenu.apply {
            isFocusTraversable = false
            items.setAll(
                Preset.entries.map { preset ->
                    MenuItem(preset.label).apply { setOnAction { model.newGame(SizeChoice.OfPreset(preset)) } }
                } +
                    SeparatorMenuItem() +
                    MenuItem("Custom…").apply { setOnAction { chooseCustom() } },
            )
        }
        val newGame =
            Button(null, icon("mdi2r-refresh")).apply {
                isFocusTraversable = false
                tooltip = Tooltip("New Game (F2)")
                setOnAction {
                    model.newGame()
                    board.requestFocus()
                }
            }
        pauseButton.apply {
            isFocusTraversable = false
            graphic = pauseIcon
            tooltip = pauseTip
            setOnAction { model.togglePause() }
        }
        counter.contentDisplay = ContentDisplay.LEFT
        return WrappingToolbar().apply {
            hgap = 6.0
            leading.setAll(sizeMenu, newGame, pauseButton)
            trailing.setAll(counter, timer)
            styleClass += "ms-toolbar"
        }
    }

    private fun icon(literal: String) = FontIcon(literal).apply { iconSize = ICON_SIZE }

    private fun chooseCustom() {
        val size = askCustomSize(node.scene?.window, model.lastCustom) ?: return
        model.newGame(SizeChoice.Custom(size))
        board.requestFocus()
    }

    private fun mousePressed(event: MouseEvent) {
        board.requestFocus()
        val (x, y) = board.cellAt(event.x, event.y) ?: return
        when (event.button) {
            MouseButton.PRIMARY -> {
                model.reveal(x, y)
            }

            MouseButton.SECONDARY -> {
                model.toggleFlag(x, y)
            }

            MouseButton.MIDDLE -> {
                model.chord(x, y)
            }

            else -> {}
        }
    }

    private fun keyPressed(event: KeyEvent) {
        val action =
            keyAction(event.code, event.isShortcutDown || event.isAltDown || event.isControlDown || event.isMetaDown)
                ?: return
        model.perform(action)
        event.consume()
    }

    private fun refresh() {
        board.update()
        sizeMenu.text = model.sizeLabel
        counter.text = model.counterText
        status.text = model.statusText
        pauseButton.isDisable = !model.canPause
        pauseButton.isSelected = model.isManuallyPaused
        pauseIcon.iconLiteral = if (model.isManuallyPaused) "mdi2p-play" else "mdi2p-pause"
        pauseTip.text = if (model.isManuallyPaused) "Resume (P)" else "Pause (P)"
        pauseOverlay.show(model.isPaused, model.isManuallyPaused)
        refreshTimer()
        if (model.isTicking) ticker.play() else ticker.stop()
    }

    private fun refreshTimer() {
        timer.text = model.timerText
    }

    /** The model stops notifying this view (unless a newer view has taken it over) and the game pauses. */
    fun dispose() {
        focusBoard.cancel()
        ticker.stop()
        visibility.dispose()
        if (model.onChange === installed) {
            model.onChange = {}
            model.hidden = true
        }
    }

    private companion object {
        const val TICK_MILLIS = 200.0
        const val ICON_SIZE = 16
    }
}
