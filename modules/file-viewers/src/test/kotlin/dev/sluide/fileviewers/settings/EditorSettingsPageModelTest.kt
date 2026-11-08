package dev.sluide.fileviewers.settings

import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = SettingsStore(dir.resolve("settings.properties"))

    @Test
    fun `the draft shows the setting and only apply changes it, and stores it`() {
        val settings = LibraryCodeSettings(store())
        val model = EditorSettingsPageModel(settings)
        assertTrue(model.libraryReadOnly.get(), "on by default")

        model.libraryReadOnly.set(false)
        assertTrue(settings.readOnly.get(), "a draft changes nothing")
        model.apply()
        assertFalse(settings.readOnly.get())
        assertFalse(LibraryCodeSettings(store()).readOnly.get(), "stored")

        model.libraryReadOnly.set(true)
        model.load()
        assertFalse(model.libraryReadOnly.get(), "load drops the draft")
    }
}
