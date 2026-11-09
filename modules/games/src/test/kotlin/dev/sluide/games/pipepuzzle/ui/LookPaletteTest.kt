package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LookPaletteTest {
    @Test
    fun `every look has its own palette`() {
        assertEquals(
            Look.entries.size,
            Look.entries
                .map { LookPalette.of(it) }
                .toSet()
                .size,
        )
    }

    @ParameterizedTest
    @EnumSource(Look::class)
    fun `flow differs from the empty conduit and from the tile`(look: Look) {
        val p = LookPalette.of(look)
        assertNotEquals(p.conduitBody, p.flow)
        assertNotEquals(p.conduitOutline, p.flow)
        assertNotEquals(p.tileBase, p.flow)
        assertNotEquals(p.tileBase, p.fixedBase)
        assertNotEquals(p.flow, p.cursor)
    }
}
