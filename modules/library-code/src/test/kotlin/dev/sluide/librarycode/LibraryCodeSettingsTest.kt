package dev.sluide.librarycode

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryCodeSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("settings.properties")
    private val store by lazy { SettingsStore(file) }

    @Test
    fun `library code opens read-only by default, a change is stored for the next start`() {
        val settings = LibraryCodeSettings(store)
        assertTrue(settings.readOnly.get())
        assertTrue(settings.currentReadOnly())

        settings.readOnly.set(false)

        assertFalse(settings.currentReadOnly())
        assertEquals("false", store.load()[READ_ONLY_KEY])
        assertFalse(LibraryCodeSettings(store).readOnly.get())
        assertFalse(LibraryCodeSettings(store).currentReadOnly())
    }

    @Test
    fun `currentReadOnly is up to date for listeners of the property`() {
        val settings = LibraryCodeSettings(store)
        val seen = mutableListOf<Boolean>()
        settings.readOnly.addListener { _, _, _ -> seen += settings.currentReadOnly() }

        settings.readOnly.set(false)
        settings.readOnly.set(true)

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `the setting is read on first use, not on creation, a value that is no boolean counts as the default`() {
        val settings = LibraryCodeSettings(store)
        val other = LibraryCodeSettings(store)
        file.writeText("$READ_ONLY_KEY=false\n")
        assertFalse(settings.currentReadOnly())
        assertFalse(other.readOnly.get())

        file.writeText("$READ_ONLY_KEY=maybe\n")
        assertTrue(LibraryCodeSettings(store).readOnly.get())
    }
}
