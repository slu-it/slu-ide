package dev.sluide.coloring.ui.picker

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import kotlin.test.Test
import kotlin.test.assertEquals

class PickerModelTest {
    private val emitted = mutableListOf<CssColor>()
    private var changes = 0
    private val model =
        PickerModel(CssColor.srgb(1.0, 0.0, 0.0)) { emitted += it }.also { it.onChange = { changes++ } }

    @Test
    fun `starts from the color read as HSV`() {
        assertEquals(listOf(0.0, 100.0, 100.0, 1.0), listOf(model.h, model.s, model.v, model.a))
    }

    @Test
    fun `user input emits an HSV color with the other values kept`() {
        model.input(h = 200.0, s = 50.0, v = 40.0)
        model.input(a = 0.5)

        assertEquals(CssColor(ColorSpace.HSV, 200.0, 50.0, 40.0, 0.5), emitted.last())
        assertEquals(2, emitted.size)
    }

    @Test
    fun `an outside change is read back`() {
        model.external(CssColor.srgb(0.0, 0.0, 1.0, 0.25))

        assertEquals(240.0, model.h, 1e-6)
        assertEquals(100.0, model.s, 1e-6)
        assertEquals(100.0, model.v, 1e-6)
        assertEquals(0.25, model.a, 1e-9)
        assertEquals(1, changes)
        assertEquals(0, emitted.size)
    }

    @Test
    fun `an out of gamut color is mapped into sRGB first`() {
        model.external(CssColor(ColorSpace.OKLCH, 0.7, 0.35, 150.0))

        assertEquals(true, model.s in 0.0..100.0 && model.v in 0.0..100.0)
    }

    @Test
    fun `the hue is kept when the outside color is gray`() {
        model.input(h = 200.0, s = 50.0, v = 50.0)
        model.external(CssColor.srgb(0.5, 0.5, 0.5))

        assertEquals(200.0, model.h, 1e-9)
        assertEquals(0.0, model.s, 1e-6)
    }

    @Test
    fun `the hue and saturation are kept when the outside color is black`() {
        model.input(h = 200.0, s = 50.0, v = 50.0)
        model.external(CssColor.srgb(0.0, 0.0, 0.0))

        assertEquals(200.0, model.h, 1e-9)
        assertEquals(50.0, model.s, 1e-9)
        assertEquals(0.0, model.v, 1e-9)
    }

    @Test
    fun `the saturation at 0 keeps the hue for the user's drag back`() {
        model.input(h = 200.0, s = 0.0, v = 50.0)
        model.input(s = 30.0)

        assertEquals(CssColor(ColorSpace.HSV, 200.0, 30.0, 50.0), emitted.last())
    }

    @Test
    fun `the color it emitted coming back is no outside change`() {
        model.input(h = 200.0, s = 0.0, v = 50.0)
        val before = changes

        model.external(emitted.last())

        assertEquals(before, changes)
        assertEquals(200.0, model.h, 1e-9)
    }
}
