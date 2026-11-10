package dev.sluide.previews.image

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane
import javafx.stage.Window
import javafx.util.Callback
import javafx.util.StringConverter

/**
 * Asks for the width and height in pixels of a PNG, starting with the SVG's [natural] size. The aspect ratio is always
 * kept: the other field follows as one is typed into or stepped. The result is the (width, height) on Export, null on
 * Cancel. Export is disabled for a size that is no positive number or beyond the limits of [WebViewSvgRasterizer].
 */
internal class PngSizeDialog(
    owner: Window?,
    natural: Pair<Int, Int>,
) : Dialog<Pair<Int, Int>?>() {
    private val ratio = PngSizeRatio(natural.first, natural.second)
    private val widthSpinner = spinner("png-width", natural.first)
    private val heightSpinner = spinner("png-height", natural.second)
    private val hint =
        Label(
            "At most ${WebViewSvgRasterizer.MAX_SIDE} px per side and about " +
                "${WebViewSvgRasterizer.MAX_PIXELS / MEGA} megapixels",
        ).apply {
            styleClass += "png-size-hint"
            isWrapText = true
            isVisible = false
            isManaged = false
        }
    private val export = ButtonType("Export", ButtonData.OK_DONE)

    /** Set while one field follows the other, so that the follower does not answer. */
    private var syncing = false

    init {
        title = "Export PNG"
        headerText = null
        if (owner != null) initOwner(owner)
        dialogPane.buttonTypes.setAll(export, ButtonType.CANCEL)
        dialogPane.content =
            GridPane().apply {
                hgap = 10.0
                vgap = 8.0
                padding = Insets(12.0)
                addRow(0, Label("Width"), widthSpinner, Label("px"))
                addRow(1, Label("Height"), heightSpinner, Label("px"))
                add(hint, 0, 2, 3, 1)
            }
        follow(widthSpinner, heightSpinner, ratio::heightFor)
        follow(heightSpinner, widthSpinner, ratio::widthFor)
        widthSpinner.editor.textProperty().addListener { _, _, _ -> refresh() }
        heightSpinner.editor.textProperty().addListener { _, _, _ -> refresh() }
        refresh()
        resultConverter = Callback { button -> if (button == export) typedSize() else null }
    }

    /** The sizes typed (or stepped to) in both fields, if both are positive numbers. */
    private fun typedSize(): Pair<Int, Int>? {
        val width = number(widthSpinner) ?: return null
        val height = number(heightSpinner) ?: return null
        return if (width >= 1 && height >= 1) width to height else null
    }

    private fun number(spinner: Spinner<Int>) =
        spinner.editor.text
            .trim()
            .toIntOrNull()

    /** Enables Export for a valid size within the limits and shows the hint for one beyond them. */
    private fun refresh() {
        val size = typedSize()
        val tooLarge = size?.let { (width, height) -> ratio.tooLarge(width, height) } ?: false
        // Typed text beyond Int is not a number; the hint is for sizes that are one.
        hint.isVisible = tooLarge
        hint.isManaged = tooLarge
        dialogPane.lookupButton(export)?.isDisable = size == null || tooLarge
        dialogPane.scene?.window?.sizeToScene()
    }

    /** Sets [other] to what [convert] makes of what is typed into [source] or stepped to. */
    private fun follow(
        source: Spinner<Int>,
        other: Spinner<Int>,
        convert: (Int) -> Int,
    ) {
        source.editor.textProperty().addListener { _, _, _ ->
            if (syncing) return@addListener
            val typed = number(source)?.takeIf { it >= 1 } ?: return@addListener
            syncing = true
            try {
                val follower = convert(typed)
                (other.valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory).value =
                    follower.coerceIn(1, WebViewSvgRasterizer.MAX_SIDE)
                other.editor.text = follower.toString()
            } finally {
                syncing = false
            }
        }
    }

    private fun spinner(
        id: String,
        value: Int,
    ) = Spinner<Int>(1, WebViewSvgRasterizer.MAX_SIDE, value.coerceIn(1, WebViewSvgRasterizer.MAX_SIDE)).apply {
        this.id = id
        styleClass += id
        isEditable = true
        prefWidth = 100.0
        // The spinner commits the text itself on the arrows: text that is no number keeps the value, a number is
        // clamped to the limits (the pixel total is checked by refresh).
        val factory = valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory
        factory.converter =
            object : StringConverter<Int>() {
                override fun toString(value: Int?) = value?.toString().orEmpty()

                override fun fromString(text: String?) = clamped(text) ?: factory.value
            }
        // On Enter and when the field loses the focus, the text goes back to the value it was committed as.
        editor.addEventHandler(ActionEvent.ACTION) { settle(this) }
        focusedProperty().addListener { _, _, focused -> if (!focused) settle(this) }
        // Text beyond the maximum stays as typed until then (and Export is off), instead of snapping back at once.
        editor.text = value.toString()
    }

    private fun clamped(text: String?) = text?.trim()?.toIntOrNull()?.coerceIn(1, WebViewSvgRasterizer.MAX_SIDE)

    /** Commits the text typed into [spinner]: a number is clamped to the limits, other text goes back to the value. */
    private fun settle(spinner: Spinner<Int>) {
        val factory = spinner.valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory
        clamped(spinner.editor.text)?.let { factory.value = it }
        spinner.editor.text = factory.value.toString()
    }

    private companion object {
        const val MEGA = 1_000_000
    }
}
