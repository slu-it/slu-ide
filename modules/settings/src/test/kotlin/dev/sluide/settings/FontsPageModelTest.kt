package dev.sluide.settings

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class FontsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val defaults = TestFonts.defaults
    private val settings by lazy { TestFonts.settings(dir.resolve("settings.properties")) }
    private val model by lazy { FontsPageModel(settings) }

    @Test
    fun `edits stay drafts until applied`() {
        val serif = FontSpec("Serif", 18.0, FontStyle.ITALIC)

        model.draft(FontArea.EDITOR).set(serif)

        assertEquals(mapOf(FontArea.EDITOR to serif), model.changes)
        assertEquals(defaults[FontArea.EDITOR], settings.font(FontArea.EDITOR).get())

        model.apply()

        assertEquals(serif, settings.font(FontArea.EDITOR).get())
        assertEquals(emptyMap(), model.changes)
    }

    @Test
    fun `loading drops the edits, like cancelling the dialog`() {
        model.draft(FontArea.TERMINAL).set(FontSpec("Serif", 20.0))

        model.load()

        assertEquals(defaults[FontArea.TERMINAL], model.draft(FontArea.TERMINAL).get())
        assertEquals(emptyMap(), model.changes)
    }

    @Test
    fun `restoring the defaults of an area is an edit like any other`() {
        settings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 16.0)))
        model.load()

        model.restoreDefaults(FontArea.EXPLORER)

        assertEquals(mapOf(FontArea.EXPLORER to defaults.getValue(FontArea.EXPLORER)), model.changes)
        assertEquals(FontSpec("Serif", 16.0), settings.font(FontArea.EXPLORER).get())
    }
}
