package dev.sluide.projecttrust

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyTrustDecisionsTest {
    @TempDir
    lateinit var dir: Path

    private val legacy by lazy { LegacyTrustDecisions(dir.resolve("project-trust.properties")) }

    private fun store(vararg migrations: LegacyTrustDecisions) =
        ProjectDataStore(dir.resolve("projects"), dir.resolve("outside-projects.json"), migrations.toList())

    private fun folder(name: String) = dir.resolve(name).createDirectories()

    @Test
    fun `both forms are parsed`() {
        val data = store()
        val a = folder("a")
        val b = folder("b")

        assertTrue(legacy.migrate(mapOf(a.toString() to "trusted:fp:1", b.toString() to "untrusted:fp2"), data))

        val trusted = TrustedProjects(data)
        assertEquals(true, trusted.decision(a, "fp:1"))
        assertEquals(false, trusted.decision(b, "fp2"))
    }

    @Test
    fun `an existing decision is never overwritten`() {
        val data = store()
        val a = folder("a")
        TrustedProjects(data).remember(a, "new", trusted = false)

        assertTrue(legacy.migrate(mapOf(a.toString() to "trusted:old"), data))

        assertEquals(false, TrustedProjects(data).decision(a, "new"))
        assertNull(TrustedProjects(data).decision(a, "old"))
    }

    @Test
    fun `malformed values are skipped`() {
        val data = store()
        val a = folder("a")

        assertTrue(legacy.migrate(mapOf(a.toString() to "maybe:abc", "bad\u0000path" to "trusted:x"), data))

        assertEquals(emptyList(), data.projects())
    }

    @Test
    fun `a failing write reports incomplete`() {
        dir.resolve("projects").writeText("not a folder")

        assertFalse(legacy.migrate(mapOf(folder("a").toString() to "trusted:x"), store()))
    }

    @Test
    fun `the old file is migrated and deleted by the store`() {
        val a = folder("a")
        legacy.file.writeText("$a=trusted\\:abc\n")

        val trusted = TrustedProjects(store(legacy))

        assertEquals(true, trusted.decision(a, "abc"))
        assertFalse(legacy.file.exists())
    }
}
