package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.blockbreaker.BlockBreakerModel
import dev.sluide.games.blockbreaker.BlockBreakerPreferences
import dev.sluide.games.blockbreaker.ui.BlockBreakerTab
import dev.sluide.games.blockbreaker.ui.BlockBreakerView
import dev.sluide.games.common.GameZoomPreferences
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Block Breaker game of one window: creates its tab and keeps the game, so that it outlives the tab. The `ide`
 * module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class BlockBreaker internal constructor(
    preferences: BlockBreakerPreferences,
    private val tabFactory: (BlockBreakerModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: BlockBreakerPreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(preferences, { BlockBreakerTab(BlockBreakerView(it, zoomPreferences)) })

    /** Shows the Block Breaker tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The game of the window; outlives the tab. */
    internal val model = BlockBreakerModel(preferences)

    /** A new Block Breaker tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
