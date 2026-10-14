package dev.sluide.settings

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FontSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val defaults = TestFonts.defaults
    private val file get() = dir.resolve("config/settings.properties")

    private fun settings() = TestFonts.settings(file)

    @Test
    fun `css sets family, size, weight and style`() {
        assertEquals(
            "-fx-font-family: \"JetBrains Mono\"; -fx-font-size: 14px; -fx-font-weight: bold; -fx-font-style: italic;",
            FontSpec("JetBrains Mono", 14.0, FontStyle.BOLD_ITALIC).toCss(),
        )
        assertEquals(
            "-fx-font-family: \"Serif\"; -fx-font-size: 12.5px; -fx-font-weight: normal; -fx-font-style: normal;",
            FontSpec("Serif", 12.5).toCss(),
        )
    }

    @Test
    fun `fonts need a family and a positive size`() {
        assertFailsWith<IllegalArgumentException> { FontSpec(" ", 12.0) }
        assertFailsWith<IllegalArgumentException> { FontSpec("Serif", 0.0) }
    }

    @Test
    fun `without a settings file the defaults are used`() {
        val settings = settings()

        FontArea.entries.forEach { assertEquals(defaults[it], settings.font(it).get()) }
    }

    @Test
    fun `applied fonts notify listeners and are stored for the next start`() {
        val settings = settings()
        val seen = mutableListOf<FontSpec>()
        settings.font(FontArea.TERMINAL).subscribe { spec -> seen += spec }
        val terminal = FontSpec("DejaVu Sans Mono", 16.0, FontStyle.BOLD)

        settings.apply(mapOf(FontArea.TERMINAL to terminal))

        assertEquals(listOf(defaults.getValue(FontArea.TERMINAL), terminal), seen)
        assertEquals(terminal, settings().font(FontArea.TERMINAL).get())
        assertEquals(defaults[FontArea.EDITOR], settings().font(FontArea.EDITOR).get())
        assertTrue("font.terminal.style=BOLD" in file.readText())
    }

    @Test
    fun `values of other settings in the file are kept`() {
        file.parent.toFile().mkdirs()
        file.writeText("other.setting=42\n")

        settings().apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 11.0)))

        assertEquals("42", SettingsStore(file).load()["other.setting"])
    }

    @Test
    fun `unusable stored values fall back to the defaults`() {
        file.parent.toFile().mkdirs()
        file.writeText(
            """
            font.explorer.family=Serif
            font.explorer.size=not a number
            font.explorer.style=REGULAR
            font.editor.family=Serif
            font.editor.size=12
            font.editor.style=WAVY
            font.terminal.family=Serif
            font.terminal.size=15
            font.terminal.style=ITALIC
            """.trimIndent(),
        )

        val settings = settings()

        assertEquals(defaults[FontArea.EXPLORER], settings.font(FontArea.EXPLORER).get())
        assertEquals(defaults[FontArea.EDITOR], settings.font(FontArea.EDITOR).get())
        assertEquals(FontSpec("Serif", 15.0, FontStyle.ITALIC), settings.font(FontArea.TERMINAL).get())
    }
}
