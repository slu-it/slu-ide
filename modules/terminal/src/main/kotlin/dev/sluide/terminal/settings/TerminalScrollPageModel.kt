package dev.sluide.terminal.settings

import javafx.beans.property.IntegerProperty
import javafx.beans.property.SimpleIntegerProperty
import kotlin.math.roundToInt

/** The edit on the Terminal → Scrolling page: draft speeds, applied to [settings] only by [apply]. */
internal class TerminalScrollPageModel(
    private val settings: TerminalScrollSettings,
) {
    val trackpad: IntegerProperty = SimpleIntegerProperty(settings.trackpad.get())
    val wheel: IntegerProperty = SimpleIntegerProperty(settings.wheel.get())

    /** Drops the edit: the drafts show the current speeds again. */
    fun load() {
        trackpad.set(settings.trackpad.get())
        wheel.set(settings.wheel.get())
    }

    fun apply() = settings.apply(trackpad.get(), wheel.get())

    companion object {
        /** The offered speed nearest to [value]: a multiple of [TerminalScrollSettings.STEP] in its range. */
        fun snap(value: Double): Int {
            val step = TerminalScrollSettings.STEP
            return ((value / step).roundToInt() * step).coerceIn(TerminalScrollSettings.SPEEDS)
        }
    }
}
