package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("config/settings.properties")
    private val system = SimpleObjectProperty(ColorScheme.LIGHT)

    private fun settings() = ThemeSettings(SettingsStore(file), system)

    @Test
    fun `without a settings file the system theme is used`() {
        assertEquals(Theme.SYSTEM, settings().theme.get())
    }

    @Test
    fun `an unknown stored theme falls back to the system theme`() {
        file.parent.createDirectories()
        file.writeText("theme=PURPLE\n")

        assertEquals(Theme.SYSTEM, settings().theme.get())
    }

    @Test
    fun `the system theme follows the operating system`() {
        val settings = settings()
        assertEquals(ColorScheme.LIGHT, settings.colorScheme.get())

        system.set(ColorScheme.DARK)

        assertEquals(ColorScheme.DARK, settings.colorScheme.get())
    }

    @Test
    fun `light and dark ignore the operating system`() {
        val settings = settings()

        settings.apply(Theme.DARK)
        assertEquals(ColorScheme.DARK, settings.colorScheme.get())

        settings.apply(Theme.LIGHT)
        system.set(ColorScheme.DARK)
        assertEquals(ColorScheme.LIGHT, settings.colorScheme.get())
    }

    @Test
    fun `an applied theme notifies listeners and is stored for the next start`() {
        val settings = settings()
        val seen = mutableListOf<ColorScheme>()
        settings.colorScheme.subscribe { scheme -> seen += scheme }

        settings.apply(Theme.DARK)

        assertEquals(listOf(ColorScheme.LIGHT, ColorScheme.DARK), seen)
        assertTrue("theme=DARK" in file.readText())
        assertEquals(Theme.DARK, settings().theme.get())
    }
}
