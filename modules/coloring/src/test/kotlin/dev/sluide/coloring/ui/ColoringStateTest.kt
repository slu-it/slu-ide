package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.addStop
import dev.sluide.coloring.analysis.removeStop
import dev.sluide.coloring.color.CssColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The slots, the editing proxy, the inspected color and the gradient sync; properties work off the FX thread. */
class ColoringStateTest {
    private val state = ColoringState()
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val green = CssColor.fromRgb8(0, 255, 0)

    @Test
    fun `the editing color reads and writes the slot being edited`() {
        assertSame(state.contrastText, state.editing.value)
        assertEquals(state.contrastText.color.value, state.editingColor.value)

        state.editingColor.set(red)

        assertEquals(red, state.contrastText.color.value)
        assertEquals(CssColor.fromRgb8(0xff, 0xff, 0xff), state.contrastBackground.color.value)
    }

    @Test
    fun `the editing color follows a switch of the slot and changes of the slot made elsewhere`() {
        state.edit(state.mix2)
        assertEquals(CssColor.fromRgb8(0xef, 0x44, 0x44), state.editingColor.value)

        state.mix2.color.set(green)
        assertEquals(green, state.editingColor.value)

        // The slot left behind is no longer followed.
        state.contrastText.color.set(red)
        assertEquals(green, state.editingColor.value)
        state.editingColor.set(red)
        assertEquals(red, state.mix2.color.value)
    }

    @Test
    fun `the inspected color follows edits and slot switches until a color is inspected`() {
        state.editingColor.set(red)
        assertEquals(red, state.inspected.value)

        state.inspect(green)
        assertEquals(green, state.inspected.value)
        assertEquals(red, state.contrastText.color.value)

        state.edit(state.base)
        assertEquals(state.base.color.value, state.inspected.value)

        state.inspect(green)
        state.base.color.set(red)
        assertEquals(red, state.inspected.value)
    }

    @Test
    fun `choosing the slot already being edited shows its color in the inspector again, editIn does not`() {
        state.edit(state.base)
        state.inspect(red)

        state.editIn(ColoringState.HARMONIES)
        assertEquals(red, state.inspected.value)

        state.edit(state.base)
        assertEquals(state.base.color.value, state.inspected.value)
    }

    @Test
    fun `each tab remembers its slot and falls back to its first slot`() {
        state.edit(state.mix2)
        state.edit(state.contrastBackground)

        state.editIn(ColoringState.MIX)
        assertSame(state.mix2, state.editing.value)
        state.editIn(ColoringState.CONTRAST)
        assertSame(state.contrastBackground, state.editing.value)
        state.editIn(ColoringState.HARMONIES)
        assertSame(state.base, state.editing.value)
        state.editIn(ColoringState.GRADIENT)
        assertSame(state.gradientStop, state.editing.value)
    }

    @Test
    fun `Vision edits the Contrast slots and remembers its own choice`() {
        state.edit(state.contrastBackground, ColoringState.CONTRAST)

        state.editIn(ColoringState.VISION)
        assertSame(state.contrastText, state.editing.value)

        state.edit(state.contrastBackground, ColoringState.VISION)
        state.edit(state.contrastText, ColoringState.CONTRAST)
        state.editIn(ColoringState.VISION)
        assertSame(state.contrastBackground, state.editing.value)
    }

    @Test
    fun `the gradient stop slot follows the gradient and the selected stop`() {
        assertEquals(CssColor.fromRgb8(0x3b, 0x82, 0xf6), state.gradientStop.color.value)

        state.selectedStop.set(1)
        assertEquals(CssColor.fromRgb8(0x8b, 0x5c, 0xf6), state.gradientStop.color.value)

        state.gradientStop.color.set(red)
        assertEquals(
            red,
            state.gradient.value.stops[1]
                .color,
        )
        assertEquals(
            CssColor.fromRgb8(0x3b, 0x82, 0xf6),
            state.gradient.value.stops[0]
                .color,
        )
    }

    @Test
    fun `the selected stop stays within the stops when one is removed`() {
        val (three, added) = addStop(state.gradient.value)
        state.setGradient(three, 2)
        assertEquals(2, state.selectedStop.get())
        assertEquals(three.stops[2].color, state.gradientStop.color.value)

        state.gradient.set(removeStop(three, added))

        assertEquals(1, state.selectedStop.get())
        assertEquals(
            state.gradient.value.stops[1]
                .color,
            state.gradientStop.color.value,
        )
    }

    @Test
    fun `editing the stop slot through the picker column writes the gradient`() {
        state.edit(state.gradientStop)

        state.editingColor.set(green)

        assertEquals(
            green,
            state.gradient.value.stops[0]
                .color,
        )
        assertEquals(green, state.inspected.value)
    }
}
