package dev.sluide.projecttrust

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustedProjectsTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = TrustedProjects(dir.resolve("config/project-trust.properties"))

    private val folder by lazy { dir.resolve("project").createDirectories() }

    @Test
    fun `a decision applies to exactly the fingerprint it was made for`() {
        store().remember(folder, "abc", trusted = true)

        assertEquals(true, store().decision(folder, "abc"))
        assertNull(store().decision(folder, "def"))
        assertNull(store().decision(dir, "abc"))
    }

    @Test
    fun `without a file there is no decision`() {
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
    fun `folders are compared normalized`() {
        store().remember(folder.resolve("sub/.."), "abc", trusted = true)

        assertEquals(true, store().decision(folder, "abc"))
    }

    @Test
    fun `an unreadable file is never replaced`() {
        val store = store()
        store.file.parent.createDirectories()
        store.file.writeText("key=\\uZZZZ")

        store.remember(folder, "abc", trusted = true)

        assertEquals("key=\\uZZZZ", store.file.readText())
        assertNull(store.decision(folder, "abc"))
    }
}
