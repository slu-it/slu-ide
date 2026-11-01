package dev.sluide.docker.dockerfile

import dev.sluide.docker.DockerSession
import dev.sluide.docker.EngineState
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.tab.FxQueue
import dev.sluide.docker.tab.fakeSession
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleStringProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [BuildPaneModel] on a session over a [FakeDockerCli]: the preview, Build with the question about unsaved changes,
 * Run… after a successful build, the engine's state and storing the options. The command itself is BuildCommandsTest's,
 * the texts are BuildRulesTest's.
 */
class BuildPaneModelTest {
    @TempDir
    lateinit var dir: Path

    private val cli = FakeDockerCli()
    private val sessionScheduler = ManualScheduler()
    private val debounce = ManualScheduler()
    private val fx = FxQueue()
    private val host = FakeHost()
    private val folder by lazy { dir.resolve("app").createDirectories() }
    private val file by lazy { folder.resolve("Dockerfile").apply { writeText(DOCKERFILE) } }
    private val projectData by lazy {
        ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    }
    private val store by lazy { DockerBuildStore(projectData) }
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun close() = opened.reversed().forEach { it.close() }

    private fun model(session: DockerSession = fakeSession(cli, sessionScheduler).also { opened += it }) =
        BuildPaneModel(
            session = session,
            store = store,
            file = { file },
            projectFolder = { dir },
            executable = { "/usr/bin/docker" },
            host = host,
            scheduler = debounce,
            fx = fx,
            debounce = 300.milliseconds,
        ).also {
            it.info.set(parseDockerfile(DOCKERFILE))
            host.text = DOCKERFILE
            opened += it
        }

    @Test
    fun `the preview shows the command Build runs, and Build starts it with the values in the environment`() {
        val model = model()
        model.update { it.copy(tags = "myapp:1.0 myapp:latest", buildArgs = mapOf("TOKEN" to "s3cret")) }

        val preview = assertIs<BuildPreview.Command>(model.preview.get())
        model.build()

        val (job, command, environment) = host.started.single()
        assertEquals(preview.line, command)
        assertEquals(
            "'/usr/bin/docker' 'build' '-f' '$file' '-t' 'myapp:1.0' '-t' 'myapp:latest' " +
                "'--build-arg' \"TOKEN=\$SLUIDE_BUILD_ARG_0\" '$folder'",
            command,
        )
        assertEquals(mapOf("SLUIDE_BUILD_ARG_0" to "s3cret"), environment)
        assertEquals(BuildJob("build myapp:1.0", "Dockerfile"), job)
        assertEquals("Build: build myapp:1.0 (Dockerfile)", job.backgroundName)
    }

    @Test
    fun `invalid options show the problem and disable Build`() {
        val model = model()
        assertTrue(model.enabled.get())

        model.update { it.copy(tags = "MyApp") }
        model.build()

        assertIs<BuildPreview.Problem>(model.preview.get())
        assertFalse(model.enabled.get())
        assertEquals(emptyList(), host.started)
    }

    @Test
    fun `unsaved changes ask first, and Cancel builds nothing`() {
        val model = model()
        host.isDirty = true
        host.unsavedAnswer = BuildUnsavedChoice.CANCEL

        model.build()

        assertEquals(listOf(unsavedBuildQuestion("Dockerfile")), host.unsavedQuestions)
        assertEquals(0, host.saveCalls)
        assertEquals(emptyList(), host.started)
    }

    @Test
    fun `Save and Build saves first and builds with the ARGs of the editor's text, not built if saving fails`() {
        val model = model()
        model.update { it.copy(buildArgs = mapOf("EXTRA" to "1")) }
        host.isDirty = true
        host.text = "$DOCKERFILE\nARG EXTRA\n"
        host.unsavedAnswer = BuildUnsavedChoice.SAVE_AND_BUILD
        host.saves = false

        model.build()
        assertEquals(1, host.saveCalls)
        assertEquals(emptyList(), host.started)

        host.saves = true
        model.build()

        assertEquals(2, host.saveCalls)
        assertEquals(listOf("EXTRA=\$SLUIDE_BUILD_ARG_0"), buildArgWords(host.started.single().second))
    }

