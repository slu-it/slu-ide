package dev.sluide.games.solitaire.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.formatTime
import dev.sluide.games.common.ui.GameZoomControls
import dev.sluide.games.common.ui.PauseOverlay
import dev.sluide.games.common.ui.TabVisibility
import dev.sluide.games.common.ui.focusBoardOnPress
import dev.sluide.games.solitaire.KeyAction
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.SolitaireModel
import dev.sluide.games.solitaire.StatusText
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.TableEvent
import dev.sluide.games.solitaire.Variant
import dev.sluide.games.solitaire.keyAction
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.util.Duration

/**
 * The Solitaire tab: toolbar, table on the felt, status line and the pause overlay. It only shows [model] and
 * forwards input; the view that shows a model owns its callbacks until [dispose].
 */
internal class SolitaireView(
    private val model: SolitaireModel,
    zoomPreferences: GameZoomPreferences,
    animationSpeed: Double = 1.0,
) {
    /** Asks a question with a [verb] button and Cancel (the default); true for the verb. Tests replace it. */
    internal var confirm: (title: String, header: String, verb: String, content: String) -> Boolean =
        { title, header, verb, content -> dialogs.confirm(title, header, verb, content) }

    /** Asks for a deal number; null when cancelled. Tests replace it. */
    internal var askDealNumber: () -> Int? = { dialogs.askDealNumber() }

    /** Asks whether to play the alternative deal (number, alternative). Tests replace it. */
    internal var askAlternative: (Int, Int) -> Boolean = { number, alternative ->
        dialogs.askAlternative(number, alternative)
    }

    private val scrollPane = ScrollPane()
    internal val table = TableView(model, scrollPane)
    internal val zoomControls =
        GameZoomControls(ZoomRange.ABOVE_FIT, zoomPreferences.zoom(GAME_ID, ZoomRange.ABOVE_FIT)) {
            zoomPreferences.setZoom(GAME_ID, it)
        }

    /** Plays the slides, flips and the deal; [animationSpeed] scales its durations (0: none, as in tests). */
    internal val animator = Animator(table, animationSpeed) { syncInput() }
    internal val burstPane = Pane()
    private var hiddenFoundations = emptyList<Node>()
    private val burst =
        WinBurst(burstPane) {
            hiddenFoundations.forEach { it.isVisible = true }
            hiddenFoundations = emptyList()
        }
    private val burstEnabled = animationSpeed > 0.0
    private val toolbar =
        SolitaireToolbar(
            ToolbarActions(
                chooseVariant = ::chooseVariant,
                chooseMode = ::chooseMode,
                newGame = ::newGame,
                restart = ::restart,
                dealNumber = ::dealNumber,
                undo = { act(model::undo) },
                redo = { act(model::redo) },
                hint = { act(model::hint) },
                pause = { act(model::togglePause) },
            ),
            zoomControls.node,
        )
    internal val gameMenu get() = toolbar.gameMenu
    internal val drawMenu get() = toolbar.drawMenu
    internal val suitsMenu get() = toolbar.suitsMenu
    internal val newGameButton get() = toolbar.newGameButton
    internal val restartButton get() = toolbar.restartButton
    internal val dealNumberButton get() = toolbar.dealNumberButton
    internal val undoButton get() = toolbar.undoButton
    internal val redoButton get() = toolbar.redoButton
    internal val hintButton get() = toolbar.hintButton
    internal val pauseButton get() = toolbar.pauseButton
    internal val scoreLabel get() = toolbar.score
    internal val movesLabel get() = toolbar.moves
    internal val timer get() = toolbar.timer
    internal val status = Label().apply { styleClass += "sol-status" }
    internal val dealing =
        Label("Dealing…").apply {
            styleClass += "sol-dealing"
            isMouseTransparent = true
            isVisible = false
        }
    private val pauseOverlay =
        PauseOverlay {
            if (model.isManuallyPaused) model.togglePause()
            table.requestFocus()
        }
    internal val overlay: Node get() = pauseOverlay.node
    private val ticker =
        Timeline(KeyFrame(Duration.seconds(1.0), { refreshStats() })).apply { cycleCount = Timeline.INDEFINITE }
    private var shown: Table? = null
    private var disposed = false

    val node: Node =
        BorderPane().apply {
            styleClass += "solitaire"
            stylesheets +=
                checkNotNull(SolitaireView::class.java.getResource("/dev/sluide/games/solitaire/solitaire.css"))
                    .toExternalForm()
            top = toolbar.node
            center =
                StackPane(
                    scrollPane.apply {
                        content = table
                        styleClass += "sol-scroll"
                        isFitToWidth = true
                        isFitToHeight = true
                        isFocusTraversable = false
                    },
                    dealing,
                    burstPane,
                    pauseOverlay.node.apply {
                        // The table alone sets the size: the overlay's hint is wider than a small table.
                        minWidth = 0.0
                        prefWidth = 0.0
                        minHeight = 0.0
                        prefHeight = 0.0
                    },
                ).apply { StackPane.setAlignment(dealing, Pos.CENTER) }
            bottom = HBox(status).apply { styleClass += "sol-status-bar" }
        }

    private val dialogs = SolitaireDialogs { node.scene?.window }

    /** The table has no scene yet when the content is focused before its first layout (it is in a scroll pane). */
    private val focusTable = FocusWhenShown()

    private val installed: () -> Unit = ::refresh
    private val visibility = TabVisibility(node) { model.hidden = it }

    init {
        table.isFocusTraversable = true
        table.setOnKeyPressed(::keyPressed)
        // A click gives the table the keyboard; consumed, or the scroll pane around it takes the focus afterwards.
        // The table's own handler (TableDrag) is on the same node and still runs.
        table.addEventHandler(MouseEvent.MOUSE_PRESSED) {
            skipAnimations()
            table.requestFocus()
            it.consume()
        }
        // a redraw (resize, theme) replaces the nodes: the burst, which hides foundation nodes, ends with it
        val completeAnimations = table.beforeRebuild
        table.beforeRebuild = {
            completeAnimations()
            burst.stop()
        }
        model.onChange = installed
        model.onEvent = ::onEvent
        // The editor area focuses the tab's content; the keys belong to the table.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusTable.request(table) }
        focusBoardOnPress(node, table)
        zoomControls.zoom.subscribe { zoom -> table.zoom = zoom.toDouble() }
        zoomControls.install(node)
        model.ensureGame()
        refresh()
    }

    /** Where the table's animations hook in: every [TableEvent] of the model arrives here, after its change. */
    private fun onEvent(event: TableEvent) {
        if (disposed) return
        if (event !is TableEvent.Won && event !is TableEvent.Hint) burst.stop()
        when (event) {
            is TableEvent.Hint -> {
                val target = hintTarget(event.move, model.mode)
                table.showHint(target.from, target.count, target.to)
            }

            is TableEvent.Moved -> {
                animator.step(event.step, reverse = false, auto = event.auto)
            }

            is TableEvent.Redone -> {
                animator.step(event.step, reverse = false)
            }

            is TableEvent.Undone -> {
                animator.step(event.step, reverse = true)
            }

            is TableEvent.Dealt, TableEvent.Restarted -> {
                animator.deal()
            }

            is TableEvent.Won -> {
                startBurst()
            }
        }
        syncInput()
    }

    private fun startBurst() {
        if (!burstEnabled) return
        val images = table.cardImages()
        val layout = table.layoutNow()
        val cards =
            burstCards(table.shown, layout).map { burstCard ->
                burstCard to
                    ImageView(images.face(burstCard.card)).apply {
                        fitWidth = layout.cardWidth
                        fitHeight = layout.cardHeight
                        isManaged = false
                    }
            }
        burst.start(cards, table, layout.cardHeight)
        // the foundations' cards fly: show the empty slots meanwhile, once the last slide of auto-finish has landed
        animator.afterSlides {
            if (!burst.isRunning) return@afterSlides
            val shown = table.shown
            hiddenFoundations =
                shown.ids(PileKind.FOUNDATION).mapNotNull { table.nodeAt(it, shownIndex(shown, it)) }.onEach {
                    it.isVisible = false
                }
        }
    }

    /** A click or key ends the deal cascade and the win burst; true when it did, and the input is then used up. */
    private fun skipAnimations(): Boolean {
        val skipped = animator.cascading || burst.isRunning
        if (animator.cascading) animator.finish()
        burst.stop()
        return skipped
    }

    private fun syncInput() {
        table.inputEnabled = model.inputEnabled && !animator.cascading
    }

    private fun act(action: () -> Unit) {
        action()
        table.requestFocus()
    }

    private fun guard(
        title: String,
        verb: String = title,
    ): Boolean =
        !model.needsConfirmToAbandon ||
            confirm(title, "Abandon this game?", verb, "Its score of ${StatusText.points(model.score)} is lost.")

    private fun newGame() {
        if (guard("New Game")) model.newGame()
        table.requestFocus()
    }

    private fun restart() {
        val start =
            !model.needsConfirmToAbandon ||
                confirm("Restart", "Start this deal over?", "Restart", "Every move is undone; the clock keeps running.")
        if (start) model.restart()
        table.requestFocus()
    }

    private fun chooseVariant(variant: Variant) {
        if (variant != model.mode.variant && guard("Change Game", "Change")) model.chooseVariant(variant)
        refresh()
        table.requestFocus()
    }

    private fun chooseMode(mode: Mode) {
        if (mode != model.mode &&
            guard(if (mode.variant == Variant.KLONDIKE) "Change Draw" else "Change Suits", "Change")
        ) {
            model.chooseMode(mode)
        }
        refresh()
        table.requestFocus()
    }

    private fun dealNumber() {
        if (guard("Deal Number", "Abandon")) askDealNumber()?.let { model.requestDeal(it, askAlternative) }
        table.requestFocus()
    }

    private fun keyPressed(event: KeyEvent) {
        if (skipAnimations()) {
            event.consume()
            return
        }
        val other =
            event.isAltDown ||
                (event.isControlDown && !event.isShortcutDown) ||
                (event.isMetaDown && !event.isShortcutDown)
        val action = keyAction(event.code, event.isShiftDown, event.isShortcutDown, other) ?: return
        if (action == KeyAction.NewGame) {
            if (!model.isDealing) newGame()
        } else {
            model.key(action)
        }
        event.consume()
    }

    private fun refresh() {
        if (disposed) return
        // Before the first deal the model gives a new empty table on every call: equal, so not shown again.
        val current = model.table
        if (current !== shown && current != shown) {
            shown = current
            table.show(current)
        }
        syncInput()
        val cursor = model.cursor
        table.showCursor(cursor.pile, cursor.depth)
        table.showPicked(cursor.picked?.pile, cursor.picked?.count ?: 0)
        dealing.isVisible = model.isDealing && model.game == null
        pauseOverlay.show(model.isPaused, model.isManuallyPaused)
        status.text = model.statusText
        refreshToolbar()
        refreshStats()
        if (model.canPause && !model.isPaused) ticker.play() else ticker.stop()
    }

    private fun refreshToolbar() {
        val mode = model.mode
        val idle = !model.isDealing
        toolbar.gameMenu.text = mode.variant.label
        toolbar.gameItems.getValue(mode.variant).isSelected = true
        toolbar.drawMenu.text = if (mode == Mode.KLONDIKE_DRAW_3) "Draw 3" else "Draw 1"
        toolbar.drawItems[mode]?.isSelected = true
        toolbar.suitsMenu.text = suitsLabel(mode)
        toolbar.suitsItems[mode]?.isSelected = true
        showIf(toolbar.drawMenu, mode.variant == Variant.KLONDIKE)
        showIf(toolbar.suitsMenu, mode.variant == Variant.SPIDER)
        // The menus stay enabled while dealing: a slow deal is left by switching (the model drops the stale deal).
        toolbar.newGameButton.isDisable = !idle
        toolbar.dealNumberButton.isDisable = !idle
        toolbar.restartButton.isDisable = !model.canRestart
        toolbar.undoButton.isDisable = !model.canUndo
        toolbar.redoButton.isDisable = !model.canRedo
        toolbar.hintButton.isDisable = !model.canHint
        toolbar.pauseButton.isDisable = !model.canPause
        toolbar.pauseButton.isSelected = model.isManuallyPaused
        toolbar.showPaused(model.isManuallyPaused)
    }

    private fun showIf(
        control: Node,
        visible: Boolean,
    ) {
        control.isVisible = visible
        control.isManaged = visible
    }

    private fun suitsLabel(mode: Mode) =
        when (mode) {
            Mode.SPIDER_2 -> "2 Suits"
            Mode.SPIDER_4 -> "4 Suits"
            else -> "1 Suit"
        }

    private fun refreshStats() {
        toolbar.score.text = "Score: ${StatusText.points(model.score)}"
        toolbar.moves.text = "Moves: ${model.moves}"
        toolbar.timer.text = formatTime(model.elapsedMillis)
    }

    /** The model stops notifying this view (unless a newer view has taken it over) and the game pauses. */
    fun dispose() {
        disposed = true
        animator.finish()
        burst.stop()
        focusTable.cancel()
        ticker.stop()
        visibility.dispose()
        if (model.onChange === installed) {
            model.onChange = {}
            model.onEvent = {}
            model.hidden = true
        }
    }

    internal companion object {
        const val GAME_ID = "solitaire"
    }
}
