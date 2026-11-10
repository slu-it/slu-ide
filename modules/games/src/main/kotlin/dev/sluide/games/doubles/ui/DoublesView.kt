package dev.sluide.games.doubles.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.ui.FitArea
import dev.sluide.games.common.ui.GameZoomControls
import dev.sluide.games.common.ui.focusBoardOnPress
import dev.sluide.games.doubles.BoardSize
import dev.sluide.games.doubles.Direction
import dev.sluide.games.doubles.DoublesModel
import dev.sluide.games.doubles.KeyAction
import dev.sluide.games.doubles.MoveResult
import dev.sluide.games.doubles.SCORE_ROW_FACTOR
import dev.sluide.games.doubles.SECTION_GAP_FACTOR
import dev.sluide.games.doubles.dragDirection
import dev.sluide.games.doubles.keyAction
import dev.sluide.games.doubles.tileSize
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.SwipeEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import java.util.Locale
import kotlin.math.max

/**
 * The Doubles tab: toolbar, score boxes, board and status line. It only shows [model] and forwards input; the view
 * that shows a model owns its callbacks until [dispose]. The zoom (ADR 0155) is read from and saved to
 * [zoomPreferences].
 */
internal class DoublesView(
    private val model: DoublesModel,
    zoomPreferences: GameZoomPreferences,
) {
    val board = BoardView()
    val sizeMenu = MenuButton()
    val newGameButton = Button()
    val undoButton = Button()
    val scoreValue = Label().apply { styleClass += "dbl-score-value" }
    val bestValue = Label().apply { styleClass += "dbl-score-value" }
    val zoomControls =
        GameZoomControls(ZoomRange.ABOVE_FIT, zoomPreferences.zoom(GAME_ID, ZoomRange.ABOVE_FIT)) {
            zoomPreferences.setZoom(GAME_ID, it)
        }
    val status = Label().apply { styleClass += "dbl-status" }

    /** Asks whether to abandon the running game of [score]; replaced in tests. */
    internal var confirmAbandon: (title: String, verb: String, score: Int) -> Boolean = ::askAbandon

    private val scoreCaptions = listOf(Label("Score"), Label("Best")).onEach { it.styleClass += "dbl-score-caption" }
    private val scoreBoxes =
        listOf(scoreCaptions[0] to scoreValue, scoreCaptions[1] to bestValue).map { (caption, value) ->
            VBox(caption, value).apply { styleClass += "dbl-score-box" }
        }
    private val scoreRow = HBox(scoreBoxes[0], scoreBoxes[1]).apply { alignment = Pos.CENTER }
    private val content =
        VBox(scoreRow, board).apply {
            alignment = Pos.CENTER
            isFillWidth = false
        }

    private val sizeGroup = ToggleGroup()
    private val sizeItems =
        BoardSize.entries.associateWith { size ->
            RadioMenuItem(size.label).apply {
                toggleGroup = sizeGroup
                setOnAction { chooseSize(size) }
            }
        }

    internal val scrollPane = ScrollPane()

    /** The Fit size comes from the scroll pane's inside; the zoomed tiles are the content's minimum size. */
    private val fitArea = FitArea(scrollPane) { resize() }
    private var zoom = 1.0

    val node: Node =
        BorderPane().apply {
            styleClass += "doubles"
            stylesheets +=
                checkNotNull(DoublesView::class.java.getResource("/dev/sluide/games/doubles/doubles.css"))
                    .toExternalForm()
            top = toolbar()
            center =
                scrollPane.apply {
                    this.content = this@DoublesView.content
                    styleClass += "dbl-scroll"
                    isFitToWidth = true
                    isFitToHeight = true
                    isFocusTraversable = false
                }
            bottom = HBox(status).apply { styleClass += "dbl-status-bar" }
        }

    /** The board has no scene yet when the content is focused before its first layout (it is in a scroll pane). */
    private val focusBoard = FocusWhenShown()

    private val installedMoved: (MoveResult) -> Unit = { result ->
        board.animate(result, model.tiles, model.size.side)
        refreshLabels()
    }
    private val installedChange: () -> Unit = {
        board.finishAnimation()
        layoutBoard()
        board.show(model.tiles, model.size.side)
        refreshLabels()
    }

    private var pressX = 0.0
    private var pressY = 0.0

    init {
        board.setOnKeyPressed(::keyPressed)
        board.setOnSwipeLeft { swipe(Direction.LEFT, it) }
        board.setOnSwipeRight { swipe(Direction.RIGHT, it) }
        board.setOnSwipeUp { swipe(Direction.UP, it) }
        board.setOnSwipeDown { swipe(Direction.DOWN, it) }
        board.setOnMouseReleased(::mouseReleased)
        model.onMoved = installedMoved
        model.onChange = installedChange
        // The editor area focuses the tab's content; the keys belong to the board.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusBoard.request(board) }
        focusBoardOnPress(node, board)
        // Consumed, or the scroll pane around the board takes the focus afterwards.
        board.setOnMousePressed {
            pressX = it.sceneX
            pressY = it.sceneY
            board.requestFocus()
            it.consume()
        }
        zoomControls.zoom.subscribe { value ->
            zoom = value.toDouble()
            resize()
        }
        zoomControls.install(node)
        refreshLabels()
    }

    private fun toolbar(): Node {
        sizeMenu.apply {
            isFocusTraversable = false
            items.setAll(sizeItems.values)
        }
        newGameButton.apply {
            graphic = FontIcon("mdi2r-refresh").apply { iconSize = ICON_SIZE }
            isFocusTraversable = false
            tooltip = Tooltip("New Game (F2)")
            setOnAction { newGame() }
        }
        undoButton.apply {
            graphic = FontIcon("mdi2u-undo").apply { iconSize = ICON_SIZE }
            isFocusTraversable = false
            tooltip = Tooltip("Undo (U)")
            setOnAction { undo() }
        }
        return WrappingToolbar().apply {
            hgap = 6.0
            leading.setAll(sizeMenu, newGameButton, undoButton)
            trailing.setAll(zoomControls.node)
            styleClass += "dbl-toolbar"
        }
    }

    private fun move(direction: Direction) {
        board.finishAnimation()
        model.move(direction)
    }

    private fun undo() {
        board.finishAnimation()
        model.undo()
        board.requestFocus()
    }

    private fun newGame() {
        board.finishAnimation()
        if (model.needsConfirmToAbandon && !confirmAbandon("New Game", "New Game", model.score)) {
            board.requestFocus()
            return
        }
        model.newGame()
        board.requestFocus()
    }

    private fun chooseSize(size: BoardSize) {
        board.finishAnimation()
        if (size != model.size) {
            if (model.needsConfirmToAbandon && !confirmAbandon("Change Size", "Change Size", model.score)) {
                sizeItems.getValue(model.size).isSelected = true
            } else {
                model.chooseSize(size)
            }
        }
        board.requestFocus()
    }

    private fun keyPressed(event: KeyEvent) {
        val action =
            keyAction(event.code, event.isShortcutDown || event.isAltDown || event.isControlDown || event.isMetaDown)
                ?: return
        when (action) {
            is KeyAction.Move -> move(action.direction)
            KeyAction.Undo -> undo()
            KeyAction.NewGame -> newGame()
        }
        event.consume()
    }

    private fun swipe(
        direction: Direction,
        event: SwipeEvent,
    ) {
        move(direction)
        event.consume()
    }

    private fun mouseReleased(event: MouseEvent) {
        val tile = currentTile()
        val direction =
            dragDirection(event.sceneX - pressX, event.sceneY - pressY, max(DRAG_MIN, tile / 3)) ?: return
        move(direction)
        event.consume()
    }

    private fun currentTile(): Double =
        tileSize(fitArea.innerWidth - 2 * PADDING, fitArea.innerHeight - 2 * PADDING, model.size.side, zoom)

    private fun resize() {
        board.finishAnimation()
        layoutBoard()
        board.show(model.tiles, model.size.side)
    }

    /** Applies the tile size for the current bounds and size to the board and the score row. */
    private fun layoutBoard() {
        val tile = currentTile()
        content.spacing = tile * SECTION_GAP_FACTOR
        content.padding = Insets(PADDING)
        board.applyTile(tile, model.size.side)
        scoreRow.spacing = tile * SCORE_SPACING_FACTOR
        scoreBoxes.forEach {
            it.setMinSize(tile * SCORE_WIDTH_FACTOR, tile * SCORE_ROW_FACTOR)
            it.setPrefSize(tile * SCORE_WIDTH_FACTOR, tile * SCORE_ROW_FACTOR)
            it.setMaxSize(tile * SCORE_WIDTH_FACTOR, tile * SCORE_ROW_FACTOR)
        }
        scoreCaptions.forEach { it.style = "-fx-font-size: ${max(MIN_FONT, tile * CAPTION_FACTOR)}px" }
        listOf(scoreValue, bestValue).forEach { it.style = "-fx-font-size: ${max(MIN_FONT, tile * VALUE_FACTOR)}px" }
    }

    private fun refreshLabels() {
        scoreValue.text = format(model.score)
        bestValue.text = format(model.best)
        status.text = model.statusText
        sizeMenu.text = model.size.label
        sizeItems.getValue(model.size).isSelected = true
        undoButton.isDisable = !model.canUndo
    }

    /** The model stops notifying this view (unless a newer view has taken it over). */
    fun dispose() {
        focusBoard.cancel()
        board.finishAnimation()
        if (model.onMoved === installedMoved) model.onMoved = {}
        if (model.onChange === installedChange) model.onChange = {}
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
                "Its score of ${format(score)} is lost; the best score is kept.",
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

    private fun format(n: Int) = "%,d".format(Locale.ROOT, n)

    private companion object {
        const val GAME_ID = "doubles"
        const val ICON_SIZE = 16
        const val PADDING = 8.0
        const val DRAG_MIN = 16.0
        const val MIN_FONT = 10.0
        const val SCORE_WIDTH_FACTOR = 1.6
        const val SCORE_SPACING_FACTOR = 0.2
        const val CAPTION_FACTOR = 0.16
        const val VALUE_FACTOR = 0.3
    }
}
