package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.doubles.DoublesModel
import dev.sluide.games.doubles.DoublesPreferences
import dev.sluide.games.doubles.ui.DoublesTab
import dev.sluide.games.doubles.ui.DoublesView
import dev.sluide.games.doubles.ui.doublesLogo
import javafx.scene.Node
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Doubles game of one window: creates its tab and keeps the game, so that it outlives the tab. The `ide`
 * module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Doubles internal constructor(
    preferences: DoublesPreferences,
    private val tabFactory: (DoublesModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: DoublesPreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(preferences, { DoublesTab(DoublesView(it, zoomPreferences)) })

    /** Shows the Doubles tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The game of the window; outlives the tab. */
    internal val model = DoublesModel(preferences)

    /** A new Doubles tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)

    /** A new logo node for the Games menu item and the tab header: a 16 by 16 vector drawing. */
    fun logo(): Node = doublesLogo()
}