    @Test
    fun `Build Saved Version does not save and builds with the ARGs of the file on disk`() {
        val model = model()
        model.update { it.copy(buildArgs = mapOf("EXTRA" to "1", "TOKEN" to "t")) }
        host.isDirty = true
        host.text = "$DOCKERFILE\nARG EXTRA\n"
        host.unsavedAnswer = BuildUnsavedChoice.BUILD_SAVED

        model.build()

        assertEquals(0, host.saveCalls)
        assertEquals(
            listOf("TOKEN=\$SLUIDE_BUILD_ARG_0"),
            buildArgWords(host.started.single().second),
            "EXTRA is only in the unsaved text",
        )
    }

    @Test
    fun `Build Saved Version shows a problem and builds nothing when the saved file cannot be read`() {
        val model = model()
        host.isDirty = true
        host.unsavedAnswer = BuildUnsavedChoice.BUILD_SAVED
        file.deleteExisting()

        model.build()

        assertEquals(emptyList(), host.started)
        assertEquals(listOf(savedUnreadableProblem("Dockerfile")), host.problems)
    }

    @Test
    fun `a build asked for while the engine is being reached starts once it answers`() {
        val work = LinkedBlockingQueue<Runnable>()
        val model = model(queuedSession(work))

        model.build()
        assertTrue(model.waitingBuild.get())
        assertEquals(emptyList(), host.started, "not before the engine answers")
        while (work.isNotEmpty()) work.poll().run()

        assertEquals(1, host.started.size)
        assertFalse(model.waitingBuild.get())
    }

    @Test
    fun `a build waiting for the engine is dropped when the engine cannot be reached`() {
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }
        val work = LinkedBlockingQueue<Runnable>()
        val model = model(queuedSession(work))

        model.build()
        while (work.isNotEmpty()) work.poll().run()
        assertIs<EngineState.Unavailable>(model.engine.get())
        assertFalse(model.waitingBuild.get())

        cli.failing = { null }
        sessionScheduler.advance(2_000.milliseconds)
        while (work.isNotEmpty()) work.poll().run()

