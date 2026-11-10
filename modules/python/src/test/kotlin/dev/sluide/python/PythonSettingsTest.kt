package dev.sluide.python

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ty path's key; how the path is stored and read is tested in server-tools (`ServerPathSettingTest`). */
class PythonSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `the ty path is stored as python ty path and read from there`() {
        PythonSettings(store).apply("/opt/ty/bin/ty")

        assertEquals("/opt/ty/bin/ty", store.load()["python.ty.path"])
        assertEquals("/opt/ty/bin/ty", PythonSettings(store).path.get())
    }
}
