package dev.sluide.games

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.solitaire.Cancellable
import dev.sluide.games.solitaire.SolitaireModel
import dev.sluide.games.solitaire.SolitairePreferences
import dev.sluide.games.solitaire.solver.DealGenerator
import dev.sluide.games.solitaire.solver.Solver
import dev.sluide.games.solitaire.ui.SolitaireTab
import dev.sluide.games.solitaire.ui.SolitaireView
import dev.sluide.games.solitaire.ui.solitaireLogo
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.scene.Node
import javafx.util.Duration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Solitaire game of one window: creates its tab and keeps the game, so that it outlives the tab. No deal is made
 * until a view asks for one. The `ide` module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Solitaire internal constructor(
    /** The game of the window; outlives the tab. */
    internal val model: SolitaireModel,
    private val tabFactory: (SolitaireModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: SolitairePreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(
        modelOf(preferences),
        { SolitaireTab(SolitaireView(it, zoomPreferences)) },
    )

    /** Shows the Solitaire tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** A new Solitaire tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)

    /** A new logo node for the Games menu item and the tab header: a 16 by 16 vector drawing. */
    fun logo(): Node = solitaireLogo()

    private companion object {
        fun modelOf(preferences: SolitairePreferences): SolitaireModel {
            val deals = DealGenerator()
            return SolitaireModel(
                preferences,
                { mode, from, cancelled -> deals.firstWinnable(mode, from, cancelled = cancelled) },
                { mode, number, cancelled -> deals.dealIfWinnable(mode, number, cancelled) },
                Solver()::solve,
                NamedThreads.single("sluide-solitaire-dealer"),
                NamedThreads.single("sluide-solitaire-hints"),
                Platform::runLater,
                { millis, task ->
                    val pause = PauseTransition(Duration.millis(millis.toDouble()))
                    pause.setOnFinished { task.run() }
                    pause.play()
                    Cancellable { pause.stop() }
                },
            )
        }
    }
}
