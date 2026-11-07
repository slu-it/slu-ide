package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.orbitguard.OrbitGuardModel
import dev.sluide.games.orbitguard.OrbitGuardPreferences
import dev.sluide.games.orbitguard.ui.OrbitGuardTab
import dev.sluide.games.orbitguard.ui.OrbitGuardView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Orbit Guard game of one window: creates its tab and keeps the game, so that it outlives the tab. The `ide`
 * module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class OrbitGuard internal constructor(
    preferences: OrbitGuardPreferences,
    private val tabFactory: (OrbitGuardModel) -> EditorTab,
) {
    @Autowired
    constructor(preferences: OrbitGuardPreferences) : this(preferences, { OrbitGuardTab(OrbitGuardView(it)) })

    /** Shows the Orbit Guard tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The game of the window; outlives the tab. */
    internal val model = OrbitGuardModel(preferences)

    /** A new Orbit Guard tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
