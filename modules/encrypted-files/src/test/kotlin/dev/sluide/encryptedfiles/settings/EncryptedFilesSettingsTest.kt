package dev.sluide.encryptedfiles.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class EncryptedFilesSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("settings.properties")
    private val store by lazy { SettingsStore(file) }

    @Test
    fun `idle lock is 10 minutes by default, a change is stored for the next start and is current for listeners`() {
        val settings = EncryptedFilesSettings(store)
        assertEquals(10, settings.idleLockMinutes.get())
        assertEquals(10, settings.currentIdleLockMinutes())

        val seen = mutableListOf<Int>()
        settings.idleLockMinutes.addListener { _, _, _ -> seen += settings.currentIdleLockMinutes() }
        settings.idleLockMinutes.set(3)

        assertEquals(listOf(3), seen)
        assertEquals("3", store.load()[IDLE_LOCK_KEY])
        assertEquals(3, EncryptedFilesSettings(store).currentIdleLockMinutes())
    }

    @ParameterizedTest
    @CsvSource("0,0", "240,240", "241,10", "-1,10", "abc,10", "'',10", "' 7 ',7")
    fun `a stored value counts only as a whole number from 0 to 240, else the default`(
        stored: String,
        expected: Int,
    ) {
        file.writeText("$IDLE_LOCK_KEY=$stored\n")
        assertEquals(expected, EncryptedFilesSettings(store).currentIdleLockMinutes())
    }

    @Test
    fun `the page model applies its draft only on apply`() {
        val settings = EncryptedFilesSettings(store)
        val model = EncryptedFilesSettingsPageModel(settings)

        model.idleLockMinutes.set(0)
        assertEquals(10, settings.currentIdleLockMinutes())
        model.apply()
        assertEquals(0, settings.currentIdleLockMinutes())

        model.idleLockMinutes.set(5)
        model.load()
        assertEquals(0, model.idleLockMinutes.get())
    }
}
