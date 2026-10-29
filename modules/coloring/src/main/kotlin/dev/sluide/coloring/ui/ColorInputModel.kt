package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.cssText

/** What a typed text of the input field means. */
internal sealed interface InputResult {
    /** The text is a color. */
    data class Valid(
        val color: CssColor,
    ) : InputResult

    /** The text is not blank and not a color. */
    data object Invalid : InputResult

    /** The text is blank: nothing to read, nothing wrong. */
    data object Blank : InputResult
}

/** The decisions of the input field, without JavaFX. */
internal object ColorInputModel {
    /** Reads the typed [text] leniently: a bare hex and a trailing `;` are accepted. */
    fun onTyped(text: String): InputResult {
        if (text.isBlank()) return InputResult.Blank
        val color = CssColorParser.parse(text, lenient = true) ?: return InputResult.Invalid
        return InputResult.Valid(color)
    }

    /**
     * The text to show for [color] when it changed elsewhere, or `null` if [currentText] already denotes exactly this
     * color (the user's own notation is kept).
     */
    fun textFor(
        color: CssColor,
        currentText: String,
    ): String? = if (CssColorParser.parse(currentText, lenient = true) == color) null else cssText(color)
}
