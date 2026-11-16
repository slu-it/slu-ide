package dev.sluide.settings

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectSettingsOpenersTest {
    private val openers = ProjectSettingsOpeners()
    private val folder = Path.of("/work/project")

    @Test
    fun `show calls the registered opener with the path and says so`() {
        val shown = mutableListOf<List<String>?>()
        openers.register(folder) { shown += it }

        assertTrue(openers.show(folder, listOf("Java")))
        assertTrue(openers.show(folder, null))

        assertEquals(listOf(listOf("Java"), null), shown)
    }

    @Test
    fun `show is false for a folder nobody registered`() {
        openers.register(folder) {}

        assertFalse(openers.show(Path.of("/work/other"), null))
    }

    @Test
    fun `the folder is compared normalized`() {
        val shown = mutableListOf<List<String>?>()
        openers.register(Path.of("/work/x/../project")) { shown += it }

        assertTrue(openers.show(folder, null))
        assertEquals(1, shown.size)
    }

    @Test
    fun `the latest registration of a folder wins and closing it brings the earlier one back`() {
        val calls = mutableListOf<String>()
        openers.register(folder) { calls += "first" }
        val second = openers.register(folder) { calls += "second" }

        openers.show(folder, null)
        second.close()
        openers.show(folder, null)

        assertEquals(listOf("second", "first"), calls)
    }

    @Test
    fun `closing the only registration makes show false, also when closed twice`() {
        val registration = openers.register(folder) {}

        registration.close()
        registration.close()

        assertFalse(openers.show(folder, null))
    }

    @Test
    fun `closing an older registration keeps the latest one`() {
        val calls = mutableListOf<String>()
        val first = openers.register(folder) { calls += "first" }
        openers.register(folder) { calls += "second" }

        first.close()
        openers.show(folder, null)

        assertEquals(listOf("second"), calls)
    }
}
