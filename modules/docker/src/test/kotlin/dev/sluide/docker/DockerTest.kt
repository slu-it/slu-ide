package dev.sluide.docker

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.tab.FAKE_ENVIRONMENT
import dev.sluide.docker.tab.fakeSession
import dev.sluide.editorarea.EditorTab
import dev.sluide.terminal.TerminalStartException
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import java.io.IOException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Shell action of [Docker], which round 2's Compose view uses too, with a fake terminal opener. */
class DockerTest {
    private class FakeTab(
        name: String,
    ) : EditorTab {
        override val title: ObservableValue<String> = SimpleStringProperty(name)
        override val tooltip = name
        override val content: Node get() = error("not shown")
    }

    private val session = fakeSession(FakeDockerCli())
    private val started = mutableListOf<List<Any>>()
    private val opened = mutableListOf<String>()
    private var failure: TerminalStartException? = null
    private var tabsShown = 0
    private val home = Path.of("/home/me")
    private val docker =
        Docker(session, BackgroundTasks.forTests(), { folder, name, command, environment ->
            failure?.let { throw it }
            started += listOf(folder, name, command, environment)
            FakeTab(name)
        }, home).apply {
            openTab = { opened += it.title.value }
            showTab = { tabsShown++ }
        }

    @AfterTest
    fun close() = session.close()

    @Test
    fun `Shell opens a terminal tab running docker exec with the CLI's environment, in the project or home folder`() {
        session.refresh()

        docker.openShell("abc", "web-1")
        docker.projectFolder.set(Path.of("/work/shop"))
        docker.openShell("abc", "web-1")

        val command = "'/usr/bin/docker' exec -it 'abc' sh -c 'command -v bash >/dev/null 2>&1 && exec bash || exec sh'"
        assertEquals(
            listOf(
                listOf(home, "web-1 (shell)", command, FAKE_ENVIRONMENT),
                listOf(Path.of("/work/shop"), "web-1 (shell)", command, FAKE_ENVIRONMENT),
            ),
            started,
        )
        assertEquals(listOf("web-1 (shell)", "web-1 (shell)"), opened)
        assertEquals(0, tabsShown)
    }

    @Test
    fun `a shell that cannot start, or no Docker, opens no tab and shows the Docker tab with the error`() {
        docker.openShell("abc", "web-1")
        session.refresh()
        failure = TerminalStartException("zsh could not be started", IOException("gone"))
        docker.openShell("abc", "web-1")

        assertEquals(emptyList(), opened)
        assertEquals(2, tabsShown)
    }
}
