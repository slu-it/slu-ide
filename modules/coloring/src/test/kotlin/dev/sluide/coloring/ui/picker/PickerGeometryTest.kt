package dev.sluide.coloring.ui.picker

import kotlin.test.Test
import kotlin.test.assertEquals

class PickerGeometryTest {
    private val eps = 1e-9

    @Test
    fun `a square position maps to saturation and value and back`() {
        val sv = PickerGeometry.squareToSv(55.0, 45.0, 220.0, 180.0)
        assertEquals(25.0, sv.s, eps)
        assertEquals(75.0, sv.v, eps)

        val xy = PickerGeometry.svToSquare(25.0, 75.0, 220.0, 180.0)
        assertEquals(55.0, xy.x, eps)
        assertEquals(45.0, xy.y, eps)
    }

    @Test
    fun `a square position outside is clamped to the edge`() {
        assertEquals(Sv(100.0, 0.0), PickerGeometry.squareToSv(500.0, 500.0, 220.0, 180.0))
        assertEquals(Sv(0.0, 100.0), PickerGeometry.squareToSv(-5.0, -5.0, 220.0, 180.0))
        assertEquals(Xy(220.0, 0.0), PickerGeometry.svToSquare(150.0, 120.0, 220.0, 180.0))
    }

    @Test
    fun `the hue strip runs from 0 at the top to 360 at the bottom and clamps`() {
        assertEquals(180.0, PickerGeometry.stripToHue(90.0, 180.0), eps)
        assertEquals(0.0, PickerGeometry.stripToHue(-10.0, 180.0), eps)
        assertEquals(360.0, PickerGeometry.stripToHue(999.0, 180.0), eps)
        assertEquals(45.0, PickerGeometry.hueToStrip(90.0, 180.0), eps)
        assertEquals(180.0, PickerGeometry.hueToStrip(400.0, 180.0), eps)
    }

    @Test
    fun `the wheel angle is the hue counterclockwise from the right`() {
        assertEquals(Hs(0.0, 50.0), PickerGeometry.wheelToHs(50.0, 0.0, 100.0, 7.0))
        val up = PickerGeometry.wheelToHs(0.0, -100.0, 100.0, 7.0)
        assertEquals(90.0, up.h, eps)
        assertEquals(100.0, up.s, eps)
        assertEquals(180.0, PickerGeometry.wheelToHs(-10.0, 0.0, 100.0, 7.0).h, eps)
        assertEquals(270.0, PickerGeometry.wheelToHs(0.0, 10.0, 100.0, 7.0).h, eps)
    }

    @Test
    fun `outside the disc the wheel clamps to the rim and keeps the angle`() {
        val hs = PickerGeometry.wheelToHs(300.0, -300.0, 100.0, 7.0)
        assertEquals(45.0, hs.h, eps)
        assertEquals(100.0, hs.s, eps)
    }

    @Test
    fun `at the wheel center the hue is the given one and the saturation 0`() {
        assertEquals(Hs(123.0, 0.0), PickerGeometry.wheelToHs(0.0, 0.0, 100.0, 123.0))
    }

    @Test
    fun `a hue and saturation map to a wheel offset and back`() {
        val xy = PickerGeometry.hsToWheel(90.0, 50.0, 100.0)
        assertEquals(0.0, xy.x, 1e-9)
        assertEquals(-50.0, xy.y, 1e-9)
        val hs = PickerGeometry.wheelToHs(xy.x, xy.y, 100.0, 0.0)
        assertEquals(90.0, hs.h, 1e-6)
        assertEquals(50.0, hs.s, 1e-6)
    }

    @Test
    fun `the value slider and the alpha slider map and clamp`() {
        assertEquals(75.0, PickerGeometry.sliderToValue(50.0, 200.0), eps)
        assertEquals(100.0, PickerGeometry.sliderToValue(-1.0, 200.0), eps)
        assertEquals(0.0, PickerGeometry.sliderToValue(1000.0, 200.0), eps)
        assertEquals(50.0, PickerGeometry.valueToSlider(75.0, 200.0), eps)
        assertEquals(0.25, PickerGeometry.xToAlpha(55.0, 220.0), eps)
        assertEquals(1.0, PickerGeometry.xToAlpha(999.0, 220.0), eps)
        assertEquals(0.0, PickerGeometry.xToAlpha(-9.0, 220.0), eps)
        assertEquals(110.0, PickerGeometry.alphaToX(0.5, 220.0), eps)
    }
}
