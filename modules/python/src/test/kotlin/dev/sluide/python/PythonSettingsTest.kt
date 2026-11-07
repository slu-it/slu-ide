package dev.sluide.python

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PythonSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `the ty path is empty by default, stored trimmed and read again`() {
        val settings = PythonSettings(store)
        assertEquals("", settings.tyPath.get())

        settings.apply("  /opt/ty/bin/ty ")

        assertEquals("/opt/ty/bin/ty", settings.tyPath.get())
        assertEquals("/opt/ty/bin/ty", settings.currentTyPath())
        assertEquals("/opt/ty/bin/ty", store.load()["python.ty.path"])
        assertEquals("/opt/ty/bin/ty", PythonSettings(store).tyPath.get())
    }
}
