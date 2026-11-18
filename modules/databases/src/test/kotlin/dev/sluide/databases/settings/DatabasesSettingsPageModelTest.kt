package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
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

    private fun storeValues() =
        dir.resolve("settings.properties").writeText(
            "databases.pageSize=100\ndatabases.fetchAllCap=2000\ndatabases.statementTimeoutSeconds=30\n",
        )

    @Test
    fun `the drafts start as the current values and load puts them back`() {
        storeValues()
        assertEquals(
            listOf(
                "100",
                "2000",
                "30",
            ),
            listOf(model.pageSize, model.fetchAllCap, model.statementTimeoutSeconds).map {
                it.get()
            },
        )

        model.pageSize.set("11")
        model.fetchAllCap.set("3000")
        model.statementTimeoutSeconds.set("5")
        model.load()

        assertEquals(
            listOf(
                "100",
                "2000",
                "30",
            ),
            listOf(model.pageSize, model.fetchAllCap, model.statementTimeoutSeconds).map {
                it.get()
            },
        )
        assertEquals(100, settings.pageSize.get())
    }

    @Test
    fun `a draft that is empty, blank or unset keeps the current value of its own setting`() {
        storeValues()
        model.pageSize.set("")
        model.fetchAllCap.set("  ")
        model.statementTimeoutSeconds.set(null)

        model.apply()

        assertEquals(100, settings.pageSize.get())
        assertEquals(2000, settings.fetchAllCap.get())
        assertEquals(30, settings.statementTimeoutSeconds.get())
        assertEquals("30", model.statementTimeoutSeconds.get())
    }

    @Test
    fun `each draft is applied to its own setting`() {
        storeValues()
        model.pageSize.set("200")
        model.fetchAllCap.set("4000")
        model.statementTimeoutSeconds.set("45")

        model.apply()

        assertEquals(
            listOf(
                200,
                4000,
                45,
            ),
            listOf(settings.pageSize, settings.fetchAllCap, settings.statementTimeoutSeconds).map {
                it.get()
            },
        )
    }
}
