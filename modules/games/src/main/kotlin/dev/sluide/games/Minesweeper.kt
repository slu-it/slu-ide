package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.minesweeper.MinesweeperModel
import dev.sluide.games.minesweeper.MinesweeperPreferences
import dev.sluide.games.minesweeper.ui.MinesweeperTab
import dev.sluide.games.minesweeper.ui.MinesweeperView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Minesweeper game of one window: creates its tab and keeps the game, so that it outlives the tab (ADR 0110).
 * The `ide` module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Minesweeper internal constructor(
    preferences: MinesweeperPreferences,
    private val tabFactory: (MinesweeperModel) -> EditorTab,
) {
    @Autowired
    constructor(preferences: MinesweeperPreferences) : this(preferences, { MinesweeperTab(MinesweeperView(it)) })

    /** Shows the Minesweeper tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The game of the window; outlives the tab. */
    internal val model = MinesweeperModel(preferences)

    /** A new Minesweeper tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
