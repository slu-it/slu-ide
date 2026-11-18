package dev.sluide.codeintelligence

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The declined install prompts: read from the shared data file, saved as `codeIntelligence.installDeclined`. */
class InstallPromptsTest {
    @TempDir
    lateinit var folder: Path

    private val shared get() = folder.resolve("shared.json")

    private fun store() = ProjectDataStore(folder.resolve("projects"), shared, emptyList())

    private fun prompts(writer: Executor = Executor(Runnable::run)) = InstallPrompts(store(), writer)

    private fun file(json: String) = Files.writeString(shared, json)

    private fun saved(): String? = store().readShared()?.get("codeIntelligence")?.toString()

    @Test
    fun `nothing is declined without a shared file`() {
        assertEquals(emptySet(), prompts().declined.toSet())
    }

    @Test
    fun `the declined ids are read from the shared file, only the strings`() {
        // A hand-edited file can hold anything in the array; only string ids are prompt ids.
        file(
            """{"version":1,"codeIntelligence":{"installDeclined":["python",5,null,{"a":1},["z"],"rust.environment"]}}""",
        )

        assertEquals(setOf("python", "rust.environment"), prompts().declined.toSet())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            """{"version":1}""",
            """{"version":1,"codeIntelligence":"python"}""",
            """{"version":1,"codeIntelligence":["python"]}""",
            """{"version":1,"codeIntelligence":{}}""",
            """{"version":1,"codeIntelligence":{"installDeclined":"python"}}""",
            """{"version":1,"codeIntelligence":{"installDeclined":{"0":"python"}}}""",
        ],
    )
    fun `a file without a list of declined ids declines nothing`(json: String) {
        file(json)

        assertEquals(emptySet(), prompts().declined.toSet())
    }

    @Test
    fun `declining adds the id at once and saves it through the writer`() {
        val queued = mutableListOf<Runnable>()
        val prompts = prompts { queued.add(it) }

        prompts.decline("python")

        assertEquals(setOf("python"), prompts.declined.toSet())
        assertEquals(1, queued.size)
        assertNull(saved(), "nothing is written before the writer runs")
        queued.single().run()
        assertEquals("""{"installDeclined":["python"]}""", saved())
    }

    @Test
    fun `declining an id twice saves once`() {
        var writes = 0
        val prompts = prompts { writes++ }

        prompts.decline("python")
        prompts.decline("python")

        assertEquals(1, writes)
    }

    @Test
    fun `an id declined before is not saved again`() {
        file("""{"version":1,"codeIntelligence":{"installDeclined":["python"]}}""")
        var writes = 0
        val prompts = prompts { writes++ }

        prompts.decline("python")

        assertEquals(0, writes)
    }

    @Test
    fun `declined ids accumulate in the file and its other fields stay`() {
        file("""{"version":1,"codeIntelligence":{"other":1,"installDeclined":["python"]},"theme":"dark"}""")
        val prompts = prompts()

        prompts.decline("rust")

        assertEquals("""{"other":1,"installDeclined":["python","rust"]}""", saved())
        assertEquals("dark", store().readShared()?.get("theme")?.asString())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            """{"version":1,"codeIntelligence":"junk"}""",
            """{"version":1,"codeIntelligence":["junk"]}""",
            """{"version":1,"codeIntelligence":{"installDeclined":"junk"}}""",
            """{"version":1,"codeIntelligence":{"installDeclined":{"0":"junk"}}}""",
        ],
    )
    fun `a field of the wrong shape is replaced when an id is saved`(json: String) {
        file(json)

        prompts().decline("python")

        assertEquals("""{"installDeclined":["python"]}""", saved())
    }

    @Test
    fun `an id another window saved meanwhile is not saved twice`() {
        val first = prompts()
        first.declined // read before the other window writes
        prompts().decline("python")

        first.decline("python")

        assertEquals("""{"installDeclined":["python"]}""", saved())
    }

    @Test
    fun `a number in the file is not the string id with the same text`() {
        val first = prompts()
        first.declined
        file("""{"version":1,"codeIntelligence":{"installDeclined":[5]}}""")

        first.decline("5")

        assertEquals("""{"installDeclined":[5,"5"]}""", saved())
    }

    @Test
    fun `an unreadable shared file is not replaced, the id still counts for this session`() {
        file("{ not json")
        val prompts = prompts()

        prompts.decline("python")

        assertEquals(setOf("python"), prompts.declined.toSet())
        assertEquals("{ not json", Files.readString(shared))
    }

    @Test
    fun `the application constructor saves on its own thread`() {
        val prompts = InstallPrompts(store())

        prompts.decline("python")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (saved() == null && System.nanoTime() < deadline) Thread.sleep(10) // polling with a deadline
        assertNotNull(saved(), "the id was saved")
        assertEquals("""{"installDeclined":["python"]}""", saved())
    }
}
