package dev.sluide.games.blockbreaker.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.blockbreaker.BlockBreakerModel
import dev.sluide.games.blockbreaker.Difficulty
import dev.sluide.games.blockbreaker.KeyAction
import dev.sluide.games.blockbreaker.keyAction
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.RealtimeLoop
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import java.util.Locale

/**
 * The Block Breaker tab: toolbar, the field with the pause overlay, and the status line. It only shows [model] and
 * forwards input; the view that shows a model owns its callback until [dispose].
 */
internal class BlockBreakerView(
    private val model: BlockBreakerModel,
) {
    val canvas = FieldCanvas(model)
    val newGameButton = Button("New Game")
    val difficultyMenu = MenuButton()
    val pauseButton = Button()
    val status = Label().apply { styleClass += "bb-status" }

    /** Asks whether to abandon the running game of [score]; replaced in tests. */
    internal var confirmAbandon: (title: String, verb: String, score: Int) -> Boolean = ::askAbandon

    // A click on the overlay does nothing of its own: the board's mouse press calls model.clicked() before it takes
    // the focus. Otherwise the press would focus the board and the following click would resume on the first click.
    private val pauseOverlay = PauseOverlay {}

    /** The focusable field area: the canvas under the pause overlay. */
    val board: StackPane =
        StackPane(canvas, pauseOverlay.node).apply {
            styleClass += "bb-board"
            isFocusTraversable = true
            setMinSize(0.0, 0.0)
        }

    internal val overlay: Node get() = pauseOverlay.node

    private val difficultyGroup = ToggleGroup()
    private val difficultyItems =
        Difficulty.entries.associateWith { difficulty ->
            RadioMenuItem(difficulty.label).apply {
                toggleGroup = difficultyGroup
                setOnAction { chooseDifficulty(difficulty) }
            }
        }

    val node: Node =
        BorderPane().apply {
            styleClass += "block-breaker"
            stylesheets +=
                checkNotNull(
                    BlockBreakerView::class.java.getResource("/dev/sluide/games/blockbreaker/block-breaker.css"),
                ).toExternalForm()
            top = toolbar()
            center = board
            bottom = HBox(status).apply { styleClass += "bb-status-bar" }
        }

    private val focusBoard = FocusWhenShown()
    internal val loop = RealtimeLoop(board, model.session) { canvas.redraw() }

    private val installedChange: () -> Unit = ::refresh

    // The hint of the overlay depends on whether the window is active; only the view that owns the model forwards it.
    private val windowSubscription: Subscription =
        board
            .sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap<Boolean> { it.focusedProperty() }
            .subscribe { active ->
                if (model.onChange === installedChange) model.windowFocusChanged(windowCountsAsActive(active))
            }

    init {
        canvas.widthProperty().bind(board.widthProperty())
        canvas.heightProperty().bind(board.heightProperty())
        canvas.isManaged = false
        board.setOnKeyPressed(::keyPressed)
        board.setOnKeyReleased(::keyReleased)
        board.setOnMousePressed(::mousePressed)
        board.setOnMouseMoved(::pointerMoved)
        board.setOnMouseDragged(::pointerMoved)
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        model.onChange = installedChange
        // The subscription above ran before the view owned the model.
        model.windowFocusChanged(windowCountsAsActive(board.scene?.window?.isFocused))
        refresh()
    }

    /**
     * An inactive window gives the focus back to its focus owner when it is activated, so the activating click
     * resumes only when that owner is the board; otherwise the window counts as active (the click only focuses).
     */
    private fun windowCountsAsActive(windowFocused: Boolean?): Boolean =
        (windowFocused ?: true) || board.scene?.focusOwner !== board

    private fun toolbar(): Node {
        newGameButton.apply {
            graphic = FontIcon("mdi2r-refresh").apply { iconSize = ICON_SIZE }
            isFocusTraversable = false
            tooltip = Tooltip("New Game (F2)")
            setOnAction { newGame() }
        }
        difficultyMenu.apply {
            isFocusTraversable = false
            items.setAll(difficultyItems.values)
        }
        pauseButton.apply {
            isFocusTraversable = false
            tooltip = Tooltip("Pause or resume (P)")
            setOnAction {
                board.requestFocus()
                model.togglePause()
            }
        }
        return WrappingToolbar().apply {
            hgap = 6.0
            leading.setAll(newGameButton, difficultyMenu, pauseButton)
            styleClass += "bb-toolbar"
        }
    }

    private fun refresh() {
        difficultyMenu.text = model.difficulty.label
        difficultyItems.getValue(model.difficulty).isSelected = true
        pauseButton.text = model.pauseButtonText
        pauseButton.isDisable = !model.session.canPause
        pauseOverlay.show(model.session.state == SessionState.PAUSED, model.pauseHint)
        status.text = model.statusText
        board.cursor = if (model.hidesPointer) Cursor.NONE else Cursor.DEFAULT
        loop.update()
        // A running loop draws every frame anyway.
        if (!loop.isRunning) canvas.redraw()
    }

    private fun mousePressed(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        // The model reads the focus as it was before this click, so the first click on an unfocused, paused board
        // only focuses it.
        model.clicked()
        board.requestFocus()
        event.consume()
    }

    private fun pointerMoved(event: MouseEvent) = model.pointerMoved(canvas.fieldX(event.x))

    private fun newGame() {
        if (model.needsConfirmToAbandon && !confirmAbandon("New Game", "New Game", model.game.score)) {
            board.requestFocus()
            return
        }
        model.newGame()
        board.requestFocus()
    }

    private fun chooseDifficulty(difficulty: Difficulty) {
        if (difficulty != model.difficulty) {
            val cancelled =
                model.needsConfirmToAbandon &&
                    !confirmAbandon("Change Difficulty", "Change Difficulty", model.game.score)
            if (cancelled) {
                difficultyItems.getValue(model.difficulty).isSelected = true
            } else {
                model.chooseDifficulty(difficulty)
            }
        }
        board.requestFocus()
    }

    private fun keyPressed(event: KeyEvent) {
        val action =
            keyAction(event.code, event.isShortcutDown || event.isAltDown || event.isControlDown || event.isMetaDown)
                ?: return
        if (action == KeyAction.NEW_GAME) newGame() else model.keyPressed(action)
        event.consume()
    }

    private fun keyReleased(event: KeyEvent) {
        val action = keyAction(event.code, false) ?: return
        model.keyReleased(action)
        event.consume()
    }

    /** The model stops notifying this view (unless a newer view has taken it over). */
    fun dispose() {
        focusBoard.cancel()
        windowSubscription.unsubscribe()
        if (model.onChange === installedChange) {
            // A closing tab pauses whatever order the editor area removes and disposes it in; a view that has
            // been taken over leaves the game to the new one.
            model.session.focusChanged(false)
            model.onChange = {}
        }
        loop.dispose()
        canvas.dispose()
    }

    private fun askAbandon(
        title: String,
        verb: String,
        score: Int,
    ): Boolean {
        val confirm = ButtonType(verb, ButtonData.OK_DONE)
        val alert =
            Alert(
                AlertType.CONFIRMATION,
                "Its score of ${String.format(Locale.ROOT, "%,d", score)} is lost; the best score is kept.",
                confirm,
                ButtonType.CANCEL,
            ).apply {
                this.title = title
                headerText = "Abandon this game?"
                node.scene?.window?.let { initOwner(it) }
            }
        (alert.dialogPane.lookupButton(confirm) as Button).isDefaultButton = false
        (alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = true
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm
    }

    private companion object {
        const val ICON_SIZE = 16
    }
}
