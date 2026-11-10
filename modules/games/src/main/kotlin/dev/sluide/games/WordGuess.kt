package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.wordguess.WordGuessModel
import dev.sluide.games.wordguess.WordGuessPreferences
import dev.sluide.games.wordguess.ui.WordGuessTab
import dev.sluide.games.wordguess.ui.WordGuessView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The Word Guess game of one window: creates its tab and keeps the round, so that it outlives the tab. The `ide`
 * module sets [showTab].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class WordGuess internal constructor(
    preferences: WordGuessPreferences,
    private val tabFactory: (WordGuessModel) -> EditorTab,
) {
    @Autowired
    constructor(
        preferences: WordGuessPreferences,
        zoomPreferences: GameZoomPreferences,
    ) : this(preferences, { WordGuessTab(WordGuessView(it, zoomPreferences)) })

    /** Shows the Word Guess tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** The game of the window; outlives the tab. */
    internal val model = WordGuessModel(preferences)

    /** A new Word Guess tab on the window's game, which outlives the tab. */
    fun createTab(): EditorTab = tabFactory(model)
}
