package dev.sluide.commandruns

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgumentHistoriesTest {
    @TempDir
    lateinit var dir: Path

    private val project by lazy { dir.resolve("project").createDirectories() }
    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("outside.json"), emptyList()) }
    private val settings by lazy { SettingsStore(dir.resolve("settings.properties")) }
    private val histories by lazy { ArgumentHistories(store, settings) }

    /** The per-file entry of [key] in the project's file, as stored. */
    private fun projectEntry(key: String) = store.read(project)?.get("files")?.get(key)

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordings")
    fun `recording puts the trimmed arguments on top, once, keeping at most the newest`(
        case: String,
        entries: List<String>,
        arguments: String,
        expected: List<String>,
    ) {
        assertEquals(expected, recorded(entries, arguments, max = 3))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removals")
    fun `removing drops only the entry`(
        case: String,
        entries: List<String>,
        entry: String,
        expected: List<String>,
    ) {
        assertEquals(expected, removed(entries, entry))
    }

    @Test
    fun `at most 20 arguments are kept by default`() {
        val entries = (1..20).map { "-n $it" }

        assertEquals(listOf("new") + entries.dropLast(1), recorded(entries, "new"))
    }

    @Test
    fun `a file in the project keeps its history in the project's file, newest first`() {
        val file = project.resolve("src/run.sh")

        assertEquals(listOf("a"), histories.record(file, project, "a"))
        assertEquals(listOf("b", "a"), histories.record(file, project, " b "))

        assertEquals(listOf("b", "a"), histories.entries(file, project))
        assertEquals(
            listOf("b", "a"),
            projectEntry("src/run.sh")?.get("runArguments")?.values()?.map { it.stringValue() },
        )
    }

    @Test
    fun `a file outside the project keeps its history in the outside file`() {
        val file = dir.resolve("scratch.sh")

        histories.record(file, project, "a")

        assertEquals(listOf("a"), histories.entries(file, project))
        assertEquals(listOf("a"), histories.entries(file, null))
        assertNull(store.read(project))
    }

    @Test
    fun `removing the last entry removes the field and so the file's entry`() {
        val file = project.resolve("run.sh")
        histories.record(file, project, "a")
        histories.record(file, project, "b")

        assertEquals(listOf("b"), histories.remove(file, project, "a"))
        assertEquals(listOf("b"), histories.entries(file, project))
        assertEquals(emptyList(), histories.remove(file, project, "b"))

        assertEquals(emptyList(), histories.entries(file, project))
        assertNull(projectEntry("run.sh"))
    }

    @Test
    fun `a blank recording writes nothing`() {
        assertEquals(emptyList(), histories.record(project.resolve("run.sh"), project, "  "))

        assertNull(store.read(project))
    }

    @Test
    fun `an expanded state that cannot be stored is logged and still applies`() {
        // A folder where the settings file should be: it cannot be read, so it is not replaced.
        val unreadable = SettingsStore(dir.resolve("settings").createDirectories())
        val histories = ArgumentHistories(store, unreadable)
        val records = CopyOnWriteArrayList<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += "${record.level} ${record.message}"
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger(ArgumentHistories::class.java.name).apply { addHandler(handler) }
        try {
            histories.expanded.set(false)
        } finally {
            logger.removeHandler(handler)
        }

        assertFalse(histories.expanded.get())
        assertEquals(listOf("WARNING Cannot store whether the run histories are expanded"), records)
    }

    @Test
    fun `the histories are expanded by default, and a change is stored for the next start`() {
        assertTrue(histories.expanded.get())

        histories.expanded.set(false)

        assertEquals("false", settings.load()[EXPANDED_KEY])
        assertFalse(ArgumentHistories(store, settings).expanded.get())
    }

    @Test
    fun `the expanded state is read on first access, not when the histories are created`() {
        val histories = ArgumentHistories(store, settings)
        dir.resolve("settings.properties").writeText("$EXPANDED_KEY=false\n")

        assertFalse(histories.expanded.get())
    }

    @Test
    fun `a view's history follows its file when it moves`() {
        var file = project.resolve("a.sh")
        val history = ArgumentHistory(histories, { file }, { project })
        history.record("x")
        histories.record(project.resolve("b.sh"), project, "y")

        file = project.resolve("b.sh")
        assertEquals(listOf("x"), history.entries)
        history.reload()

        assertEquals(listOf("y"), history.entries)
        history.record("z")
        assertEquals(listOf("z", "y"), histories.entries(project.resolve("b.sh"), project))
        history.remove("y")
        assertEquals(listOf("z"), history.entries)
        assertEquals(listOf("x"), histories.entries(project.resolve("a.sh"), project))
    }

    companion object {
        @JvmStatic
        fun recordings() =
            listOf(
                Arguments.of("into an empty history", listOf<String>(), "-x", listOf("-x")),
                Arguments.of("trimmed", listOf("a"), "  -x test \t", listOf("-x test", "a")),
                Arguments.of("blank is ignored", listOf("a"), " \t ", listOf("a")),
                Arguments.of("empty is ignored", listOf("a"), "", listOf("a")),
                Arguments.of("again moves to the top", listOf("a", "b", "c"), "c", listOf("c", "a", "b")),
                Arguments.of("on top stays", listOf("a", "b"), " a", listOf("a", "b")),
                Arguments.of("the oldest drops", listOf("a", "b", "c"), "d", listOf("d", "a", "b")),
            )

        @JvmStatic
        fun removals() =
            listOf(
                Arguments.of("the entry", listOf("a", "b", "c"), "b", listOf("a", "c")),
                Arguments.of("the last one", listOf("a"), "a", listOf<String>()),
                Arguments.of("an absent one is a no-op", listOf("a", "b"), "c", listOf("a", "b")),
                Arguments.of("exact text only", listOf("a b"), "a", listOf("a b")),
            )
    }
}
