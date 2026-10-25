package dev.sluide.projecttrust

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustedProjectsTest {
    @TempDir
    lateinit var dir: Path

    private val data by lazy {
        ProjectDataStore(dir.resolve("projects"), dir.resolve("outside-projects.json"), emptyList())
    }

    private fun store() = TrustedProjects(data)

    private val folder by lazy { dir.resolve("project").createDirectories() }

    @Test
    fun `a decision applies to exactly the fingerprint it was made for`() {
        store().remember(folder, "abc", trusted = true)

        assertEquals(true, store().decision(folder, "abc"))
        assertNull(store().decision(folder, "def"))
        assertNull(store().decision(dir, "abc"))
    }

    @Test
    fun `without a decision there is none`() {
        assertNull(store().decision(folder, "abc"))
    }

    @Test
    fun `a later decision replaces the earlier one`() {
        store().remember(folder, "abc", trusted = true)
        store().remember(folder, "def", trusted = false)

        assertNull(store().decision(folder, "abc"))
        assertEquals(false, store().decision(folder, "def"))
    }

    @Test
    fun `other data of the project is kept`() {
        data.update(folder) { it.put("lastOpened", 5L) }

        store().remember(folder, "abc", trusted = true)

        assertEquals(5, data.read(folder)?.get("lastOpened")?.asLong())
    }

    @Test
    fun `a malformed trust object gives no decision`() {
        data.update(folder) { it.put("trust", "yes") }
        assertNull(store().decision(folder, "abc"))

        data.update(folder) { it.putObject("trust").put("fingerprint", "abc") }
        assertNull(store().decision(folder, "abc"))
    }
}
