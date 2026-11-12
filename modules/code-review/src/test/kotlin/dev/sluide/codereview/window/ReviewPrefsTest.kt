package dev.sluide.codereview.window

import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewPrefsTest {
    @TempDir
    lateinit var dir: Path

    private val shared by lazy { dir.resolve("shared.json") }

    private fun prefs() = ReviewPrefs(ProjectDataStore(dir.resolve("projects"), shared, emptyList()))

    @Test
    fun `defaults without a file`() {
        assertEquals(ReviewPrefsValue(ReviewLayout.SPLIT, false), prefs().read())
    }

    @Test
    fun `written values are read back by another store`() {
        prefs().write(ReviewPrefsValue(ReviewLayout.UNIFIED, true))

        assertEquals(ReviewPrefsValue(ReviewLayout.UNIFIED, true), prefs().read())
        assertTrue(shared.readText().contains("UNIFIED"))
    }

    @Test
    fun `only values that differ from the defaults are written, and an empty field goes`() {
        prefs().write(ReviewPrefsValue(ReviewLayout.SPLIT, true))
        assertFalse(shared.readText().contains("layout"))
        assertTrue(shared.readText().contains("hideWhitespace"))

        prefs().write(ReviewPrefsValue())
        assertFalse(shared.readText().contains("review"))
    }

    @Test
    fun `other fields stay and invalid values read as the defaults`() {
        shared.writeText(
            """{"version":1,"editor":{"softWrap":true},"review":{"layout":"SIDEWAYS","hideWhitespace":"yes"}}""",
        )
        assertEquals(ReviewPrefsValue(), prefs().read())

        prefs().write(ReviewPrefsValue(ReviewLayout.UNIFIED, false))

        assertTrue(shared.readText().contains("softWrap"))
        assertEquals(ReviewPrefsValue(ReviewLayout.UNIFIED, false), prefs().read())
    }
}
