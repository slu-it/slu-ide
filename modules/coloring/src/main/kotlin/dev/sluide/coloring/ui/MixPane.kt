package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.analysis.colorMixText
import dev.sluide.coloring.analysis.flattenText
import dev.sluide.coloring.analysis.flattenedOver
import dev.sluide.coloring.analysis.mixColor
import dev.sluide.coloring.analysis.mixSteps
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import javafx.util.Subscription

/** The mix of Color 1 with Color 2, and a color at an opacity over a background (Mix slots, ADR 0111). */
internal class MixPane(
    private val state: ColoringState,
) {
    private val swatches = mutableListOf<Swatch>()
    private val subscriptions = mutableListOf<Subscription>()

    /** The share of the second color in percent. */
    internal val amount = Slider(0.0, 100.0, 50.0)

    /** The color space of the mix. */
    internal val space =
        ChoiceBox<MixSpace>().apply {
            items.setAll(MixSpace.entries)
            value = MixSpace.OKLCH
            converter =
                object : StringConverter<MixSpace>() {
                    override fun toString(s: MixSpace?) = s?.css.orEmpty()

                    override fun fromString(text: String?) = MixSpace.entries.firstOrNull { it.css == text }
                }
        }
    private val mix1Slot = SlotView(state.mix1, state)
    private val mix2Slot = SlotView(state.mix2, state)

    /** The text field of the second color. */
    internal val secondText: TextField get() = mix2Slot.field

    private val result = SimpleObjectProperty(state.mix1.color.value)
    private val mixCss = readOnlyText()
    private val resultCss = readOnlyText()
    private val stripColors = List(STRIP_STEPS) { SimpleObjectProperty(state.mix1.color.value) }
    private val strip = HBox(4.0, *stripColors.map { swatch(it) }.toTypedArray())

    /** The nine swatches of the strip. */
    internal val stripSwatches: List<Swatch> get() = strip.children.filterIsInstance<Swatch>()

    /** The CSS `color-mix()` text now shown. */
    internal val mixCssText: String get() = mixCss.text

    /** The CSS text of the mix result now shown. */
    internal val resultCssText: String get() = resultCss.text

    /** The opacity of the flat color over the background, in percent. */
    internal val opacitySlider = Slider(0.0, 100.0, 50.0)
    private val flatColorSlot = SlotView(state.flatColor, state)
    private val flatBackgroundSlot = SlotView(state.flatBackground, state)

    /** The text field of the background color. */
    internal val backgroundText: TextField get() = flatBackgroundSlot.field

    private val flattened = SimpleObjectProperty(state.flatColor.color.value)
    private val flattenedCss = readOnlyText()

    /** The flattened color's CSS text now shown. */
    internal val flattenedCssText: String get() = flattenedCss.text

    val node: Node =
        VBox(
            12.0,
            title("Mix"),
            line(mix1Slot.node, Label("+"), mix2Slot.node, Label("in"), space),
            line(Label("Amount of second color"), amount, percentLabel(amount)),
            line(swatch(result, RESULT_SIZE), mixCss, copyButton("Copy color-mix()") { mixCss.text }),
            line(Label("Result"), resultCss, copyButton { resultCss.text }),
            strip,
            title("Opacity over a background"),
            line(
                flatColorSlot.node,
                Label("at"),
                opacitySlider,
                percentLabel(opacitySlider),
                Label("opacity over"),
                flatBackgroundSlot.node,
            ),
            line(swatch(flattened, RESULT_SIZE), flattenedCss, copyButton { flattenedCss.text }),
        ).apply { padding = Insets(12.0) }

    init {
        val refresh = Runnable { recompute() }
        subscriptions +=
            listOf(state.mix1, state.mix2, state.flatColor, state.flatBackground).map { it.color.subscribe(refresh) } +
            listOf(amount.valueProperty(), space.valueProperty(), opacitySlider.valueProperty()).map {
                it.subscribe(refresh)
            }
        recompute()
    }

    private fun recompute() {
        val first = state.mix1.color.value
        val second = state.mix2.color.value
        val p = Math.round(amount.value).toDouble()
        val s = space.value
        result.set(mixColor(first, second, p, s))
        mixCss.text = colorMixText(first, second, p, s)
        resultCss.text = cssText(result.value)
        mixSteps(first, second, s).forEachIndexed { i, c -> stripColors[i].set(c) }
        val o = Math.round(opacitySlider.value).toDouble()
        val flat = state.flatColor.color.value
        val background = state.flatBackground.color.value
        flattened.set(flattenedOver(flat, o, background))
        flattenedCss.text = flattenText(flat, o, background)
    }

    private fun swatch(
        color: ObservableValue<CssColor>,
        size: Double = 24.0,
    ) = Swatch.of(color, state, size).also { swatches += it }

    private fun title(text: String) = Label(text).apply { styleClass += "coloring-section-title" }

    private fun percentLabel(slider: Slider) =
        Label().apply { textProperty().bind(slider.valueProperty().map { "${Math.round(it.toDouble())} %" }) }

    private fun readOnlyText() =
        TextField().apply {
            isEditable = false
            styleClass += "coloring-format-text"
            prefColumnCount = TEXT_COLUMNS
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    private fun line(vararg nodes: Node) = HBox(6.0, *nodes).apply { alignment = Pos.CENTER_LEFT }

    /** Releases the listeners on the window's state. */
    fun dispose() {
        subscriptions.forEach(Subscription::unsubscribe)
        subscriptions.clear()
        listOf(mix1Slot, mix2Slot, flatColorSlot, flatBackgroundSlot).forEach(SlotView::dispose)
        swatches.forEach(Swatch::dispose)
        swatches.clear()
    }

    private companion object {
        const val STRIP_STEPS = 9
        const val RESULT_SIZE = 36.0
        const val TEXT_COLUMNS = 36
    }
}