        assertTrue(model.enabled.get(), "the engine answers now")
        assertEquals(emptyList(), host.started, "the dropped build does not start later")
    }

    @Test
    fun `a closed model no longer follows the window's engine`() {
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }
        val session = fakeSession(cli, sessionScheduler).also { opened += it }
        // Another view keeps the session retrying.
        opened += session.subscribe()
        val model = model(session)
        assertIs<EngineState.Unavailable>(model.engine.get())

        model.close()
        cli.failing = { null }
        sessionScheduler.advance(2_000.milliseconds)

        assertIs<EngineState.Available>(session.state.get())
        assertIs<EngineState.Unavailable>(model.engine.get())
    }

    @Test
    fun `Run is enabled after a build with a tag ended with exit code 0, until the next build starts`() {
        val model = model()
        model.update { it.copy(tags = "myapp:1.0, other") }
        assertFalse(model.runImageEnabled.get(), "before any build")

        model.build()
        assertTrue(model.running.get())
        assertFalse(model.runImageEnabled.get(), "while the build runs")
        host.end(succeeded = true)

        assertTrue(model.runImageEnabled.get())
        assertEquals("myapp:1.0", model.builtTag.get())
        model.runImage()
        assertEquals(listOf("myapp:1.0"), host.runImages)

        model.build()
        assertFalse(model.runImageEnabled.get(), "the next build runs")
        host.end(succeeded = false)
        assertFalse(model.runImageEnabled.get(), "it failed")
        assertNull(model.builtTag.get())
        model.runImage()
        assertEquals(listOf("myapp:1.0"), host.runImages, "not run while disabled")
    }

    @Test
    fun `Run stays disabled after a successful build without a tag`() {
        val model = model()

        model.build()
        host.end(succeeded = true)

        assertFalse(model.runImageEnabled.get())
        assertEquals(BuildJob("build Dockerfile", "Dockerfile"), host.started.single().first)
    }

    @Test
    fun `a stopped build's end does not count once the next one started`() {
        val model = model()
        model.update { it.copy(tags = "myapp") }
        model.build()
        val first = host.endings.single()

        model.build()
        first(true)

        assertTrue(model.running.get())
        assertFalse(model.runImageEnabled.get())
        host.end(succeeded = true)
        assertTrue(model.runImageEnabled.get())
    }

    @Test
    fun `the tags are recorded in the history only when a build started`() {
        val model = model()
        model.update { it.copy(tags = "myapp:1.0") }
        host.starts = false

        model.build()
        assertEquals(emptyList(), model.options.get().history)

        host.starts = true
        model.build()
        model.update { it.copy(tags = "myapp:2.0") }
        model.build()

        assertEquals(listOf("myapp:2.0", "myapp:1.0"), model.options.get().history)
    }

    @Test
    fun `while the engine is not available Build and Run are disabled`() {
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }
        val model = model()

        assertIs<EngineState.Unavailable>(model.engine.get())
        assertIs<BuildPreview.Command>(model.preview.get(), "the command still shows")
        assertFalse(model.enabled.get())
        model.build()
        assertEquals(emptyList(), host.started)

        cli.failing = { null }
        sessionScheduler.advance(2_000.milliseconds)

        assertTrue(model.enabled.get())
    }

    @Test
    fun `options are stored once the debounce passed, and on close`() {
        val model = model()

        model.update { it.copy(pull = true) }
        model.update { it.copy(pull = true, noCache = true) }
        assertEquals(BuildOptions(), store.load(file, dir), "not before the debounce")
        debounce.advance(300.milliseconds)
        fx.drain()
        assertEquals(BuildOptions(noCache = true, pull = true), store.load(file, dir))

        model.update { it.copy(platform = "linux/arm64") }
        model.close()
        assertEquals("linux/arm64", store.load(file, dir).platform)
    }

    @Test
    fun `stored options are loaded, and a moved file keeps them under its new path`() {
        store.save(file, dir, BuildOptions(target = "build"))
        var path = file
        val model =
            BuildPaneModel(fakeSession(cli, sessionScheduler).also { opened += it }, store, { path }, { dir }, {
                "docker"
            }, host, debounce, fx).also { opened += it }
        assertEquals("build", model.options.get().target)

        path = folder.resolve("Dockerfile.prod")
        model.moved()

        assertEquals("build", store.load(path, dir).target)
        assertTrue(
            model.preview
                .get()
                .toString()
                .contains("Dockerfile.prod"),
        )
    }

    @Test
    fun `Rerun builds again through Build, asking again`() {
        val model = model()
        model.build()
        host.isDirty = true

        model.rerun()

        assertEquals(1, host.unsavedQuestions.size)
        assertEquals(1, host.started.size, "cancelled")
    }

    /** A session whose own work waits in [work] until the test runs it. */
    private fun queuedSession(work: LinkedBlockingQueue<Runnable>) =
        DockerSession(
            executable = SimpleStringProperty("docker"),
            connect = { DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/docker"), emptyMap()), cli) },
            worker = { work.add(it) },
            scheduler = sessionScheduler,
            fx = { it.run() },
        ).also { opened += it }

    /** The words after `--build-arg` in the [command] line, without their quotes. */
    private fun buildArgWords(command: String) =
        Regex("""'--build-arg' "([^"]*)"""").findAll(command).map { it.groupValues[1] }.toList()

    /** Records what the model asks of the view; the answers are settable. */
    private class FakeHost : BuildHost {
        override var isDirty = false
        override var text = ""
        var unsavedAnswer = BuildUnsavedChoice.CANCEL
        var saves = true
        var saveCalls = 0

        /** Whether [start] starts the job (`false`: the question about the running one was answered no). */
        var starts = true
        val unsavedQuestions = mutableListOf<String>()
        val started = mutableListOf<Triple<BuildJob, String, Map<String, String>>>()
        val endings = mutableListOf<(Boolean) -> Unit>()
        val runImages = mutableListOf<String>()
        val problems = mutableListOf<String>()

        override fun askUnsaved(question: String): BuildUnsavedChoice {
            unsavedQuestions += question
            return unsavedAnswer
        }

        override fun save(): Boolean {
            saveCalls++
            if (saves) isDirty = false
            return saves
        }

        override fun start(
            job: BuildJob,
            command: () -> String,
            environment: Map<String, String>,
            ended: (succeeded: Boolean) -> Unit,
        ): Boolean {
            if (!starts) return false
            started += Triple(job, command(), environment)
            endings += ended
            return true
        }

        /** Ends the last started build. */
        fun end(succeeded: Boolean) = endings.last()(succeeded)

        override fun runImage(reference: String) {
            runImages += reference
        }

        override fun showProblem(message: String) {
            problems += message
        }
    }

    private companion object {
        const val DOCKERFILE = "ARG TOKEN\nFROM alpine AS base\nARG LEVEL=3\nRUN true\n"
    }
}
