package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientStop
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.analysis.addStop
import dev.sluide.coloring.analysis.css
import dev.sluide.coloring.analysis.moveStop
import dev.sluide.coloring.analysis.removeStop
import dev.sluide.coloring.analysis.setStopColor
import dev.sluide.coloring.ui.gradient.GradientPreview
import dev.sluide.coloring.ui.gradient.StopsBar
import javafx.beans.property.SimpleObjectProperty
import javafx.css.PseudoClass
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.control.Toggle
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import kotlin.math.roundToInt

/**
 * A CSS gradient builder: it starts from the current color to the second color and is edited here. The gradient
 * belongs to the pane, so it starts again when the tab is opened anew. The decisions are in `analysis/Gradients.kt`;
 * this class connects them to the controls.
 */
internal class GradientPane(
    private val state: ColoringState,
    conicThrottleMillis: Double = 50.0,
) {
    private var gradient =
        Gradient(
            GradientType.LINEAR,
            90.0,
            listOf(GradientStop(state.current.get(), 0.0), GradientStop(state.second.get(), 1.0)),
            MixSpace.OKLCH,
        )
    private var selected = 0
    private var updating = false

    private val preview = GradientPreview(conicThrottleMillis)
    private val bar =
        StopsBar(onSelect = { apply(gradient, it) }, onMove = { p ->
            moveStop(gradient, selected, p).let { (g, i) -> apply(g, i) }
        })
    private val selectedColor = SimpleObjectProperty(gradient.stops[0].color)
    private val selectedSwatch = Swatch.of(selectedColor, state)
    private val types = ToggleGroup()
    private val spaces = ToggleGroup()
    private val angle = Slider(0.0, 360.0, gradient.angleDeg)
    private val angleField =
        TextField().apply {
            id = "gradient-angle"
            styleClass += "coloring-input"
            prefColumnCount = 4
        }
    private val stopField =
        ColorField(selectedColor, promptText = "Color of the stop").apply { node.id = "gradient-stop-text" }
    private val addButton = Button("Add stop").apply { id = "gradient-add" }
    private val removeButton = Button("Remove stop").apply { id = "gradient-remove" }
    private val setCurrentButton = Button("Set to current color").apply { id = "gradient-set-current" }
    private val useButton = Button("Use stop as current").apply { id = "gradient-use" }
    private val cssField =
        TextField().apply {
            id = "gradient-css"
            isEditable = false
        }
    private val copyButton = Button(null, FontIcon(MaterialDesignC.CONTENT_COPY)).apply { id = "gradient-copy" }
    private val currentSubscription: Subscription

    val node: Node

    /** The stops bar; for tests. */
    internal val stopsBar: StopsBar get() = bar

    init {
        val typeRow = HBox(4.0).apply { children += segmented(types, GradientType.entries.map { it to it.label() }) }
        val spaceRow = HBox(4.0).apply { children += segmented(spaces, SPACES.map { it to it.label }) }
        val angleRow =
            HBox(8.0, Label("Angle"), angle.also { HBox.setHgrow(it, Priority.ALWAYS) }, angleField, Label("deg"))
        val stopRow = HBox(8.0, selectedSwatch, stopField.node.also { HBox.setHgrow(it, Priority.ALWAYS) })
        val stopButtons = FlowPane(6.0, 6.0, addButton, removeButton, setCurrentButton, useButton)
        copyButton.tooltip = Tooltip("Copy the CSS")
        val outRow = HBox(6.0, cssField.also { HBox.setHgrow(it, Priority.ALWAYS) }, copyButton)
        node =
            ScrollPane(
                VBox(
                    10.0,
                    preview,
                    bar,
                    FlowPane(16.0, 8.0, typeRow, HBox(8.0, Label("Interpolation"), spaceRow)),
                    angleRow,
                    stopRow,
                    stopButtons,
                    outRow,
                ).apply {
                    styleClass += "gradient-pane"
                    padding = Insets(12.0)
                },
            ).apply { isFitToWidth = true }

        types.selectedToggleProperty().subscribe { _, t -> if (!updating) onType(t) }
        spaces.selectedToggleProperty().subscribe { _, t -> if (!updating) onSpace(t) }
        angle.valueProperty().subscribe { v ->
            if (!updating) apply(gradient.copy(angleDeg = v.toDouble().roundToInt().toDouble()))
        }
        angleField.textProperty().subscribe { text -> if (!updating) onAngleText(text) }
        selectedColor.subscribe { c -> if (!updating) apply(setStopColor(gradient, selected, c)) }
        addButton.setOnAction { addStop(gradient).let { (g, i) -> apply(g, i) } }
        removeButton.setOnAction { apply(removeStop(gradient, selected), selected) }
        setCurrentButton.setOnAction { apply(setStopColor(gradient, selected, state.current.get())) }
        useButton.setOnAction { state.current.set(gradient.stops[selected].color) }
        copyButton.setOnAction {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(css(gradient)) })
        }
        currentSubscription = state.current.subscribe { _ -> updateSetCurrent() }
        refresh()
    }

    /** Releases the listeners on the window's state and disposes the swatches. */
    fun dispose() {
        currentSubscription.unsubscribe()
        stopField.dispose()
        selectedSwatch.dispose()
        preview.dispose()
    }

    private fun apply(
        g: Gradient,
        sel: Int = selected,
    ) {
        gradient = g
        selected = sel.coerceIn(0, g.stops.lastIndex)
        refresh()
    }

    private fun onType(toggle: Toggle?) {
        val type = toggle?.userData as? GradientType
        if (type == null) refresh() else apply(gradient.copy(type = type))
    }

    private fun onSpace(toggle: Toggle?) {
        val space = toggle?.userData as? MixSpace
        if (space == null) refresh() else apply(gradient.copy(space = if (space == MixSpace.SRGB) null else space))
    }

    private fun onAngleText(text: String) {
        val deg = text.trim().toDoubleOrNull()
        when {
            text.isBlank() -> {
                angleField.pseudoClassStateChanged(INVALID, false)
            }

            deg == null || deg !in 0.0..360.0 -> {
                angleField.pseudoClassStateChanged(INVALID, true)
            }

            else -> {
                angleField.pseudoClassStateChanged(INVALID, false)
                apply(gradient.copy(angleDeg = deg))
            }
        }
    }

    private fun refresh() {
        updating = true
        try {
            val color = gradient.stops[selected].color
            cssField.text = css(gradient)
            preview.gradient = gradient
            bar.show(gradient, selected)
            selectedColor.set(color)
            types.toggles.first { it.userData == gradient.type }.isSelected = true
            val space = gradient.space ?: MixSpace.SRGB
            spaces.toggles.first { it.userData == space }.isSelected = true
            angle.isDisable = gradient.type == GradientType.RADIAL
            angleField.isDisable = angle.isDisable
            angle.value = gradient.angleDeg
            if (angleField.text.trim().toDoubleOrNull() != gradient.angleDeg) {
                angleField.text = gradient.angleDeg.roundToInt().toString()
                angleField.pseudoClassStateChanged(INVALID, false)
            }
            removeButton.isDisable = gradient.stops.size <= 2
            updateSetCurrent()
        } finally {
            updating = false
        }
    }

    private fun updateSetCurrent() {
        setCurrentButton.isDisable = gradient.stops[selected].color == state.current.get()
    }

    private fun <T> segmented(
        group: ToggleGroup,
        items: List<Pair<T, String>>,
    ): List<ToggleButton> =
        items.map { (value, label) ->
            ToggleButton(label).apply {
                userData = value
                id = "gradient-${label.lowercase()}"
                toggleGroup = group
            }
        }

    private fun GradientType.label() = name.lowercase().replaceFirstChar { it.uppercase() }

    private companion object {
        val INVALID: PseudoClass = PseudoClass.getPseudoClass("invalid")
        val SPACES = listOf(MixSpace.SRGB, MixSpace.OKLAB, MixSpace.OKLCH)
        val MixSpace.label get() =
            if (this ==
                MixSpace.SRGB
            ) {
                "sRGB"
            } else if (this == MixSpace.OKLAB) {
                "OKLab"
            } else {
                "OKLCH"
            }
    }
}
