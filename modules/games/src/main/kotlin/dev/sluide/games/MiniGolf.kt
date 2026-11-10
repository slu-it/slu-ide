package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.minigolf.MiniGolfModel
import dev.sluide.games.minigolf.MiniGolfPreferences
import dev.sluide.games.minigolf.ui.MiniGolfTab
import dev.sluide.games.minigolf.ui.MiniGolfView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Mini Golf game of one window: creates its tab and keeps the round, so that it outlives the tab. The `ide`
 * module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class MiniGolf internal constructor(
    preferences: MiniGolfPreferences,
    private val tabFactory: (MiniGolfModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: MiniGolfPreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(preferences, { MiniGolfTab(MiniGolfView(it, zoomPreferences)) })

    /** Shows the Mini Golf tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The round of the window; outlives the tab. */
    internal val model = MiniGolfModel(preferences)

    /** A new Mini Golf tab on the window's round, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
