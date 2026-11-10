package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.minigolf.Phase
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldCanvasTest {
    private val layout = FieldLayout(320, 240)

    @Test
    fun `an area of the field's size maps one to one`() {
        val point = fieldPoint(layout, 320.0, 240.0, 100.0, 50.0, 1.0)

        assertEquals(100.0, point.x)
        assertEquals(50.0, point.y)
    }

    @Test
    fun `a wider area is centred and scaled by its height`() {
        // Scale 2 (height 480): the field spans x 130 to 770 of 900.
        val corner = fieldPoint(layout, 900.0, 480.0, 130.0, 0.0, 1.0)
        val middle = fieldPoint(layout, 900.0, 480.0, 450.0, 240.0, 1.0)

        assertEquals(0.0, corner.x, 1e-9)
        assertEquals(0.0, corner.y, 1e-9)
        assertEquals(160.0, middle.x, 1e-9)
        assertEquals(120.0, middle.y, 1e-9)
    }

    @Test
    fun `outside the field the point lies outside it`() {
        val point = fieldPoint(layout, 900.0, 480.0, 0.0, 500.0, 1.0)

        assertEquals(-65.0, point.x, 1e-9)
        assertEquals(250.0, point.y, 1e-9)
    }

    @Test
    fun `the HUD shows the next stroke while aiming and the current one otherwise`() {
        assertEquals(1, hudStroke(Phase.AIMING, 0, false))
        assertEquals(3, hudStroke(Phase.AIMING, 2, false))
        assertEquals(2, hudStroke(Phase.ROLLING, 2, true))
        assertEquals(1, hudStroke(Phase.CARD, 0, false))
    }

    @Test
    fun `a push roll keeps the stroke of the next shot`() {
        assertEquals(3, hudStroke(Phase.ROLLING, 2, false))
    }

    @Test
    fun `at zoom 0_5 the field is centred at half the scale`() {
        // Scale 2 halved is 1: the field spans x 290 to 610 and y 120 to 360 of 900 x 480.
        val corner = fieldPoint(layout, 900.0, 480.0, 290.0, 120.0, 0.5)
        val inside = fieldPoint(layout, 900.0, 480.0, 390.0, 170.0, 0.5)

        assertEquals(0.0, corner.x, 1e-9)
        assertEquals(0.0, corner.y, 1e-9)
        assertEquals(100.0, inside.x, 1e-9)
        assertEquals(50.0, inside.y, 1e-9)
    }
}
