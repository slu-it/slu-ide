package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabasesSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { DatabasesSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val model by lazy { DatabasesSettingsPageModel(settings) }

    @Test
    fun `the edit stays a draft until applied, and loading drops it`() {
        model.pageSize.set("100")
        assertEquals(500, settings.pageSize.get())

        model.load()
        assertEquals("500", model.pageSize.get())

        model.pageSize.set("100")
        model.apply()
        assertEquals(100, settings.pageSize.get())
    }

    @Test
    fun `a draft that is no number keeps the value and a number out of range is clamped and shown`() {
        model.pageSize.set("many")
        model.fetchAllCap.set("1")
        model.statementTimeoutSeconds.set(" 60 ")

        model.apply()

        assertEquals(500, settings.pageSize.get())
        assertEquals(1_000, settings.fetchAllCap.get())
        assertEquals(60, settings.statementTimeoutSeconds.get())
        assertEquals("500", model.pageSize.get())
        assertEquals("1000", model.fetchAllCap.get())
    }
}
