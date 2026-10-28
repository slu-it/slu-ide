package dev.sluide.ide.todos

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowTodosTest {
    @Test
    fun `the divider is the panel's width plus half a divider from the right edge`() {
        assertEquals(0.675, todoDividerPosition(1000.0, 320.0, 10.0), 1e-9)
        assertEquals(0.75, todoDividerPosition(800.0, 195.0, 10.0), 1e-9)
    }

    @Test
    fun `an unknown total uses a 1000 px wide split`() {
        assertEquals(todoDividerPosition(1000.0, 320.0, 8.0), todoDividerPosition(0.0, 320.0, 8.0), 1e-9)
    }

    @Test
    fun `a panel wider than the split stays within 0 and 1`() {
        assertEquals(0.0, todoDividerPosition(200.0, 500.0, 8.0), 0.0)
    }
}
