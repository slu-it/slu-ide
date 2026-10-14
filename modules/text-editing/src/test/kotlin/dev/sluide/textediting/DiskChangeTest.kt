package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals

class DiskChangeTest {
    @Test
    fun `ignores the saved text coming back, also after more typing`() {
        assertEquals(DiskChange.IGNORE, DiskChange.decide("saved", saved = "saved", buffer = "saved", dirty = false))
        assertEquals(DiskChange.IGNORE, DiskChange.decide("saved", saved = "saved", buffer = "typed", dirty = true))
    }

    @Test
    fun `reloads a new text into an editor without unsaved changes`() {
        assertEquals(DiskChange.RELOAD, DiskChange.decide("new", saved = "old", buffer = "old", dirty = false))
    }

    @Test
    fun `asks when a new text meets unsaved changes`() {
        assertEquals(DiskChange.CONFLICT, DiskChange.decide("new", saved = "old", buffer = "typed", dirty = true))
    }

    @Test
    fun `a new text the editor holds already is taken as saved`() {
        assertEquals(DiskChange.RELOAD, DiskChange.decide("same", saved = "old", buffer = "same", dirty = true))
        assertEquals(DiskChange.RELOAD, DiskChange.decide("a\r\nb", saved = "old", buffer = "a\nb", dirty = true))
    }

    @Test
    fun `line endings are no change`() {
        assertEquals(DiskChange.IGNORE, DiskChange.decide("a\r\nb\r\n", saved = "a\nb\n", buffer = "x", dirty = true))
        assertEquals(DiskChange.IGNORE, DiskChange.decide("a\nb", saved = "a\rb", buffer = "a\nb", dirty = false))
    }
}
