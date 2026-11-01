package dev.sluide.docker.compose

import dev.sluide.docker.DockerSession
import dev.sluide.docker.EngineState
import dev.sluide.docker.cli.ComposeConfigOutput
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.FxQueue
import dev.sluide.docker.tab.fakeSession
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleStringProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import kotlin.io.path.createDirectories
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
 * [ComposePaneModel] on a session over a [FakeDockerCli]: loading the configuration, the commands of the buttons with
 * the questions before Up, the row actions and storing the options. The texts and enable rules are ComposeRulesTest's.
 */
class ComposePaneModelTest {
    @TempDir
    lateinit var dir: Path

    private val cli = FakeDockerCli().apply { composeConfigOutput = shopConfig() }
    private val sessionScheduler = ManualScheduler()
    private val debounce = ManualScheduler()
    private val fx = FxQueue()
    private val host = FakeHost()
    private val folder by lazy { dir.resolve("shop").createDirectories() }
    private val file by lazy { folder.resolve("compose.yaml").apply { writeText(SHOP_COMPOSE) } }
    private val projectData by lazy {
        ProjectDataStore(
            dir.resolve("projects"),
            dir.resolve("shared.json"),
            emptyList(),
        )
    }
    private val store by lazy { ComposeStore(projectData) }
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun close() = opened.reversed().forEach { it.close() }

