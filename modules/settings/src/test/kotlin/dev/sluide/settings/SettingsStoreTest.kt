package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
