package dev.sluide.typescript

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The server path's key; how the path is stored and read is tested in server-tools (`ServerPathSettingTest`). */
class TypeScriptSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `the server path is stored as typescript server path and read from there`() {
        TypeScriptSettings(store).apply("/opt/tsc/lib/tsc")

        assertEquals("/opt/tsc/lib/tsc", store.load()["typescript.server.path"])
        assertEquals("/opt/tsc/lib/tsc", TypeScriptSettings(store).path.get())
    }
}