    private fun session(cli: DockerCli = this.cli): DockerSession =
        DockerSession(
            executable = SimpleStringProperty("docker"),
            connect = { DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/docker"), emptyMap()), cli) },
            worker = { it.run() },
            scheduler = sessionScheduler,
            fx = { it.run() },
        ).also { opened += it }

    private fun model(session: DockerSession = session()): ComposePaneModel =
        ComposePaneModel(
            session = session,
            store = store,
            file = { file },
            projectFolder = { folder },
            executable = { "/usr/bin/docker" },
            host = host,
            scheduler = debounce,
            fx = fx,
            debounce = 300.milliseconds,
        ).also { opened += it }

    /** A model whose configuration was read. */
    private fun loaded(): ComposePaneModel =
        model().also { model ->
            model.order.set(listOf("web", "worker", "debug"))
            fx.runUntil { model.config.get() is ConfigLoad.Loaded }
        }

    @Test
    fun `attaching reads the configuration with every profile in the file's folder and lists the services`() {
        val model = loaded()

        val (workingDir, args, environment) = cli.composeConfigCalls.single()
        assertEquals(folder, workingDir)
        assertEquals(ComposeCommands.baseArgs(file, null, ComposeOptions(), allProfiles = true), args)
        assertEquals(emptyMap(), environment)
        assertEquals(listOf("web", "worker", "debug"), model.services.get().map { it.name }, "in file order")
        assertTrue(model.enabled.get())
        assertEquals(1, cli.count("events"), "subscribed to the session")
    }

    @Test
    fun `the services follow the session's containers`() {
        val model = loaded()

        cli.containers = listOf(container("shop-web-1", project = "shop", service = "web"))
        cli.pushEvent(DockerEvent("container", "start", "id-shop-web-1"))
        sessionScheduler.advance(200.milliseconds)

        assertEquals(
            "running",
            model.services
                .get()
                .first { it.name == "web" }
                .summary,
        )
    }

    @Test
    fun `a result of an older load is dropped`() {
        val renamed = ComposeConfigOutput(SHOP_CONFIG_JSON.replace("\"shop\"", "\"renamed\""), "")
        val byName =
            object : DockerCli by cli {
                override fun composeConfig(
                    workingDir: Path,
                    composeArgs: List<String>,
                    environment: Map<String, String>,
                ) = if ("-p" in composeArgs) renamed else shopConfig()
            }
        val model = model(session(byName))
        val seen = mutableListOf<String>()
        model.config.subscribe { config -> (config as? ConfigLoad.Loaded)?.let { seen += it.config.name } }

        // Both loads run before either result is handed over: only the second one counts, whichever comes first.
        model.update { it.copy(projectName = "renamed") }
        model.reload()
        fx.runUntil { fx.ran == 2 }

        assertEquals(listOf("renamed"), seen)
    }

    @Test
    fun `options are stored, and only the project name and environment read the configuration again, debounced`() {
        val model = loaded()

        model.update { it.copy(build = true, profiles = setOf("debug")) }
        debounce.advance(300.milliseconds)
        fx.drain()
        assertEquals(1, cli.composeConfigCalls.size, "flags and profiles do not change the configuration")

        model.update { it.copy(variables = listOf(ComposeVariable("GREETING", "hi"))) }
        debounce.advance(299.milliseconds)
        assertEquals(1, cli.composeConfigCalls.size)
        debounce.advance(1.milliseconds)
        fx.runUntil { cli.composeConfigCalls.size == 2 }

        assertEquals(mapOf("GREETING" to "hi"), cli.composeConfigCalls.last().third)
        fx.drain()
        val stored = ComposeStore(projectData).load(file, folder)
        assertEquals(setOf("debug"), stored.profiles)
        assertTrue(stored.build)
        assertEquals(listOf(ComposeVariable("GREETING", "hi")), stored.variables)
    }

    @Test
    fun `typed options are stored once the debounce passed, and the last change on close`() {
        val model = loaded()

        model.update { it.copy(projectName = "s") }
        model.update { it.copy(projectName = "sh") }
        assertEquals(ComposeOptions(), store.load(file, folder), "not on every key")
        debounce.advance(300.milliseconds)
        fx.drain()
        assertEquals("sh", store.load(file, folder).projectName)

        model.update { it.copy(projectName = "shop2") }
        model.close()

        assertEquals("shop2", store.load(file, folder).projectName, "flushed on close")
    }

    @Test
    fun `a save reads the configuration again`() {
        val model = loaded()

        model.fileSaved()
        fx.runUntil { fx.ran == 2 }

        assertEquals(2, cli.composeConfigCalls.size)
    }

    @Test
    fun `the buttons start their commands with the options and the environment`() {
        val model = loaded()
        model.update {
            it.copy(profiles = setOf("debug"), forceRecreate = true, variables = listOf(ComposeVariable("A", "1")))
        }
        // The variable reads the configuration again (an Up meanwhile would wait for it).
        debounce.advance(300.milliseconds)
        fx.runUntil { cli.composeConfigCalls.size == 2 && fx.ran == 4 }
        val base = ComposeCommands.baseArgs(file, null, model.options.get())

        model.up()
        model.upService("web")
        model.build()
        model.pull()
        model.restart()
        model.down(removeVolumes = false)

        // The variable's value goes under its private name, handed on by name in front of the command.
        fun line(args: List<String>) = ComposeCommands.commandLine("/usr/bin/docker", args, listOf("A"))
        val docker = "env \"A=\$SLUIDE_COMPOSE_ENV_0\" '/usr/bin/docker' "
        assertEquals(
            listOf(
                "up" to line(base + listOf("up", "-d", "--force-recreate")),
                "up web" to
                    line(base + listOf("up", "-d", "--force-recreate", "web")),
                "build" to line(base + "build"),
                "pull" to line(base + "pull"),
                "restart" to line(base + "restart"),
                "down" to
                    line(ComposeCommands.downArgs(file, null, model.options.get(), removeVolumes = false)),
            ),
            host.started.map { it.first.label to it.second },
        )
        assertTrue(host.started.all { it.second.startsWith(docker) })
        assertTrue("'--profile' '*'" in host.started.last().second, "down with every profile")
        assertEquals(setOf(mapOf("SLUIDE_COMPOSE_ENV_0" to "1")), host.started.map { it.third }.toSet())
    }

    @Test
    fun `attached Up has no -d, and an override file beside a default name is passed`() {
        val override = folder.resolve("compose.override.yaml").apply { writeText("services: {}\n") }
        val model = loaded()
        model.update { it.copy(detached = false) }

        model.up()

        val args = ComposeCommands.baseArgs(file, override, model.options.get()) + "up"
        assertEquals(ComposeCommands.commandLine("/usr/bin/docker", args), host.started.single().second)
        assertEquals(override, model.override.get())
    }

    @Test
    fun `Up asks about unsaved changes first`() {
        val model = loaded()
        host.isDirty = true

        host.unsavedAnswer = UnsavedChoice.CANCEL
        model.up()
        host.unsavedAnswer = UnsavedChoice.SAVE_AND_UP
        host.saves = false
        model.up()
        assertEquals(emptyList(), host.started, "cancelled, or the save failed")

        host.saves = true
        model.up()
        fx.runUntil { host.started.isNotEmpty() }
        host.isDirty = true
        host.unsavedAnswer = UnsavedChoice.UP_SAVED
        model.upService("web")

        assertEquals(List(4) { unsavedQuestion("compose.yaml") }, host.unsavedQuestions)
        assertEquals(2, host.saveCalls)
        assertEquals(listOf("up", "up web"), host.started.map { it.first.label })
    }

    @Test
    fun `Save and Up asks about the variables of the saved file`() {
        val model = loaded()
        host.isDirty = true
        host.unsavedAnswer = UnsavedChoice.SAVE_AND_UP
        host.onSave = { cli.composeConfigOutput = shopConfig(unresolved = true) }
        host.confirmAnswer = false

        model.up()
        assertTrue(model.waitingUp.get(), "waits for the configuration of the saved file")
        fx.runUntil { host.questions.isNotEmpty() }

        assertEquals(listOf(unresolvedQuestion(listOf("GREETING"))), host.questions)
        assertEquals(emptyList(), host.started)
        assertFalse(model.waitingUp.get())
    }

    @Test
    fun `an Up right after a change of the variables waits for the configuration with them`() {
        val model = loaded()

        model.update { it.copy(variables = listOf(ComposeVariable("GREETING", "hi"))) }
        model.up()
        assertEquals(emptyList(), host.started)
        fx.runUntil { host.started.isNotEmpty() }

        assertEquals(2, cli.composeConfigCalls.size, "read at once, not after the debounce")
        assertEquals(mapOf("GREETING" to "hi"), cli.composeConfigCalls.last().third)
        assertEquals(listOf(300.milliseconds), debounce.pending, "the reload's debounce is cancelled, the store's left")
    }

    @Test
    fun `Rerun goes through the command's questions again, and only after a run that started`() {
        val model = loaded()

        model.down(removeVolumes = true)
        model.rerun()
        host.confirmAnswer = false
        model.rerun()
        assertEquals(List(3) { downVolumesQuestion("shop") }, host.questions)
        assertEquals(listOf("down -v", "down -v"), host.started.map { it.first.label })

        host.starts = false
        model.build()
        host.starts = true
        host.confirmAnswer = true
        model.rerun()

        assertEquals(listOf("down -v", "down -v", "build", "down -v"), host.started.map { it.first.label })
    }

    @Test
    fun `Up with unresolved variables asks first`() {
        cli.composeConfigOutput = shopConfig(unresolved = true)
        val model = loaded()

        host.confirmAnswer = false
        model.up()
        assertEquals(emptyList(), host.started)

        host.confirmAnswer = true
        model.upService("web")
        assertEquals(listOf(unresolvedQuestion(listOf("GREETING"))), host.questions.distinct())
        assertEquals(listOf("up web"), host.started.map { it.first.label })
    }

    @Test
    fun `Down and Remove Volumes asks, plain Down and the other commands do not`() {
        val model = loaded()

        host.confirmAnswer = false
        model.down(removeVolumes = true)
        model.down(removeVolumes = false)
        model.build()
        host.confirmAnswer = true
        model.down(removeVolumes = true)

        assertEquals(listOf(downVolumesQuestion("shop"), downVolumesQuestion("shop")), host.questions)
        assertEquals(listOf("down", "build", "down -v"), host.started.map { it.first.label })
        assertTrue(
            host.started
                .last()
                .second
                .endsWith("'down' '-v'"),
        )
    }

    @Test
    fun `an Up asked for while the engine is being reached runs once the configuration is read`() {
        val work = LinkedBlockingQueue<Runnable>()
        val session =
            DockerSession(
                executable = SimpleStringProperty("docker"),
                connect = { DockerConnection.Ready(ResolvedDocker(Path.of("/usr/bin/docker"), emptyMap()), cli) },
                worker = { work.add(it) },
                scheduler = sessionScheduler,
                fx = { it.run() },
            ).also { opened += it }
        val model = model(session)

        model.up()
        assertEquals(emptyList(), host.started, "not before the engine answers")
        while (work.isNotEmpty()) work.poll().run()
        fx.runUntil { host.started.isNotEmpty() }

        assertEquals(listOf("up"), host.started.map { it.first.label })
    }

    @Test
    fun `a configuration that fails disables the commands and drops a waiting Up`() {
        cli.failing =
            { if (it.startsWith("composeConfig")) DockerException("yaml: line 2: did not find expected key") else null }
        val model = model()
        fx.runUntil { model.config.get() is ConfigLoad.Failed }

        model.up()
        model.build()

        assertEquals(ConfigLoad.Failed("yaml: line 2: did not find expected key"), model.config.get())
        assertFalse(model.enabled.get())
        assertEquals(emptyList(), host.started)
    }

    @Test
    fun `the engine becoming available reads the configuration`() {
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }
        val session = fakeSession(cli, sessionScheduler)
        opened += session
        val model = model(session)
        assertIs<EngineState.Unavailable>(model.engine.get())
        assertEquals(ConfigLoad.None, model.config.get())

        cli.failing = { null }
        sessionScheduler.advance(2_000.milliseconds)
        fx.runUntil { model.config.get() is ConfigLoad.Loaded }

        assertTrue(model.enabled.get())
    }

