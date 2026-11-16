package dev.sluide.java.settings

import dev.sluide.java.JavaSettings
import dev.sluide.java.JavaWorkspaceUse
import dev.sluide.java.JavaWorkspaces
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Java page's extra parts without a scene: memory choice and the workspaces table (ADR 0084). */
class JavaSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings.properties")) }
    private val settings by lazy { JavaSettings(store) }
    private val root by lazy { dir.resolve("workspaces").createDirectories() }
    private val workspaces by lazy { JavaWorkspaces(root) }
    private val use = JavaWorkspaceUse { it.run() }
    private val questions = mutableListOf<String>()
    private var answer = true
    private var duringQuestion: () -> Unit = {}
    private val ioQueue = ArrayDeque<Runnable>()
    private val fxQueue = ArrayDeque<Runnable>()
    private var zone = ZoneId.of("UTC")

    private val model by lazy {
        JavaSettingsPageModel(
            settings,
            workspaces,
            use,
            { question ->
                questions += question
                duringQuestion()
                answer
            },
            { ioQueue += it },
            { fxQueue += it },
            zone,
        )
    }

    private fun runIo() {
        while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
    }

    private fun runFx() {
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
    }

    private fun runAll() {
        while (ioQueue.isNotEmpty() || fxQueue.isNotEmpty()) {
            runIo()
            runFx()
        }
    }

    private fun workspace(
        name: String,
        project: String? = "/work/$name",
        lastUsed: String? = "2026-11-15T10:30:00Z",
        bytes: Int = 2_000_000,
    ): Path {
        val folder = root.resolve(name).createDirectories()
        project?.let { folder.resolve("project-path.txt").writeText(it) }
        lastUsed?.let { folder.resolve("last-used").writeText(it) }
        folder
            .resolve("data")
            .createDirectories()
            .resolve("index.bin")
            .writeBytes(ByteArray(bytes))
        return folder
    }

    @Test
    fun `loading lists the workspaces with project, size and last use, off the calling thread`() {
        workspace("app-0123456789ab")

        model.load()
        assertTrue(model.rows.isEmpty(), "listed on the io thread")
        assertTrue(model.loading.get())
        runIo()
        assertTrue(model.loading.get(), "the rows arrive on the FX thread")
        runFx()

        assertFalse(model.loading.get())
        assertEquals(
            listOf(
                WorkspaceRow(root.resolve("app-0123456789ab"), "/work/app-0123456789ab", "2.0 MB", "2026-11-15 10:30"),
            ),
            model.rows,
        )
    }

    @Test
    fun `a workspace without a readable project path or time is unknown`() {
        workspace("lost-aaaaaaaaaaaa", project = null, lastUsed = null, bytes = 500)

        model.load()
        runAll()

        assertEquals(listOf("unknown"), model.rows.map { it.project })
        assertEquals(listOf("unknown"), model.rows.map { it.lastUsed })
        assertEquals(listOf("500 B"), model.rows.map { it.size })
    }

    @Test
    fun `the last use is shown in the local time zone`() {
        workspace("app-0123456789ab")
        zone = ZoneId.of("Asia/Tokyo")

        model.load()
        runAll()

        assertEquals("2026-11-15 19:30", model.rows.single().lastUsed)
    }

    @Test
    fun `the most recently used workspace comes first and unknown ones last`() {
        workspace("old-aaaaaaaaaaaa", lastUsed = "2026-01-01T00:00:00Z")
        workspace("none-bbbbbbbbbbbb", lastUsed = null)
        workspace("new-cccccccccccc", lastUsed = "2026-11-01T00:00:00Z")

        model.load()
        runAll()

        assertEquals(
            listOf(
                "/work/new-cccccccccccc",
                "/work/old-aaaaaaaaaaaa",
                "/work/none-bbbbbbbbbbbb",
            ),
            model.rows.map {
                it.project
            },
        )
    }

    @Test
    fun `an older load that finishes after a newer one does not replace its rows`() {
        val gone = workspace("gone-aaaaaaaaaaaa")
        workspace("kept-bbbbbbbbbbbb")
        model.load()
        runIo()
        val older = fxQueue.removeFirst()
        workspaces.delete(gone)
        model.load()
        runIo()

        runFx()
        older.run()

        assertEquals(listOf("/work/kept-bbbbbbbbbbbb"), model.rows.map { it.project })
        assertFalse(model.loading.get())
    }

    @Test
    fun `the delete button is off without a selection and for a workspace in use`() {
        val folder = workspace("app-0123456789ab")
        model.load()
        runAll()
        val row = model.rows.single()

        assertEquals(DeleteButton("Delete…", true), model.deleteButton(null))
        assertEquals(DeleteButton("Delete…", false), model.deleteButton(row))

        val held = use.acquire(folder)
        assertEquals(DeleteButton("Delete… (in use)", true), model.deleteButton(row))
        held.close()
        assertEquals(DeleteButton("Delete…", false), model.deleteButton(row))
    }

    @Test
    fun `delete asks first, names the project and what is lost, and Cancel deletes nothing`() {
        val folder = workspace("app-0123456789ab")
        model.load()
        runAll()
        answer = false

        model.delete(model.rows.single())
        runAll()

        assertEquals(1, questions.size)
        val question = questions.single()
        assertTrue("/work/app-0123456789ab" in question, question)
        assertTrue("permanently" in question && "indexes" in question && "import cache" in question, question)
        assertTrue("rebuilt" in question && "next time" in question, question)
        assertTrue(Files.isDirectory(folder))
        assertEquals(1, model.rows.size)
    }

    @Test
    fun `the question calls a workspace without a project path that of an unknown project`() {
        workspace("lost-aaaaaaaaaaaa", project = null)
        model.load()
        runAll()

        val question = JavaSettingsPageModel.deleteQuestion(model.rows.single())

        assertTrue(question.startsWith("Delete the Java workspace of an unknown project? Its folder "), question)
    }

    @Test
    fun `a confirmed delete removes the folder off the calling thread and then the row`() {
        val folder = workspace("app-0123456789ab")
        val other = workspace("other-bbbbbbbbbbbb")
        model.load()
        runAll()

        model.delete(model.rows.single { it.folder == folder })
        assertTrue(Files.isDirectory(folder), "not before the io thread ran")
        runAll()

        assertFalse(Files.exists(folder))
        assertTrue(Files.isDirectory(other))
        assertEquals(listOf(other), model.rows.map { it.folder })
        assertEquals("", model.message.get())
    }

    @Test
    fun `a workspace in use is neither asked about nor deleted`() {
        val folder = workspace("app-0123456789ab")
        model.load()
        runAll()
        val held = use.acquire(folder)

        model.delete(model.rows.single())
        runAll()

        assertTrue(questions.isEmpty())
        assertTrue(Files.isDirectory(folder))
        held.close()
    }

    @Test
    fun `a workspace that a window starts using during the question is not deleted and says why`() {
        val folder = workspace("app-0123456789ab")
        model.load()
        runAll()
        duringQuestion = { use.acquire(folder) }

        model.delete(model.rows.single())
        runAll()

        assertEquals(1, questions.size)
        assertTrue(Files.isDirectory(folder))
        assertEquals("Cannot delete /work/app-0123456789ab: a window is using it", model.message.get())
    }

    @Test
    fun `a workspace that a window starts using after the question and before the deletion is not deleted`() {
        val folder = workspace("app-0123456789ab")
        model.load()
        runAll()

        model.delete(model.rows.single())
        val held = use.acquire(folder)
        runAll()

        assertTrue(Files.isDirectory(folder))
        assertEquals("Cannot delete /work/app-0123456789ab: a window is using it", model.message.get())
        held.close()
    }

    @Test
    fun `a delete that fails says so and the next load clears it`() {
        workspace("app-0123456789ab")
        model.load()
        runAll()
        val outside = WorkspaceRow(dir.resolve("elsewhere"), "/work/x", "1 B", "unknown")

        model.delete(outside)
        runAll()

        assertTrue(model.message.get().startsWith("Cannot delete /work/x: "), model.message.get())
        assertTrue(Files.isDirectory(root.resolve("app-0123456789ab")))

        model.load()
        runAll()
        assertEquals("", model.message.get())
    }

    @Test
    fun `loading shows the stored memory and apply stores the chosen one`() {
        store.update(mapOf("java.maxMemory" to "4096"))

        model.load()
        assertEquals(4096, model.memory.get())

        model.memory.set(8192)
        assertEquals(4096, settings.maxMemoryMb.get(), "a draft until apply")
        model.apply()

        assertEquals(8192, settings.maxMemoryMb.get())
        assertEquals("8192", store.load()["java.maxMemory"])
    }

    @Test
    fun `loading drops an unapplied choice`() {
        model.load()
        model.memory.set(8192)

        model.load()

        assertEquals(2048, model.memory.get())
    }

    @Test
    fun `the memory choices are 1 2 3 4 6 and 8 GB plus a stored size in between`() {
        model.load()
        assertEquals(listOf(1024, 2048, 3072, 4096, 6144, 8192), model.memoryChoices())

        store.update(mapOf("java.maxMemory" to "5120"))
        val other = JavaSettingsPageModel(JavaSettings(store), workspaces, use, { true }, { it.run() }, { it.run() })
        other.load()
        assertEquals(listOf(1024, 2048, 3072, 4096, 5120, 6144, 8192), other.memoryChoices())

        store.update(mapOf("java.maxMemory" to "600"))
        val small = JavaSettingsPageModel(JavaSettings(store), workspaces, use, { true }, { it.run() }, { it.run() })
        small.load()
        assertEquals(listOf(600, 1024, 2048, 3072, 4096, 6144, 8192), small.memoryChoices())
    }

    @ParameterizedTest
    @CsvSource("1024,1 GB", "2048,2 GB", "8192,8 GB", "5120,5120 MB", "600,600 MB", "65536,65536 MB")
    fun `a memory is shown in GB when the page offers it and in MB otherwise`(
        megabytes: Int,
        label: String,
    ) {
        assertEquals(label, JavaSettingsPageModel.memoryLabel(megabytes))
    }

    @ParameterizedTest
    @CsvSource(
        "0,0 B",
        "999,999 B",
        "1000,1.0 kB",
        "1500,1.5 kB",
        "999949,999.9 kB",
        "1234567,1.2 MB",
        "2000000000,2.0 GB",
        "123456789012,123.5 GB",
        "5000000000000,5000.0 GB",
    )
    fun `sizes are shown with one decimal in decimal units`(
        bytes: Long,
        text: String,
    ) {
        assertEquals(text, JavaSettingsPageModel.humanSize(bytes))
    }
}
