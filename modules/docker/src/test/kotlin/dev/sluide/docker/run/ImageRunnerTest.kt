package dev.sluide.docker.run

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.editorarea.EditorTab
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.terminal.TerminalStartException
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageRunnerTest {
    @TempDir
    lateinit var dir: Path

    private class FakeTab(
        name: String,
    ) : EditorTab {
        override val title: ObservableValue<String> = SimpleStringProperty(name)
        override val tooltip = name
        override val content: Node get() = error("not shown")
    }

    private val cli = FakeDockerCli()
    private val taskLog = mutableListOf<String>()
    private val tasks = BackgroundTasks.forTests { taskLog += it }
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private val errors = mutableListOf<String>()
    private val terminals = mutableListOf<List<Any>>()
    private val opened = mutableListOf<String>()
    private val asked = mutableListOf<RunOptions>()
    private var answer: (RunOptions) -> RunOptions? = { it }
    private var terminalFailure: TerminalStartException? = null
    private var engine: ResolvedDocker? = ResolvedDocker(Path.of("/usr/bin/docker"), mapOf("DOCKER_HOST" to "x"))
    private var project: Path? = null
    private val home = Path.of("/home/me")

    private val data by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val store by lazy { DockerRunStore(data) }

    private val host =
        object : RunHost {
            override val projectFolder get() = project
            override val home get() = this@ImageRunnerTest.home
            override val docker get() = engine
            override val networks = listOf("bridge")
            override val volumes = listOf("data")

            override fun ask(initial: RunOptions): RunOptions? {
                asked += initial
                return answer(initial)
            }

            override fun submit(
                name: String,
                action: (DockerCli) -> String,
            ): CompletableFuture<String> =
                CompletableFuture<String>().also {
                    try {
                        it.complete(action(cli))
                    } catch (e: DockerException) {
                        it.completeExceptionally(e)
                    }
                }

            override fun startTask(
                name: String,
                cancel: () -> Unit,
            ): BackgroundTask = tasks.start(name, cancel)

            override fun openTerminal(
                folder: Path,
                name: String,
                command: String,
                environment: Map<String, String>,
            ): EditorTab {
                terminalFailure?.let { throw it }
                terminals += listOf(folder, name, command, environment)
                return FakeTab(name)
            }

            override fun openTab(tab: EditorTab) {
                opened += tab.title.value
            }

            override fun showContainer(
                id: String,
                logs: Boolean,
            ) {
                shown += id to logs
            }

            override fun showError(message: String) {
                errors += message
            }

            override fun onFx(action: Runnable) = action.run()
        }

    private val runner by lazy { ImageRunner(host, store) }

    private val options =
        RunOptions(
            "nginx:1",
            name = "web",
            ports = listOf("8080:80"),
            environment = listOf(RunEnvVar("TOKEN", "s3cret")),
            volumes = listOf(RunVolume("./html", "/html", readOnly = true)),
        )

    @Test
    fun `a detached run starts through the CLI as a task, with values inline, and shows the container`() {
        project = Path.of("/work/shop")
        answer = { options }

        runner.run("nginx:1")

        assertEquals(
            listOf(
                listOf(
                    "run",
                    "-d",
                    "--name",
                    "web",
                    "-p",
                    "8080:80",
                    "-e",
                    "TOKEN=s3cret",
                    "-v",
                    "/work/shop/html:/html:ro",
                    "nginx:1",
                ),
            ),
            cli.runCalls,
        )
        assertEquals(listOf("0123456789ab" to true), shown)
        assertEquals(emptyList(), errors)
        assertTrue(taskLog.any { "started: Run nginx:1" in it }, taskLog.toString())
        assertTrue(taskLog.any { "finished" in it && "Run nginx:1" in it }, taskLog.toString())
    }

    @Test
    fun `a failing run ends the task as failed and shows the CLI's message`() {
        cli.failing = { if (it.startsWith("run ")) DockerException("port is already allocated") else null }

        runner.run("nginx:1")

        assertEquals(listOf("port is already allocated"), errors)
        assertEquals(emptyList(), shown)
        assertTrue(taskLog.any { "failed" in it && "Run nginx:1" in it }, taskLog.toString())
    }

    @Test
    fun `an attached run opens a terminal tab with the values in private environment variables`() {
        answer = { options.copy(detached = false) }

        runner.run("nginx:1")
        project = Path.of("/work/shop")
        runner.run("nginx:1")

        val command =
            "'/usr/bin/docker' 'run' '--name' 'web' '-p' '8080:80' '-e' \"TOKEN=\$SLUIDE_RUN_ENV_0\" '-v' '%s' 'nginx:1'"
        val environment = mapOf("DOCKER_HOST" to "x", "SLUIDE_RUN_ENV_0" to "s3cret")
        assertEquals(
            listOf(
                listOf(home, "nginx:1 (run)", command.format("/home/me/html:/html:ro"), environment),
                listOf(Path.of("/work/shop"), "nginx:1 (run)", command.format("/work/shop/html:/html:ro"), environment),
            ),
            terminals,
        )
        assertEquals(listOf("nginx:1 (run)", "nginx:1 (run)"), opened)
        assertEquals(emptyList(), cli.runCalls, "nothing runs through the CLI")
    }

    @Test
    fun `an attached run that cannot start shows the error`() {
        answer = { options.copy(detached = false) }
        terminalFailure = TerminalStartException("zsh could not be started", IOException("gone"))

        runner.run("nginx:1")

        assertEquals(emptyList(), opened)
        assertEquals(1, errors.size)
        assertTrue("zsh could not be started" in errors.single())
    }

    @Test
    fun `without Docker the run shows that and still remembers the values`() {
        engine = null
        answer = { options }

        runner.run("nginx:1")

        assertEquals(listOf("Docker is not available"), errors)
        assertEquals(emptyList(), cli.runCalls)
        assertEquals("web", store.load("nginx:9", null).name)
    }

    @Test
    fun `cancelling stores nothing and starts nothing`() {
        answer = { null }

        runner.run("nginx:1")

        assertEquals(emptyList(), cli.runCalls)
        assertEquals(emptyList(), terminals)
        assertNull(data.readShared()?.get("dockerRuns"))
    }

    @Test
    fun `the dialog starts with the remembered values of the repository and the run keeps the asked image`() {
        answer = { options }
        runner.run("nginx:1")
        answer = { it.copy(image = "something-else") }

        runner.run("nginx:2")

        assertEquals(options.copy(image = "nginx:2"), asked.last())
        assertEquals(
            "nginx:2",
            cli.runCalls.last().last(),
        )
    }

    @Test
    fun `an untagged image is run by id and not remembered`() {
        answer = { it.copy(name = "web") }

        runner.run("0123456789ab")

        assertEquals(
            "0123456789ab",
            cli.runCalls.single().last(),
        )
        assertNull(data.readShared()?.get("dockerRuns"))
    }

    @Test
    fun `values are remembered in the project when one is open`() {
        project = dir.resolve("proj").also { it.toFile().mkdirs() }
        answer = { options }

        runner.run("nginx:1")

        assertEquals("web", store.load("nginx:1", project).name)
        assertEquals("", store.load("nginx:1", null).name)
    }
}
