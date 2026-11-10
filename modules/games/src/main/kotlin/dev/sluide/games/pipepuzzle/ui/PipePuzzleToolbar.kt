package dev.sluide.games.pipepuzzle.ui

import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.pipepuzzle.BoardSize
import dev.sluide.games.pipepuzzle.Look
import dev.sluide.games.pipepuzzle.Mode
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.javafx.FontIcon

internal val Mode.label: String get() = if (this == Mode.NETWORK) "Network" else "Path"

internal val BoardSize.label: String get() = "$side×$side"

internal val Look.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** What the toolbar's controls do; the view implements it. */
internal class ToolbarActions(
    val newGame: () -> Unit,
    val restart: () -> Unit,
    val hint: () -> Unit,
    val pause: () -> Unit,
    val chooseMode: (Mode) -> Unit,
    val chooseSize: (BoardSize) -> Unit,
    val chooseLook: (Look) -> Unit,
)

/** The Pipe Puzzle toolbar: the controls, which [PipePuzzleView] updates; the clicks go to [actions]. */
internal class PipePuzzleToolbar(
    actions: ToolbarActions,
    zoom: Node,
) {
    val modeMenu = MenuButton()
    val sizeMenu = MenuButton()
    val lookMenu = MenuButton()
    val newGameButton = button("mdi2r-refresh", "New Game (F2)", actions.newGame)
    val restartButton = button("mdi2r-restore", "Restart Puzzle (R)", actions.restart)
    val hintButton = button("mdi2l-lightbulb-on-outline", "Hint (H)", actions.hint)
    val pauseButton =
        ToggleButton(null, icon("mdi2p-pause")).apply {
            isFocusTraversable = false
            tooltip = Tooltip("Pause (P)")
            setOnAction { actions.pause() }
        }
    val timer = Label().apply { graphic = icon("mdi2t-timer-outline") }

    val modeItems = radioItems(Mode.entries, Mode::label, actions.chooseMode)
    val sizeItems = radioItems(BoardSize.entries, BoardSize::label, actions.chooseSize)
    val lookItems = radioItems(Look.entries, Look::label, actions.chooseLook)

    val node: WrappingToolbar =
        WrappingToolbar().apply {
            hgap = 6.0
            for ((menu, items) in listOf(modeMenu to modeItems, sizeMenu to sizeItems, lookMenu to lookItems)) {
                menu.isFocusTraversable = false
                menu.items.setAll(items.values)
            }
            leading.setAll(modeMenu, sizeMenu, lookMenu, newGameButton, restartButton, hintButton, pauseButton)
            trailing.setAll(timer, zoom)
            styleClass += "pp-toolbar"
        }

    /** Shows the pause button as pausing or resuming. */
    fun showPaused(paused: Boolean) {
        (pauseButton.graphic as FontIcon).iconLiteral = if (paused) "mdi2p-play" else "mdi2p-pause"
        pauseButton.tooltip.text = if (paused) "Resume (P)" else "Pause (P)"
    }

    private fun <T : Any> radioItems(
        values: List<T>,
        label: (T) -> String,
        choose: (T) -> Unit,
    ): Map<T, RadioMenuItem> {
        val group = ToggleGroup()
        return values.associateWith { value ->
            RadioMenuItem(label(value)).apply {
                toggleGroup = group
                setOnAction { choose(value) }
            }
        }
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

    private fun icon(literal: String) = FontIcon(literal).apply { iconSize = ICON_SIZE }

    private companion object {
        const val ICON_SIZE = 16
    }
}
