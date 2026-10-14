package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsStoreTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("config/settings.properties")

    /** `Properties.load` throws an `IllegalArgumentException` for this, not an `IOException`. */
    private val corrupt = "theme=DARK\nfont.editor.family=\\uZZZZ\n"

    private fun writeCorruptFile() {
        file.parent.createDirectories()
        file.writeText(corrupt)
    }

    @Test
    fun `values are stored in addition to the stored ones`() {
        val store = SettingsStore(file)

        store.update(mapOf("a" to "1", "b" to "2"))
        store.update(mapOf("b" to "3"))

        assertEquals(mapOf("a" to "1", "b" to "3"), SettingsStore(file).load())
    }

    @Test
    fun `a corrupt file means no settings, the IDE still starts with the defaults`() {
        writeCorruptFile()

        assertEquals(emptyMap(), SettingsStore(file).load())
        assertEquals(TestFonts.defaults[FontArea.EDITOR], TestFonts.settings(file).font(FontArea.EDITOR).get())
        assertEquals(
            Theme.SYSTEM,
            ThemeSettings(SettingsStore(file), { SimpleObjectProperty(ColorScheme.LIGHT) }).theme.get(),
        )
    }

    @Test
    fun `a file that cannot be read is not replaced, so its other settings are not lost`() {
        writeCorruptFile()

        assertFailsWith<IOException> { SettingsStore(file).update(mapOf("theme" to "LIGHT")) }

        assertEquals(corrupt, file.readText())
    }

    @Test
    fun `a settings folder that cannot be created fails the update`() {
        dir.resolve("config").writeText("a file, not a folder")

        val failure = assertFailsWith<IOException> { SettingsStore(file).update(mapOf("theme" to "DARK")) }

        assertTrue(file.toString() in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a read-only settings folder fails the update and keeps the file`() {
        file.parent.createDirectories()
        file.writeText("theme=DARK\n")
        file.parent.setPosixFilePermissions(PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue(!Files.isWritable(file.parent), "the folder is writable anyway (running as root?)")

            assertFailsWith<IOException> { SettingsStore(file).update(mapOf("theme" to "LIGHT")) }

            assertEquals("theme=DARK\n", file.readText())
        } finally {
            file.parent.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `fonts and themes that cannot be stored are not applied`() {
        dir.resolve("config").writeText("a file, not a folder")
        val fonts = TestFonts.settings(file)
        val themes = ThemeSettings(SettingsStore(file), { SimpleObjectProperty(ColorScheme.LIGHT) })

        assertFailsWith<IOException> { fonts.apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 20.0))) }
        assertFailsWith<IOException> { themes.apply(Theme.DARK) }

        assertEquals(TestFonts.defaults[FontArea.EDITOR], fonts.font(FontArea.EDITOR).get())
        assertEquals(Theme.SYSTEM, themes.theme.get())
    }
}
