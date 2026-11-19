package dev.sluide.settings

import dev.sluide.testsupport.LogCapture
import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.Level
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
            Theme.DARK,
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
    fun `a settings folder that cannot be created fails the update, with the file and the reason`() {
        dir.resolve("config").writeText("a file, not a folder")
        val blocker = dir.resolve("blocker").apply { writeText("a file, not a folder") }
        val deeper = blocker.resolve("sub/settings.properties")

        val failure = assertFailsWith<IOException> { SettingsStore(file).update(mapOf("theme" to "DARK")) }
        // createDirectories under a regular file: FileSystemException with the reason "Not a directory" (checked on the JDK)
        val failureDeeper = assertFailsWith<IOException> { SettingsStore(deeper).update(mapOf("theme" to "DARK")) }

        assertEquals("Cannot save settings to $file: FileAlreadyExistsException", failure.message)
        assertEquals("Cannot save settings to $deeper: FileSystemException: Not a directory", failureDeeper.message)
    }

    @Test
    fun `a read-only settings folder fails the update and keeps the file`() {
        file.parent.createDirectories()
        file.writeText("theme=DARK\n")
        file.parent.setPosixFilePermissions(PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue(!Files.isWritable(file.parent), "the folder is writable anyway (running as root?)")

            val failure = assertFailsWith<IOException> { SettingsStore(file).update(mapOf("theme" to "LIGHT")) }

            assertEquals("Cannot save settings to $file: AccessDeniedException", failure.message)
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
        assertFailsWith<IOException> { themes.apply(Theme.LIGHT) }

        assertEquals(TestFonts.defaults[FontArea.EDITOR], fonts.font(FontArea.EDITOR).get())
        assertEquals(Theme.DARK, themes.theme.get())
    }

    @Test
    fun `the default store keeps its settings in the user's Thoreau folder`() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".sluide", "settings.properties"),
            SettingsStore().file,
        )
    }

    @Test
    fun `a settings file that is a folder cannot be read, is not replaced and is logged`() {
        file.createDirectories()

        LogCapture(SettingsStore::class.java.name).use { log ->
            assertEquals(emptyMap(), SettingsStore(file).load())
            val failure = assertFailsWith<IOException> { SettingsStore(file).update(mapOf("a" to "1")) }

            assertEquals("Cannot read settings from $file, so it is not replaced", failure.message)
            assertEquals(List(2) { "Cannot read settings from $file" }, log.messages())
        }
        assertTrue(Files.isDirectory(file))
    }

    @Test
    fun `a corrupt file is logged as unreadable`() {
        writeCorruptFile()

        LogCapture(SettingsStore::class.java.name).use { log ->
            SettingsStore(file).load()

            assertEquals(listOf(Level.WARNING to "Cannot read settings from $file"), log.lines.toList())
            assertTrue(log.thrown.single() is IllegalArgumentException)
        }
    }
}
