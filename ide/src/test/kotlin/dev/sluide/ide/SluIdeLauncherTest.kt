package dev.sluide.ide

import kotlin.test.Test
import kotlin.test.assertEquals

class SluIdeLauncherTest {
    @Test
    fun `allows the Host header, keeping headers allowed already`() {
        assertEquals("host", withHost(null))
        assertEquals("host", withHost(" "))
        assertEquals("upgrade,host", withHost("upgrade"))
        assertEquals("Host,upgrade", withHost("Host,upgrade"))
        assertEquals("upgrade, host", withHost("upgrade, host"))
    }
}
