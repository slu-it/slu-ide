package dev.sluide.settings

import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import org.springframework.stereotype.Component

private const val TRACKPAD_KEY = "terminal.scroll.trackpad"
private const val WHEEL_KEY = "terminal.scroll.wheel"

/**
 * How fast terminals scroll, in percent of the default speed, separately for trackpads and mouse wheels. Terminals
 * follow [trackpad] and [wheel]; the settings dialog changes them with [apply], which also stores them in the
 * [SettingsStore]. Speeds are multiples of [STEP] in [SPEEDS]; stored values that are not fall back to [DEFAULT].
 */
@Component
class TerminalScrollSettings(
    private val store: SettingsStore,
) {
    private val trackpadWrapper: ReadOnlyIntegerWrapper
    private val wheelWrapper: ReadOnlyIntegerWrapper

    init {
        val stored = store.load()
        trackpadWrapper = ReadOnlyIntegerWrapper(read(stored, TRACKPAD_KEY))
        wheelWrapper = ReadOnlyIntegerWrapper(read(stored, WHEEL_KEY))
    }

    /** The scroll speed of trackpads in percent; 100 is the default speed. */
    val trackpad: ReadOnlyIntegerProperty = trackpadWrapper.readOnlyProperty

    /** The scroll speed of mouse wheels in percent; 100 is the default speed. */
    val wheel: ReadOnlyIntegerProperty = wheelWrapper.readOnlyProperty

    /**
     * Stores the speeds and then sets them. Must be called on the FX thread, which waits for the (small) settings
     * file to be written.
     *
     * @throws IllegalArgumentException if a speed is outside [SPEEDS] or not a multiple of [STEP].
     * @throws java.io.IOException if the speeds cannot be stored; they are then not changed either.
     */
    fun apply(
        trackpad: Int,
        wheel: Int,
    ) {
        require(isUsable(trackpad) && isUsable(wheel)) {
            "Speeds must be multiples of $STEP in $SPEEDS: $trackpad, $wheel"
        }
        if (trackpad == trackpadWrapper.get() && wheel == wheelWrapper.get()) return
        store.update(mapOf(TRACKPAD_KEY to trackpad.toString(), WHEEL_KEY to wheel.toString()))
        trackpadWrapper.set(trackpad)
        wheelWrapper.set(wheel)
    }

    internal companion object {
        /** The range of the speeds that can be chosen, in percent; stored speeds outside are unusable. */
        val SPEEDS = 10..200

        /** The speeds that can be chosen are whole multiples of this, in percent. */
        const val STEP = 5

        const val DEFAULT = 100

        fun read(
            stored: Map<String, String>,
            key: String,
        ): Int = stored[key]?.trim()?.toIntOrNull()?.takeIf(::isUsable) ?: DEFAULT

        private fun isUsable(speed: Int) = speed in SPEEDS && speed % STEP == 0
    }
}