    @Test
    fun `Stop and Restart act on the service's containers, Logs and Shell go to the Docker tool`() {
        val web1 = container("shop-web-1", project = "shop", service = "web")
        val web2 = container("shop-web-2", project = "shop", service = "web", state = ContainerState.EXITED)
        cli.containers = listOf(web1, web2)
        val model = loaded()
        val web = model.services.get().first { it.name == "web" }

        model.perform(web, ServiceAction.STOP)
        model.perform(web, ServiceAction.RESTART)
        model.perform(web, ServiceAction.LOGS)
        model.perform(web, ServiceAction.SHELL)

        waitFor { cli.calls.count { it.startsWith("act") } == 2 }
        assertEquals(
            setOf("act STOP id-shop-web-1", "act RESTART id-shop-web-1"),
            cli.calls
                .filter {
                    it.startsWith("act")
                }.toSet(),
        )
        assertEquals(listOf("id-shop-web-1"), host.logs)
        assertEquals(listOf("id-shop-web-1" to "shop-web-1"), host.shells)
    }

    @Test
    fun `a failed row action shows in the error line`() {
        cli.containers = listOf(container("shop-web-1", project = "shop", service = "web"))
        val model = loaded()
        cli.failing = { if (it.startsWith("act")) DockerException("Error: permission denied") else null }

        model.perform(model.services.get().first(), ServiceAction.STOP)
        fx.runUntil { model.error.get() != null }

        assertEquals("✖ Stop web failed: Error: permission denied", model.error.get())
    }

