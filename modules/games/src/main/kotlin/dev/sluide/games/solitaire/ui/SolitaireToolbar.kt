package dev.sluide.games.solitaire.ui

import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Variant
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.javafx.FontIcon

/** What the toolbar's controls do; the view implements it. */
internal class ToolbarActions(
    val chooseVariant: (Variant) -> Unit,
    val chooseMode: (Mode) -> Unit,
    val newGame: () -> Unit,
    val restart: () -> Unit,
    val dealNumber: () -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val hint: () -> Unit,
    val pause: () -> Unit,
)

/** The Solitaire toolbar: the controls, which [SolitaireView] updates; the clicks go to [actions]. */
internal class SolitaireToolbar(
    actions: ToolbarActions,
) {
    val gameMenu = MenuButton()
    val drawMenu = MenuButton()
    val suitsMenu = MenuButton()
    val newGameButton = button("mdi2r-refresh", "New Game (F2)", actions.newGame)
    val restartButton = button("mdi2r-restore", "Restart Deal", actions.restart)
    val dealNumberButton = button("mdi2p-pound", "Deal Number…", actions.dealNumber)
    val undoButton = button("mdi2u-undo", "Undo (U)", actions.undo)
    val redoButton = button("mdi2r-redo", "Redo (Shift+U)", actions.redo)
    val hintButton = button("mdi2l-lightbulb-on-outline", "Hint (H)", actions.hint)
    val pauseButton =
        ToggleButton(null, icon("mdi2p-pause")).apply {
            isFocusTraversable = false
            tooltip = Tooltip("Pause (P)")
            setOnAction { actions.pause() }
        }
    val score = Label()
    val moves = Label()
    val timer = Label().apply { graphic = icon("mdi2t-timer-outline") }

    val gameItems = Variant.entries.associateWith { choice(it.label) { actions.chooseVariant(it) } }
    val drawItems = modeItems(actions, Mode.KLONDIKE_DRAW_1 to "Draw 1", Mode.KLONDIKE_DRAW_3 to "Draw 3")
    val suitsItems =
        modeItems(actions, Mode.SPIDER_1 to "1 Suit", Mode.SPIDER_2 to "2 Suits", Mode.SPIDER_4 to "4 Suits")

    val node: WrappingToolbar =
        WrappingToolbar().apply {
            hgap = 6.0
            gameMenu.setup(gameItems.values, "Game")
            drawMenu.setup(drawItems.values, "Draw")
            suitsMenu.setup(suitsItems.values, "Suits")
            leading.setAll(
                gameMenu,
                drawMenu,
                suitsMenu,
                newGameButton,
                restartButton,
                dealNumberButton,
                undoButton,
                redoButton,
                hintButton,
                pauseButton,
            )
            trailing.setAll(score, moves, timer)
            styleClass += "sol-toolbar"
        }

    /** Shows the pause button as pausing or resuming. */
    fun showPaused(paused: Boolean) {
        (pauseButton.graphic as FontIcon).iconLiteral = if (paused) "mdi2p-play" else "mdi2p-pause"
        pauseButton.tooltip.text = if (paused) "Resume (P)" else "Pause (P)"
    }

    private fun modeItems(
        actions: ToolbarActions,
        vararg modes: Pair<Mode, String>,
    ) = modes.associate { (mode, label) -> mode to choice(label) { actions.chooseMode(mode) } }

    private fun MenuButton.setup(
        entries: Collection<RadioMenuItem>,
        tip: String,
    ) {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        val group = ToggleGroup()
        entries.forEach { it.toggleGroup = group }
        items.setAll(entries)
    }

    private fun choice(
        label: String,
        action: () -> Unit,
    ) = RadioMenuItem(label).apply { setOnAction { action() } }

    private fun button(
        literal: String,
        tip: String,
        action: () -> Unit,
    ) = Button(null, icon(literal)).apply {
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { action() }
    }

    private fun icon(literal: String) = FontIcon(literal).apply { iconSize = ICON_SIZE }

    private companion object {
        const val ICON_SIZE = 16
    }
}
