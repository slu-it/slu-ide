package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.control.Label
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPagesTest {
    @TempDir
    lateinit var dir: Path

    private class Page(
        override val path: List<String>,
    ) : SettingsPage {
        override val node get() = Label()

        override fun load() {}

        override fun apply() {}
    }

    @Test
    fun `pages are ordered by group, then by name, Appearance before Fonts`() {
        val file = dir.resolve("settings.properties")
        val fonts = FontsPage(TestFonts.settings(file))
        val appearance = AppearancePage(ThemeSettings(SettingsStore(file), { SimpleObjectProperty(ColorScheme.LIGHT) }))
        val longerGroup = Page(listOf("Display Extras", "A"))

        val pages = SettingsPages(listOf(longerGroup, fonts, appearance)).pages

        assertEquals(listOf(appearance, fonts, longerGroup), pages)
    }
}
