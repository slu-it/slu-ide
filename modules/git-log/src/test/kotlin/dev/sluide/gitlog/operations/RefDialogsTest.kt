package dev.sluide.gitlog.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefDialogsTest {
    @Test
    fun `origin is checked at first, else the first remote`() {
        assertEquals("origin", defaultTagRemote(listOf("upstream", "origin", "fork")))
        assertEquals("upstream", defaultTagRemote(listOf("upstream", "fork")))
        assertNull(defaultTagRemote(emptyList()))
    }
}
