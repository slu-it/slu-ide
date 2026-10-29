package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.NamedColors
import dev.sluide.coloring.color.NamedMatch
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.color.nearestNamed
import dev.sluide.coloring.color.outOfSrgb
import dev.sluide.coloring.color.toRgb8
import javafx.css.PseudoClass
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.VBox
import javafx.util.Subscription
import java.util.Locale

/** The input field for the current color in any CSS notation, with a large preview and the color's name. */
internal class InputSection(
    private val state: ColoringState,
) {
    private val field =
        TextField().apply {
            styleClass += "coloring-input"
            promptText = "Any CSS color, e.g. #3b82f6 or oklch(62% 0.19 260)"
        }
    private val preview =
        Swatch(state.current, width = PREVIEW_MIN_WIDTH, height = PREVIEW_HEIGHT).apply {
            styleClass += "coloring-preview"
            maxWidth = Double.MAX_VALUE
        }
    private val gamutNote =
        Label("Outside sRGB — shown mapped into the gamut").apply {
            styleClass += "coloring-note"
            isWrapText = true
        }
    private val nameLabel = Label().apply { styleClass += "coloring-name" }
    private val nameTip = Tooltip()
    private var match: NamedMatch? = null
    private var settingText = false
    private val subscription: Subscription

    val node: Node =
        VBox(6.0, field, preview, gamutNote, nameLabel).apply { styleClass += "coloring-input-section" }

    init {
        field.textProperty().subscribe { text -> typed(text.orEmpty()) }
        field.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            when (e.code) {
                KeyCode.ESCAPE -> {
                    setText(cssText(state.current.get()))
                    field.pseudoClassStateChanged(INVALID, false)
                    e.consume()
                }

                KeyCode.ENTER -> {
                    field.selectAll()
                    e.consume()
                }

                else -> {}
            }
        }
        nameLabel.setOnMouseClicked { e ->
            if (e.button == MouseButton.PRIMARY) {
                match
                    ?.let { NamedColors.byName(it.name) }
                    ?.let {
                        state.current.set(
                            CssColor.fromRgb8(
                                it.r,
                                it.g,
                                it.b,
                                state.current
                                    .get()
                                    .toRgb8()
                                    .a,
                            ),
                        )
                    }
            }
        }
        subscription = state.current.subscribe { color -> changed(color) }
    }

    private fun typed(text: String) {
        if (settingText) return
        when (val result = ColorInputModel.onTyped(text)) {
            is InputResult.Valid -> {
                field.pseudoClassStateChanged(INVALID, false)
                state.current.set(result.color)
            }

            InputResult.Invalid -> {
                field.pseudoClassStateChanged(INVALID, true)
            }

            InputResult.Blank -> {
                field.pseudoClassStateChanged(INVALID, false)
            }
        }
    }

    private fun changed(color: CssColor) {
        showDetails(color)
        val text = ColorInputModel.textFor(color, field.text.orEmpty())
        field.pseudoClassStateChanged(INVALID, false)
        if (text != null) setText(text)
    }

    private fun setText(text: String) {
        settingText = true
        try {
            field.text = text
        } finally {
            settingText = false
        }
    }

    private fun showDetails(color: CssColor) {
        gamutNote.isVisible = color.outOfSrgb
        gamutNote.isManaged = color.outOfSrgb
        val m = nearestNamed(color)
        match = m
        nameLabel.text = if (m.exact) "Name: ${m.name}" else "Nearest name: ${m.name}"
        nameLabel.tooltip =
            if (m.exact) null else nameTip.apply { text = "ΔE OK ${"%.3f".format(Locale.ROOT, m.distance)}" }
    }

    /** Gives the focus to the input field. */
    fun focus() = field.requestFocus()

    /** Releases the listeners on the window's state. */
    fun dispose() {
        subscription.unsubscribe()
        preview.dispose()
    }

    private companion object {
        val INVALID: PseudoClass = PseudoClass.getPseudoClass("invalid")
        const val PREVIEW_MIN_WIDTH = 120.0
        const val PREVIEW_HEIGHT = 56.0
    }
}
