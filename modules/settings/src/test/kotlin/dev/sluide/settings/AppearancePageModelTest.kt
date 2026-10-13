package dev.sluide.settings

import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearancePageModelTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy {
        ThemeSettings(SettingsStore(dir.resolve("settings.properties")), SimpleObjectProperty(ColorScheme.LIGHT))
    }
    private val model by lazy { AppearancePageModel(settings) }

    @Test
    fun `the edit stays a draft until applied`() {
        model.draft.set(Theme.DARK)
        assertEquals(Theme.SYSTEM, settings.theme.get())

        model.apply()

        assertEquals(Theme.DARK, settings.theme.get())
    }

    @Test
    fun `loading drops the edit, like cancelling the dialog`() {
        model.draft.set(Theme.LIGHT)

        model.load()

        assertEquals(Theme.SYSTEM, model.draft.get())
        assertEquals(Theme.SYSTEM, settings.theme.get())
    }
}
