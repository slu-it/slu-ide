package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabasesSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("settings.properties")

    private fun settings() = DatabasesSettings(SettingsStore(file))

    @Test
    fun `defaults, and bad or out of range stored values give the defaults`() {
        val settings = settings()
        assertEquals(500, settings.pageSize.get())
        assertEquals(100_000, settings.fetchAllCap.get())
        assertEquals(0, settings.statementTimeoutSeconds.get())

        file.writeText("databases.pageSize=5\ndatabases.fetchAllCap=abc\ndatabases.statementTimeoutSeconds=90000\n")
        val bad = settings()
        assertEquals(500, bad.pageSize.get())
        assertEquals(100_000, bad.fetchAllCap.get())
        assertEquals(0, bad.statementTimeoutSeconds.get())
    }

    @Test
    fun `applied values are clamped, notify listeners and are stored`() {
        val settings = settings()
        val seen = mutableListOf<Int>()
        settings.pageSize.subscribe { value -> seen += value.toInt() }

        settings.apply(20_000, 5_000, 30)

        assertEquals(listOf(500, 10_000), seen)
        assertEquals(5_000, settings.fetchAllCap.get())
        val next = settings()
        assertEquals(10_000, next.pageSize.get())
        assertEquals(5_000, next.fetchAllCap.get())
        assertEquals(30, next.statementTimeoutSeconds.get())
    }

    @Test
    fun `unchanged values are not written, and ones that cannot be stored are not set`() {
        settings().apply(500, 100_000, 0)
        assertFalse(file.exists())

        file.createDirectories()
        val settings = settings()
        assertFailsWith<IOException> { settings.apply(100, 100_000, 0) }
        assertEquals(500, settings.pageSize.get())
    }
}
