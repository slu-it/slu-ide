package dev.sluide.games

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.pipepuzzle.Generator
import dev.sluide.games.pipepuzzle.PipePuzzleModel
import dev.sluide.games.pipepuzzle.PipePuzzlePreferences
import dev.sluide.games.pipepuzzle.ui.PipePuzzleTab
import dev.sluide.games.pipepuzzle.ui.PipePuzzleView
import dev.sluide.games.pipepuzzle.ui.pipePuzzleLogo
import javafx.application.Platform
import javafx.scene.Node
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * The Pipe Puzzle game of one window: creates its tab and keeps the game, so that it outlives the tab. No puzzle is
 * made until a view asks for one. The `ide` module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class PipePuzzle internal constructor(
    /** The game of the window; outlives the tab. */
    internal val model: PipePuzzleModel,
    private val tabFactory: (PipePuzzleModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: PipePuzzlePreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(
        PipePuzzleModel(
            preferences,
            { mode, size -> Generator().generate(mode, size, Random.Default) },
            NamedThreads.single("sluide-pipe-puzzle-generator"),
            Platform::runLater,
        ),
        { PipePuzzleTab(PipePuzzleView(it, zoomPreferences)) },
    )

    /** Shows the Pipe Puzzle tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** A new Pipe Puzzle tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)

    /** A new logo node for the Games menu item and the tab header: a 16 by 16 vector drawing. */
    fun logo(): Node = pipePuzzleLogo()
}
