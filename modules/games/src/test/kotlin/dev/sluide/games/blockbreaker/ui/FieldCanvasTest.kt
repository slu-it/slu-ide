package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BlockBreakerGame
import dev.sluide.games.common.ui.FieldLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldCanvasTest {
    private val layout = FieldLayout(BlockBreakerGame.WIDTH, BlockBreakerGame.HEIGHT)

    @Test
    fun `an area of the field's size maps one to one`() {
        assertEquals(0.0, fieldX(layout, 320.0, 240.0, 0.0, 1.0))
        assertEquals(160.0, fieldX(layout, 320.0, 240.0, 160.0, 1.0))
    }

    @Test
    fun `a wider area is centred and scaled by its height`() {
        // Scale 2 (height 480), the field spans x 130 to 770 of 900.
        assertEquals(0.0, fieldX(layout, 900.0, 480.0, 130.0, 1.0), 1e-9)
        assertEquals(160.0, fieldX(layout, 900.0, 480.0, 450.0, 1.0), 1e-9)
        assertEquals(320.0, fieldX(layout, 900.0, 480.0, 770.0, 1.0), 1e-9)
    }

    @Test
    fun `outside the field the value lies outside 0 to 320`() {
        assertEquals(-65.0, fieldX(layout, 900.0, 480.0, 0.0, 1.0), 1e-9)
    }

    @Test
    fun `at zoom 0_5 the field is centred at half the scale`() {
        // Scale 2 halved is 1: the field spans x 290 to 610 of 900.
        assertEquals(0.0, fieldX(layout, 900.0, 480.0, 290.0, 0.5), 1e-9)
        assertEquals(100.0, fieldX(layout, 900.0, 480.0, 390.0, 0.5), 1e-9)
        assertEquals(320.0, fieldX(layout, 900.0, 480.0, 610.0, 0.5), 1e-9)
    }
}
