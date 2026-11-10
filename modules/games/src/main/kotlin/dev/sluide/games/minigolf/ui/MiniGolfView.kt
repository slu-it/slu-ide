package dev.sluide.games.minigolf.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.ui.GameZoomControls
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.RealtimeLoop
import dev.sluide.games.common.ui.focusBoardOnPress
import dev.sluide.games.common.ui.keepDragsOn
import dev.sluide.games.minigolf.KeyAction
import dev.sluide.games.minigolf.MiniGolfModel
import dev.sluide.games.minigolf.geometry.Vec
import dev.sluide.games.minigolf.keyAction
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The Mini Golf tab: toolbar, the field with the pause overlay, and the status line. It only shows [model] and
 * forwards input; the view that shows a model owns its callbacks until [dispose].
 */
internal class MiniGolfView(
    private val model: MiniGolfModel,
    zoomPreferences: GameZoomPreferences,
) {
    val canvas = FieldCanvas(model)
    val newGameButton = Button("New Game")
    val pauseButton = Button()
    val status = Label().apply { styleClass += "mg-status" }

    /** Asks whether to abandon the round with strokes played; replaced in tests. */
    internal var confirmAbandon: () -> Boolean = ::askAbandon

    // A click on the overlay does nothing of its own: the board's mouse press calls model.pressed() before it takes
    // the focus. Otherwise the press would focus the board and the following click would resume on the first click.
    private val pauseOverlay = PauseOverlay {}

    /** The focusable field area: the canvas under the pause overlay. */
    val board: StackPane =
        StackPane(canvas, pauseOverlay.node).apply {
            styleClass += "mg-board"
            isFocusTraversable = true
            setMinSize(0.0, 0.0)
        }

    internal val overlay: Node get() = pauseOverlay.node

    internal val zoomControls =
        GameZoomControls(ZoomRange.UP_TO_FIT, zoomPreferences.zoom(GAME_ID, ZoomRange.UP_TO_FIT)) {
            zoomPreferences.setZoom(GAME_ID, it)
        }

    val node: Node =
        BorderPane().apply {
            styleClass += "mini-golf"
            stylesheets +=
                checkNotNull(MiniGolfView::class.java.getResource("/dev/sluide/games/minigolf/mini-golf.css"))
                    .toExternalForm()
            top = toolbar()
            center = board
            bottom = HBox(status).apply { styleClass += "mg-status-bar" }
        }

    private val focusBoard = FocusWhenShown()
    internal val loop = RealtimeLoop(board, model.session) { canvas.redraw() }

    private val installedChange: () -> Unit = ::refresh
    private val installedHoleInOne: (Vec) -> Unit = canvas::startBurst

    // Whether a click activating the window would resume (the overlay hint); only the owning view tells the model.
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
        board.setOnMouseDragged(::mouseDragged)
        board.setOnMouseReleased(::mouseReleased)
        keepDragsOn(board)
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        focusBoardOnPress(node, board)
        zoomControls.zoom.subscribe { zoom -> canvas.zoom = zoom.toDouble() }
        zoomControls.install(node)
        model.onChange = installedChange
        model.onHoleInOne = installedHoleInOne
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
            leading.setAll(newGameButton, pauseButton)
            trailing.setAll(zoomControls.node)
            styleClass += "mg-toolbar"
        }
    }

    private fun refresh() {
        pauseButton.text = model.pauseButtonText
        pauseButton.isDisable = !model.session.canPause
        pauseOverlay.show(model.session.state == SessionState.PAUSED, model.pauseHint)
        status.text = model.statusText
        loop.update()
        // A running loop draws every frame anyway.
        if (!loop.isRunning) canvas.redraw()
    }

    private fun mousePressed(event: MouseEvent) {
        when (event.button) {
            MouseButton.PRIMARY -> {
                // The model reads the focus as it was before this click, so the first click on an unfocused,
                // paused board only focuses it.
                val point = canvas.fieldPoint(event.x, event.y)
                model.pressed(point.x, point.y)
                board.requestFocus()
                event.consume()
            }

            MouseButton.SECONDARY -> {
                model.cancelAim()
                event.consume()
            }

            else -> {
                Unit
            }
        }
    }

    private fun mouseDragged(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        val point = canvas.fieldPoint(event.x, event.y)
        model.dragged(point.x, point.y)
    }

    private fun mouseReleased(event: MouseEvent) {
        if (event.button != MouseButton.PRIMARY) return
        val point = canvas.fieldPoint(event.x, event.y)
        model.released(point.x, point.y)
    }

    private fun newGame() {
        if (model.needsConfirmToAbandon && !confirmAbandon()) {
            board.requestFocus()
            return
        }
        model.newGame()
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
            // been taken over leaves the round to the new one.
            model.session.focusChanged(false)
            model.onChange = {}
            model.onHoleInOne = {}
        }
        loop.dispose()
        canvas.dispose()
    }

    private fun askAbandon(): Boolean {
        val confirm = ButtonType("New Game", ButtonData.OK_DONE)
        val alert =
            Alert(
                AlertType.CONFIRMATION,
                "The strokes of this round are lost; the best scores of finished holes are kept.",
                confirm,
                ButtonType.CANCEL,
            ).apply {
                title = "New Game"
                headerText = "Abandon this round?"
                node.scene?.window?.let { initOwner(it) }
            }
        (alert.dialogPane.lookupButton(confirm) as Button).isDefaultButton = false
        (alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = true
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm
    }

    private companion object {
        const val GAME_ID = "miniGolf"
        const val ICON_SIZE = 16
    }
}
