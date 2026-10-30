package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientStop
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.analysis.setStopColor
import dev.sluide.coloring.color.CssColor
import javafx.beans.property.IntegerProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.util.Subscription

/**
 * The colors the Coloring tool of one window works on (ADR 0111): the slots of the tool tabs, the gradient, the slot
 * the picker column edits and the color the inspector shows. Outlives the tab. Used on the JavaFX thread.
 */
internal class ColoringState {
    val contrastText = ColorSlot(CONTRAST, "Text", CssColor.fromRgb8(0x1e, 0x29, 0x3b))
    val contrastBackground = ColorSlot(CONTRAST, "Background", CssColor.fromRgb8(0xff, 0xff, 0xff))
    val base = ColorSlot(HARMONIES, "Base", CssColor.fromRgb8(0x3b, 0x82, 0xf6))
    val mix1 = ColorSlot(MIX, "Color 1", CssColor.fromRgb8(0x3b, 0x82, 0xf6))
    val mix2 = ColorSlot(MIX, "Color 2", CssColor.fromRgb8(0xef, 0x44, 0x44))

    /** The color shown at an opacity over [flatBackground] (Mix). */
    val flatColor = ColorSlot(MIX, "Color", CssColor.fromRgb8(0x3b, 0x82, 0xf6))
    val flatBackground = ColorSlot(MIX, "Background", CssColor.fromRgb8(0xff, 0xff, 0xff))

    /** The gradient of the Gradient tab. */
    val gradient: ObjectProperty<Gradient> =
        SimpleObjectProperty(
            Gradient(
                GradientType.LINEAR,
                90.0,
                listOf(
                    GradientStop(CssColor.fromRgb8(0x3b, 0x82, 0xf6), 0.0),
                    GradientStop(CssColor.fromRgb8(0x8b, 0x5c, 0xf6), 1.0),
                ),
                MixSpace.OKLCH,
            ),
        )

    /** The index of the selected stop of [gradient]; kept within its stops. */
    val selectedStop: IntegerProperty = SimpleIntegerProperty(0)

    /**
     * The color of the selected stop of [gradient], both ways. Its label stays "Stop"; the stop's number is
     * [selectedStop] + 1.
     */
    val gradientStop = ColorSlot(GRADIENT, "Stop", gradient.value.stops[0].color)

    /** The slot the picker column edits. */
    val editing: ObjectProperty<ColorSlot> = SimpleObjectProperty(contrastText)

    /** The color of [editing]: reads and writes that slot's color, and follows a switch of the slot. */
    val editingColor: ObjectProperty<CssColor> = SimpleObjectProperty(contrastText.color.value)

    /**
     * The color the inspector shows: the last inspected color, or [editingColor] since it last changed. A switch of
     * [editing] resets it to the slot's color, and so does [edit] of the slot already being edited.
     */
    val inspected: ObjectProperty<CssColor> = SimpleObjectProperty(contrastText.color.value)

    /** The slot last edited in each tool tab, by the tab's title. */
    val remembered: MutableMap<String, ColorSlot> = mutableMapOf()

    private var syncingStop = false
    private var proxying = false
    private var slotSubscription: Subscription = Subscription.EMPTY

    init {
        gradient.subscribe { g ->
            val last = g.stops.lastIndex
            if (selectedStop.get() !in 0..last) selectedStop.set(selectedStop.get().coerceIn(0, last))
            stopFromGradient()
        }
        selectedStop.subscribe { _ -> stopFromGradient() }
        gradientStop.color.subscribe { _, c ->
            if (!syncingStop) syncStop { gradient.set(setStopColor(gradient.value, selectedStop.get(), c)) }
        }
        editing.subscribe { slot ->
            slotSubscription.unsubscribe()
            slotSubscription = slot.color.subscribe { c -> proxy { editingColor.set(c) } }
            inspected.set(slot.color.value)
        }
        editingColor.subscribe { _, c ->
            if (!proxying) proxy { editing.value.color.set(c) }
            inspected.set(c)
        }
    }

    /** Shows [color] in the inspector; no slot changes. */
    fun inspect(color: CssColor) = inspected.set(color)

    /**
     * Edits [slot] in the picker column and remembers it for the tool tab [tab]; the inspector shows the slot's color
     * again, also when [slot] was already being edited.
     */
    fun edit(
        slot: ColorSlot,
        tab: String = slot.tab,
    ) {
        remembered[tab] = slot
        editing.set(slot)
        inspected.set(slot.color.value)
    }

    /** Edits the slot last edited in the tool tab [tab], or its first slot; an unknown tab changes nothing. */
    fun editIn(tab: String) {
        (remembered[tab] ?: firstSlot(tab))?.let(editing::set)
    }

    /** The slot a tool tab edits before one of its slots is chosen; Vision shows the Contrast pair. */
    fun firstSlot(tab: String): ColorSlot? =
        when (tab) {
            CONTRAST, VISION -> contrastText
            HARMONIES -> base
            MIX -> mix1
            GRADIENT -> gradientStop
            else -> null
        }

    /**
     * Sets [gradient] to [g] and [selectedStop] to [selected] together, so [gradientStop] changes once, to the stop
     * finally selected (a moved stop changes its index with the gradient).
     */
    fun setGradient(
        g: Gradient,
        selected: Int = selectedStop.get(),
    ) {
        syncStop {
            gradient.set(g)
            selectedStop.set(selected.coerceIn(0, g.stops.lastIndex))
        }
        stopFromGradient()
    }

    private fun stopFromGradient() {
        if (syncingStop) return
        val stops = gradient.value.stops
        syncStop { gradientStop.color.set(stops[selectedStop.get().coerceIn(0, stops.lastIndex)].color) }
    }

    private fun syncStop(block: () -> Unit) {
        val before = syncingStop
        syncingStop = true
        try {
            block()
        } finally {
            syncingStop = before
        }
    }

    private fun proxy(block: () -> Unit) {
        val before = proxying
        proxying = true
        try {
            block()
        } finally {
            proxying = before
        }
    }

    companion object {
        const val CONTRAST = "Contrast"
        const val VISION = "Vision"
        const val HARMONIES = "Shades & Harmonies"
        const val MIX = "Mix"
        const val GRADIENT = "Gradient"
    }
}
