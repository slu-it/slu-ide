package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
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

    @Test
    fun `a change of any one value is stored`() {
        val settings = settings()

        settings.apply(500, 5_000, 0)
        assertEquals(5_000, settings().fetchAllCap.get())
        settings.apply(500, 5_000, 60)
        assertEquals(60, settings().statementTimeoutSeconds.get())
        settings.apply(100, 5_000, 60)
        assertEquals(100, settings().pageSize.get())

        assertEquals(5_000, settings.fetchAllCap.get())
        assertEquals(60, settings.statementTimeoutSeconds.get())
        assertEquals(100, settings.pageSize.get())
    }

    @Test
    fun `values that equal the current ones are not written again`() {
        val settings = settings()
        settings.apply(100, 5_000, 60)
        file.deleteExisting()

        settings.apply(100, 5_000, 60)

        assertFalse(file.exists(), "nothing written for the same three values")
    }

    @ParameterizedTest
    @CsvSource(
        "10,10,10000,500,10",
        "9,10,10000,500,500",
        "10000,10,10000,500,10000",
        "10001,10,10000,500,500",
        "' 20 ',10,10000,500,20",
        "'',10,10000,500,500",
        "2.5,10,10000,500,500",
        "0,0,86400,7,0",
        "-1,0,86400,7,7",
        "86400,0,86400,7,86400",
        "86401,0,86400,7,7",
    )
    fun `a stored value counts inside its range, trimmed, else the default`(
        value: String,
        low: Int,
        high: Int,
        default: Int,
        expected: Int,
    ) {
        assertEquals(expected, DatabasesSettings.read(value, low..high, default))
    }

    @Test
    fun `a missing stored value is the default`() {
        assertEquals(5, DatabasesSettings.read(null, 1..10, 5))
    }
}
