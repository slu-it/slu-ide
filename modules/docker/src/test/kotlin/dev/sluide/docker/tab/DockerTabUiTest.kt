package dev.sluide.docker.tab

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.Docker
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.details.StreamView
import dev.sluide.docker.tab.details.StreamViews
import dev.sluide.docker.tab.tree.ResourceKey
import dev.sluide.docker.tab.tree.TreeRow
import dev.sluide.editorarea.EditorTab
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TabPane
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.GridPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The real Docker tab on a session over a [FakeDockerCli]: one test per connection between the view and its logic
 * (tree from the model, selection to details, buttons to actions, engine note and Retry, details tab to the Logs
 * view, showContainer, dispose); the logic's cases are unit tests ([DockerTabModelTest], [TabActionsTest]). The
 * session's work runs at once on the thread that asks for it, so the tests drive it on the FX thread. Needs a display,
 * see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DockerTabUiTest {
    /** Records what the tab asks it to show. */
    private class RecordingView : StreamView {
        val shown = CopyOnWriteArrayList<String?>()
        var disposed = false
        override val node: Node = StackPane()

        override fun show(containerId: String?) {
            shown += containerId
        }

        override fun dispose() {
            disposed = true
        }
    }

    private val cli = FakeDockerCli()
    private val scheduler = ManualScheduler()
    private val session = fakeSession(cli, scheduler)
    private val logs = RecordingView()
    private val web = container("web", image = "nginx:1.27")
    private val old = container("old", state = ContainerState.EXITED)
    private lateinit var docker: Docker
    private lateinit var tab: EditorTab
    private var disposed = false

    @Start
    fun start(stage: Stage) {
        cli.containers = listOf(web, old)
        cli.images = listOf(image("nginx", "1.27"))
        cli.volumes = listOf(volume("data"))
        cli.networks = listOf(network("bridge"))
        cli.details =
            mapOf(
                web.id to
                    ContainerDetails(
                        web.id,
                        "web",
                        "nginx:1.27",
                        null,
                        ContainerState.RUNNING,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyMap(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    ),
            )
        docker =
            Docker(
                session,
                BackgroundTasks.forTests(),
                { _, _, _, _ -> error("no shells here") },
                Path.of("/home/me"),
                streamViews = StreamViews(logs = { logs }, stats = { RecordingView() }),
            )
        tab = docker.createTab()
        stage.scene = Scene(tab.content as Parent, 1000.0, 700.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        if (!disposed) onFx { tab.dispose() }
        session.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun tree(robot: FxRobot) = robot.lookup(".docker-tree").queryAs(TreeView::class.java) as TreeView<TreeRow>

    private fun select(
        robot: FxRobot,
        group: Int,
        label: String,
    ) {
        val tree = tree(robot)
        robot.interact {
            tree.selectionModel.select(
                tree.root.children[group]
                    .children
                    .first { it.value.label == label },
            )
        }
    }

    private fun overviewTexts(robot: FxRobot) =
        onFx { robot.lookup(".docker-overview .label").queryAllAs(Label::class.java).map { it.text } }

    private fun detailsTabs(robot: FxRobot) =
        onFx {
            robot.lookup(".tab-pane").queryAllAs(TabPane::class.java).first { pane ->
                pane.tabs.any { it.text == "Logs" }
            }
        }

    /** Lets the session reload the lists after an event, on the FX thread as the session's hand-off would. */
    private fun reload(robot: FxRobot) =
        robot.interact {
            cli.pushEvent(DockerEvent("container", "die", "x"))
            scheduler.advance(200.milliseconds)
        }

    @Test
    fun `the tree shows the four groups from the session's lists`(robot: FxRobot) {
        val tree = tree(robot)

        assertEquals(
            listOf("Containers (2)", "Images (1)", "Volumes (1)", "Networks (1)"),
            onFx { tree.root.children.map { it.value.label } },
        )
        assertEquals(
            listOf("old", "web"),
            onFx {
                tree.root.children[0]
                    .children
                    .map { it.value.label }
            },
        )
    }

    @Test
    fun `selecting a container shows its Overview, and its Stop button stops it`(robot: FxRobot) {
        select(robot, 0, "web")

        awaitTrue("the overview") { "nginx:1.27" in overviewTexts(robot) }
        val stop =
            onFx { robot.lookup(".docker-actions .button").queryAllAs(Button::class.java).single { it.text == "Stop" } }
        robot.interact { stop.fire() }

        awaitTrue("the stop") { "act STOP ${web.id}" in cli.calls }
    }

    /** The layout facts of the shown container's details header and Overview. */
    private class HeaderLayout(
        val detailsWidth: Double,
        val buttonCount: Int,
        val labelCount: Int,
        val rows: Set<Double>,
        val outside: List<String>,
        val narrowButtons: List<String>,
        val narrowLabels: List<String>,
    )

    private fun headerLayout(robot: FxRobot): HeaderLayout =
        onFx {
            (tab.content as Parent).layout()
            val header = robot.lookup(".docker-details-header").queryAs(Region::class.java)
            val top = header.localToScene(header.boundsInLocal)
            val tabs =
                robot.lookup(".tab-pane").queryAllAs(TabPane::class.java).first {
                    it.tabs.any { t ->
                        t.text ==
                            "Logs"
                    }
                }
            val tabsTop = tabs.localToScene(tabs.boundsInLocal).minY
            val buttons = robot.lookup(".docker-actions .button").queryAllAs(Button::class.java).toList()
            val labels = robot.lookup(".docker-overview-label").queryAllAs(Label::class.java).toList()
            val split = robot.lookup(".split-pane").queryAs(SplitPane::class.java)
            HeaderLayout(
                split.items[1].layoutBounds.width,
                buttons.size,
                labels.size,
                buttons.map { it.localToScene(it.boundsInLocal).minY }.toSet(),
                buttons
                    .filter {
                        val box = it.localToScene(it.boundsInLocal)
                        box.minX < top.minX || box.maxX > top.maxX || box.minY < top.minY || box.maxY > tabsTop
                    }.map { it.text },
                buttons.filter { it.width < it.prefWidth(-1.0) - 0.5 }.map { it.text },
                labels.filter { it.width < it.prefWidth(-1.0) - 0.5 }.map { it.text },
            )
        }

    @Test
    fun `the details buttons share a row when wide and wrap, whole, when narrow`(robot: FxRobot) {
        select(robot, 0, "web")
        awaitTrue("the overview") { "nginx:1.27" in overviewTexts(robot) }

        val wide = headerLayout(robot)
        assertTrue(wide.buttonCount > 0 && wide.labelCount > 0, "buttons and labels shown")
        assertEquals(1, wide.rows.size, "all buttons in one row: ${wide.rows}")
        assertEquals(emptyList(), wide.outside, "buttons outside the header")

        val split = onFx { robot.lookup(".split-pane").queryAs(SplitPane::class.java) }
        robot.interact { split.setDividerPositions(0.6) }
        val narrow = headerLayout(robot)
        assertTrue(narrow.detailsWidth < 450, "the details pane is narrow: ${narrow.detailsWidth}")
        assertTrue(narrow.rows.size > 1, "the buttons wrapped: ${narrow.rows}")
        assertEquals(emptyList(), narrow.outside, "buttons outside the header")
        assertEquals(emptyList(), narrow.narrowButtons, "truncated buttons")
        assertEquals(emptyList(), narrow.narrowLabels, "truncated labels")
    }

    @Test
    fun `an image's overview follows the containers using it and is not rebuilt when it shows the same`(
        robot: FxRobot,
    ) {
        select(robot, 1, "nginx:1.27")
        awaitTrue("the overview") { "web (running)" in overviewTexts(robot) }

        fun firstRowLabel() =
            onFx {
                robot
                    .lookup(".docker-overview")
                    .queryAs(GridPane::class.java)
                    .children
                    .first()
            }
        val before = firstRowLabel()
        cli.containers = listOf(web, old.copy(status = "Exited (1) 1 second ago"))
        reload(robot)
        assertSame(before, firstRowLabel(), "an unrelated container changed")

        cli.containers = listOf(web.copy(state = ContainerState.EXITED), old)
        reload(robot)
        assertTrue("web (exited)" in overviewTexts(robot), overviewTexts(robot).toString())
    }

    @Test
    fun `an unavailable engine shows its error with Retry, which refreshes like F5`(robot: FxRobot) {
        cli.failing = { if (it == "engine") DockerException("Cannot connect to the Docker daemon") else null }
        robot.interact { session.refresh() }
        val retry = onFx { robot.lookup(".docker-note .button").queryAs(Button::class.java) }
        val engineCalls = cli.count("engine")

        assertTrue(onFx { retry.isVisible && retry.scene != null })
        assertTrue(
            onFx {
                robot.lookup(".docker-note .label").queryAllAs(Label::class.java).any {
                    it.text == "Cannot connect to the Docker daemon"
                }
            },
        )
        robot.interact { retry.fire() }
        assertEquals(engineCalls + 1, cli.count("engine"))

        robot.interact {
            Event.fireEvent(tab.content, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        assertEquals(engineCalls + 2, cli.count("engine"))
    }

    @Test
    fun `the Logs view follows the selected container only while the Logs tab is shown`(robot: FxRobot) {
        select(robot, 0, "web")
        val tabs = detailsTabs(robot)

        robot.interact { tabs.selectionModel.select(tabs.tabs.single { it.text == "Logs" }) }
        assertEquals(web.id, logs.shown.last())

        robot.interact { tabs.selectionModel.select(tabs.tabs.single { it.text == "Overview" }) }
        assertEquals(null, logs.shown.last())
    }

    @Test
    fun `showContainer with logs selects the container and its Logs tab, which follows it`(robot: FxRobot) {
        robot.interact { docker.showContainer(web.id, logs = true) }

        assertEquals(
            ResourceKey.ContainerKey(web.id),
            onFx {
                tree(robot)
                    .selectionModel.selectedItem
                    ?.value
                    ?.key
            },
        )
        val tabs = detailsTabs(robot)
        assertEquals("Logs", onFx { tabs.selectionModel.selectedItem.text })
        assertEquals(web.id, logs.shown.last())
    }

    @Test
    fun `disposing the tab releases the session's subscription and the stream views`(robot: FxRobot) {
        assertFalse(cli.eventStreams.single().closed)

        robot.interact { tab.dispose() }
        disposed = true

        assertTrue(cli.eventStreams.single().closed)
        assertTrue(logs.disposed)
    }
}
