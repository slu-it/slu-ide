package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import javafx.beans.property.ObjectProperty
import javafx.css.PseudoClass
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.util.Subscription

/**
 * A text field for a color in any CSS notation, bound both ways to [color]: a valid text sets it, bad text is marked
 * `:invalid` and leaves it alone, blank is neither. Escape restores the color's text, Enter selects all. An external
 * change rewrites the text only if it no longer denotes
 * that color, and never writes a rounded color back.
 */
internal class ColorField(
    private val color: ObjectProperty<CssColor>,
    columns: Int = 12,
    promptText: String? = null,
) {
    val node: TextField =
        TextField().apply {
            styleClass += "coloring-input"
            prefColumnCount = columns
            this.promptText = promptText
        }
    private var settingText = false
    private val subscriptions: List<Subscription>

    init {
        setText(cssText(color.value))
        node.addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        subscriptions =
            listOf(
                node.textProperty().subscribe { _, text -> typed(text.orEmpty()) },
                color.subscribe { _, c -> changed(c) },
            )
    }

    private fun onKey(e: KeyEvent) {
        when (e.code) {
            KeyCode.ESCAPE -> {
                setText(cssText(color.value))
                node.pseudoClassStateChanged(INVALID, false)
                e.consume()
            }

            KeyCode.ENTER -> {
                node.selectAll()
                e.consume()
            }

            else -> {}
        }
    }

    private fun typed(text: String) {
        if (settingText) return
        when (val result = ColorInputModel.onTyped(text)) {
            is InputResult.Valid -> {
                node.pseudoClassStateChanged(INVALID, false)
                if (result.color != color.value) color.set(result.color)
            }

            InputResult.Invalid -> {
                node.pseudoClassStateChanged(INVALID, true)
            }

            InputResult.Blank -> {
                node.pseudoClassStateChanged(INVALID, false)
            }
        }
    }

    private fun changed(c: CssColor?) {
        if (c == null) return
        node.pseudoClassStateChanged(INVALID, false)
        ColorInputModel.textFor(c, node.text.orEmpty())?.let(::setText)
    }

    private fun setText(text: String) {
        settingText = true
        try {
            node.text = text
        } finally {
            settingText = false
        }
    }

    /** Stops following [color]. */
    fun dispose() = subscriptions.forEach(Subscription::unsubscribe)

    private companion object {
        val INVALID: PseudoClass = PseudoClass.getPseudoClass("invalid")
    }
}
