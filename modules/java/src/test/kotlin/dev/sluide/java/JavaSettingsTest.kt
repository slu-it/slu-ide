package dev.sluide.java

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The Java settings' keys and values; how a server path is stored is tested in server-tools. */
class JavaSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }

    @Test
    fun `the jdtls path is stored as java jdtls path and read from there`() {
        JavaSettings(store).apply("/opt/jdtls/bin/jdtls")

        assertEquals("/opt/jdtls/bin/jdtls", store.load()["java.jdtls.path"])
        assertEquals("/opt/jdtls/bin/jdtls", JavaSettings(store).path.get())
    }

    @Test
    fun `the maximum memory is 2 GB until it is set`() {
        val settings = JavaSettings(store)

        assertEquals(2048, settings.maxMemoryMb.get())
        assertEquals(2048, settings.currentMaxMemoryMb())
    }

    @ParameterizedTest
    @CsvSource(
        "1024,1024",
        "8192,8192",
        "3000,3000",
        "512,512",
        "65536,65536",
        "' 4096 ',4096",
        "511,2048",
        "65537,2048",
        "0,2048",
        "-4096,2048",
        "lots,2048",
        "'',2048",
        "99999999999999999999,2048",
        "4096.5,2048",
    )
    fun `a stored memory in 512 to 65536 MB is kept and anything else is the default`(
        stored: String,
        expected: Int,
    ) {
        store.update(mapOf("java.maxMemory" to stored))

        val settings = JavaSettings(store)

        assertEquals(expected, settings.maxMemoryMb.get())
        assertEquals(expected, settings.currentMaxMemoryMb())
    }

    @Test
    fun `applying a memory stores it and sets the property and the thread-safe value`() {
        val settings = JavaSettings(store)
        val seen = mutableListOf<Int>()
        settings.maxMemoryMb.addListener { _, _, value -> seen += value.toInt() }

        settings.applyMaxMemory(6144)

        assertEquals("6144", store.load()["java.maxMemory"])
        assertEquals(6144, settings.maxMemoryMb.get())
        assertEquals(6144, settings.currentMaxMemoryMb())
        assertEquals(listOf(6144), seen)
        assertEquals(6144, JavaSettings(store).maxMemoryMb.get())

        Files.delete(dir.resolve("settings.properties"))
        settings.applyMaxMemory(6144)
        assertEquals(listOf(6144), seen, "an unchanged value is no change")
        assertFalse(Files.exists(dir.resolve("settings.properties")), "and is not stored again")
    }

    @Test
    fun `an unusable memory is refused and nothing is stored`() {
        val settings = JavaSettings(store)

        for (bad in listOf(0, 100, 511, 65537)) {
            val failure = runCatching { settings.applyMaxMemory(bad) }.exceptionOrNull()
            assertEquals(IllegalArgumentException::class, failure?.let { it::class }, "$bad")
        }
        assertEquals(null, store.load()["java.maxMemory"])
        assertEquals(2048, settings.maxMemoryMb.get())
    }
}