    @Test
    fun `closing unsubscribes and drops a late result`() {
        val model = model()

        model.close()
        fx.runUntil { fx.ran == 1 }

        assertTrue(cli.eventStreams.single().closed)
        assertEquals(ConfigLoad.Loading, model.config.get())
        model.up()
        assertNull(host.started.firstOrNull())
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached within 5 s" }
            Thread.sleep(5) // poll interval
        }
    }

    /** Records what the model asks of the view; the answers are settable. */
    private class FakeHost : ComposeHost {
        override var isDirty = false
        var unsavedAnswer = UnsavedChoice.CANCEL
        var saves = true
        var saveCalls = 0
        var onSave: () -> Unit = {}

        /** Whether [start] starts the job (`false`: the question about the running one was answered no). */
        var starts = true
        var confirmAnswer = true
        val unsavedQuestions = mutableListOf<String>()
        val questions = mutableListOf<String>()
        val started = mutableListOf<Triple<ComposeJob, String, Map<String, String>>>()
        val logs = mutableListOf<String>()
        val shells = mutableListOf<Pair<String, String>>()

        override fun askUnsaved(question: String): UnsavedChoice {
            unsavedQuestions += question
            return unsavedAnswer
        }

        override fun save(): Boolean {
            saveCalls++
            if (saves) {
                isDirty = false
                onSave()
            }
            return saves
        }

        override fun confirm(question: String): Boolean {
            questions += question
            return confirmAnswer
        }

        override fun start(
            job: ComposeJob,
            command: () -> String,
            environment: Map<String, String>,
        ): Boolean {
            started += Triple(job, command(), environment)
            return starts
        }

        override fun showLogs(id: String) {
            logs += id
        }

        override fun openShell(
            id: String,
            name: String,
        ) {
            shells += id to name
        }
    }
}
