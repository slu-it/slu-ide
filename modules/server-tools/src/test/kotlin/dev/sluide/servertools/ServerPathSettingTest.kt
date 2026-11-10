package dev.sluide.servertools

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerPathSettingTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `the path is empty by default, stored trimmed under its key and read again`() {
        val setting = ServerPathSetting(store, "acme.ls.path")
        assertEquals("", setting.path.get())

        setting.apply("  /opt/acme/bin/acme-ls ")

        assertEquals("/opt/acme/bin/acme-ls", setting.path.get())
        assertEquals("/opt/acme/bin/acme-ls", setting.currentPath())
        assertEquals("/opt/acme/bin/acme-ls", store.load()["acme.ls.path"])
        assertEquals("/opt/acme/bin/acme-ls", ServerPathSetting(store, "acme.ls.path").path.get())
    }
}
