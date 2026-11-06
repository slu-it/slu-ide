package dev.sluide.games.wordguess.ui

import dev.sluide.games.wordguess.GAP_FACTOR
import dev.sluide.games.wordguess.KEY_HEIGHT_FACTOR
import dev.sluide.games.wordguess.KEY_WIDTH_FACTOR
import dev.sluide.games.wordguess.KeyboardKey
import dev.sluide.games.wordguess.Language
import dev.sluide.games.wordguess.LetterState
import dev.sluide.games.wordguess.MIN_TILE
import dev.sluide.games.wordguess.WIDE_KEY_UNITS
import dev.sluide.games.wordguess.keyboardRows
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The on-screen keyboard of a language; its keys show the best-known state of their letter. Clicks go to the
 * callbacks. The keys are not focus-traversable, the view hands the focus back to the grid.
 */
internal class KeyboardView(
    private val onLetter: (Char) -> Unit,
    private val onEnter: () -> Unit,
    private val onBackspace: () -> Unit,
) : VBox() {
    private class Key(
        val button: Button,
        val units: Double,
    )

    private var language: Language? = null
    private val letterKeys = mutableMapOf<Char, Button>()
    private val specialKeys = mutableMapOf<KeyboardKey, Button>()
    private var keys: List<Key> = emptyList()
    private val rowBoxes = mutableListOf<HBox>()
    private var tile = MIN_TILE

    init {
        styleClass += "wg-keyboard"
        alignment = Pos.CENTER
    }

    /** Shows the keyboard of [language] (a no-op for the one shown). */
    fun show(language: Language) {
        if (this.language == language) return
        this.language = language
        letterKeys.clear()
        specialKeys.clear()
        val all = mutableListOf<Key>()
        rowBoxes.clear()
        for (row in keyboardRows(language)) {
            val made = row.map { key(it) }
            all += made
            rowBoxes += HBox(tile * GAP_FACTOR, *made.map { it.button }.toTypedArray()).apply { alignment = Pos.CENTER }
        }
        keys = all
        children.setAll(rowBoxes)
        applyTile(tile)
    }

    /** Sets the tile side the keys scale with. */
    fun applyTile(side: Double) {
        tile = side
        spacing = side * GAP_FACTOR
        rowBoxes.forEach { it.spacing = side * GAP_FACTOR }
        keys.forEach {
            val width = side * KEY_WIDTH_FACTOR * it.units + (it.units - 1) * side * GAP_FACTOR
            val height = side * KEY_HEIGHT_FACTOR
            it.button.setMinSize(width, height)
            it.button.setPrefSize(width, height)
            it.button.setMaxSize(width, height)
            val font = if (it.units > 1.0) SMALL_FONT_FACTOR else FONT_FACTOR
            it.button.style = "-fx-font-size: ${(side * font).toInt()}px;"
            (it.button.graphic as? FontIcon)?.iconSize = (side * ICON_FACTOR).toInt()
        }
    }

    fun update(states: Map<Char, LetterState>) {
        letterKeys.forEach { (letter, button) ->
            button.styleClass.setAll(
                "button",
                "wg-key",
                when (states[letter]) {
                    LetterState.CORRECT -> "wg-correct"
                    LetterState.PRESENT -> "wg-present"
                    LetterState.ABSENT -> "wg-absent"
                    null -> "wg-unknown"
                },
            )
        }
    }

    /** The key of [letter] (for tests and the view), if the shown keyboard has it. */
    fun keyOf(letter: Char): Button? = letterKeys[letter]

    /** The Enter or Backspace key (for tests). */
    fun keyOf(key: KeyboardKey): Button? = specialKeys[key]

    private fun key(key: KeyboardKey): Key =
        when (key) {
            is KeyboardKey.Letter -> {
                val button = base(Button(key.letter.toString())) { onLetter(key.letter) }
                letterKeys[key.letter] = button
                Key(button, 1.0)
            }

            KeyboardKey.Enter -> {
                Key(base(Button("Enter")) { onEnter() }.also { specialKeys[key] = it }, WIDE_KEY_UNITS)
            }

            KeyboardKey.Backspace -> {
                val icon = FontIcon("mdi2b-backspace-outline").apply { styleClass += "wg-key-icon" }
                Key(base(Button(null, icon)) { onBackspace() }.also { specialKeys[key] = it }, WIDE_KEY_UNITS)
            }
        }

    private fun base(
        button: Button,
        action: () -> Unit,
    ) = button.apply {
        isFocusTraversable = false
        styleClass += listOf("wg-key", "wg-unknown")
        setOnAction { action() }
    }

    private companion object {
        const val FONT_FACTOR = 0.35
        const val SMALL_FONT_FACTOR = 0.25
        const val ICON_FACTOR = 0.4
    }
}
