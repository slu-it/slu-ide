package dev.sluide.settings

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalScrollPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { TerminalScrollSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val model by lazy { TerminalScrollPageModel(settings) }

    @Test
    fun `the edits stay drafts until applied`() {
        model.trackpad.set(35)
        model.wheel.set(150)
        assertEquals(100, settings.trackpad.get())
        assertEquals(100, settings.wheel.get())

        model.apply()

        assertEquals(35, settings.trackpad.get())
        assertEquals(150, settings.wheel.get())
    }

    @Test
    fun `loading drops the edits, like cancelling the dialog`() {
        settings.apply(trackpad = 40, wheel = 100)
        model.trackpad.set(10)
        model.wheel.set(200)

        model.load()

        assertEquals(40, model.trackpad.get())
        assertEquals(100, model.wheel.get())
        assertEquals(40, settings.trackpad.get())
    }

    @Test
    fun `slider values snap to whole multiples of 5 within the range`() {
        assertEquals(35, TerminalScrollPageModel.snap(36.4))
        assertEquals(40, TerminalScrollPageModel.snap(37.6))
        assertEquals(10, TerminalScrollPageModel.snap(3.0))
        assertEquals(200, TerminalScrollPageModel.snap(204.0))
    }
}
