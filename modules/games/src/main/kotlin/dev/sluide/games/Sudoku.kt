package dev.sluide.games

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.games.sudoku.Generator
import dev.sluide.games.sudoku.SudokuModel
import dev.sluide.games.sudoku.SudokuPreferences
import dev.sluide.games.sudoku.ui.SudokuTab
import dev.sluide.games.sudoku.ui.SudokuView
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Sudoku game of one window: creates its tab and keeps the game, so that it outlives the tab. No puzzle is made
 * until a view asks for one. The `ide` module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Sudoku internal constructor(
    /** The game of the window; outlives the tab. */
    internal val model: SudokuModel,
    private val tabFactory: (SudokuModel) -> EditorTab,
) {
    @Autowired
    constructor(preferences: SudokuPreferences) : this(
        SudokuModel(
            preferences,
            Generator()::generate,
            NamedThreads.single("sluide-sudoku-generator"),
            Platform::runLater,
        ),
        { SudokuTab(SudokuView(it)) },
    )

    /** Shows the Sudoku tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** A new Sudoku tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
