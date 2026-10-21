package dev.sluide.terminal.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalScrollSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("config/settings.properties")

    private fun settings() = TerminalScrollSettings(SettingsStore(file))

    private fun store(text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    @Test
    fun `without a settings file both speeds are 100 percent`() {
        val settings = settings()

        assertEquals(100, settings.trackpad.get())
        assertEquals(100, settings.wheel.get())
    }

    @Test
    fun `the bounds of the range are usable`() {
        store("terminal.scroll.trackpad=10\nterminal.scroll.wheel=200\n")

        val settings = settings()

        assertEquals(10, settings.trackpad.get())
        assertEquals(200, settings.wheel.get())
    }

    @Test
    fun `unusable stored speeds, also those between the steps of 5, fall back to 100 percent`() {
        for (value in listOf("5", "250", "abc", "", "50.5", "37")) {
            store("terminal.scroll.trackpad=$value\nterminal.scroll.wheel=$value\n")

            val settings = settings()

            assertEquals(100, settings.trackpad.get(), value)
            assertEquals(100, settings.wheel.get(), value)
        }
    }

    @Test
    fun `applied speeds notify listeners and are stored for the next start`() {
        val settings = settings()
        val seen = mutableListOf<Number>()
        settings.trackpad.subscribe { speed -> seen += speed }

        settings.apply(trackpad = 35, wheel = 150)

        assertEquals(listOf<Number>(100, 35), seen)
        assertEquals(150, settings.wheel.get())
        val text = file.readText()
        assertTrue("terminal.scroll.trackpad=35" in text && "terminal.scroll.wheel=150" in text, text)
        assertEquals(35, settings().trackpad.get())
        assertEquals(150, settings().wheel.get())
    }

    @Test
    fun `speeds that cannot be stored are not set either`() {
        // A directory instead of the settings file cannot be read, so it is not replaced.
        file.createDirectories()
        val settings = settings()

        assertFailsWith<IOException> { settings.apply(trackpad = 50, wheel = 50) }

        assertEquals(100, settings.trackpad.get())
        assertEquals(100, settings.wheel.get())
    }

    @Test
    fun `unchanged speeds are not written`() {
        val settings = settings()

        settings.apply(trackpad = 100, wheel = 100)

        assertFalse(file.exists())
    }

    @Test
    fun `speeds outside the range or between the steps are rejected`() {
        val settings = settings()

        assertFailsWith<IllegalArgumentException> { settings.apply(trackpad = 5, wheel = 100) }
        assertFailsWith<IllegalArgumentException> { settings.apply(trackpad = 100, wheel = 201) }
        assertFailsWith<IllegalArgumentException> { settings.apply(trackpad = 37, wheel = 100) }
        assertFalse(file.exists())
    }
}
