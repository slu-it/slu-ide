package dev.sluide.converting

import dev.sluide.converting.model.FakeFamily
import dev.sluide.converting.model.FakeFormat
import dev.sluide.converting.model.ManualScheduler
import dev.sluide.converting.model.Pick
import dev.sluide.converting.model.Side
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ConvertingTest {
    @TempDir
    lateinit var dir: Path

    private val family = FakeFamily("Data", listOf(FakeFormat("json"), FakeFormat("yaml", listOf("yaml", "yml"))))
    private val scheduler = ManualScheduler()

    private fun converting(): Converting {
        val fonts = FontArea.entries.associateWith { FontSpec("Monospaced", 13.0) }
        val fontSettings = FontSettings(SettingsStore(dir.resolve("settings.properties")), fonts)
        return Converting(listOf(family), fontSettings, { it.run() }, scheduler)
    }

    @Test
    fun `open puts the text on the left, pinned to the format of the extension, and shows the tab`() {
        val converting = converting()
        var shown = 0
        converting.showTab = { shown++ }

        converting.open("yaml:a", ".yml")

        assertEquals("yaml:a", converting.model.text(Side.LEFT))
        assertEquals(Side.LEFT, converting.model.source)
        assertEquals(Pick.Pinned(family.format("yaml")), converting.model.pick(Side.LEFT))
        assertEquals(1, shown)
        assertNull(converting.openScratch, "no Open as Scratch until ide sets it")
    }

    @Test
    fun `open detects without an extension or with one no format has`() {
        val converting = converting()

        converting.open("json:a", "txt")
        assertEquals(Pick.Detect, converting.model.pick(Side.LEFT))

        converting.open("json:b", null)
        assertEquals(Pick.Detect, converting.model.pick(Side.LEFT))
    }

    @Test
    fun `closing it stops the conversions`() {
        val converting = converting()

        converting.close()
        converting.model.edit(Side.LEFT, "json:a")

        assertEquals("", converting.model.text(Side.LEFT), "edits are ignored once closed")
        assertTrue(scheduler.waiting.isEmpty())
        assertFailsWith<RejectedExecutionException>("the background thread is shut down") {
            scheduler.schedule(Duration.ZERO) {}
        }
    }
}
