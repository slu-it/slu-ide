package dev.sluide.docker.tab.stats

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.StatsSample
import dev.sluide.docker.tab.TabTestSession
import dev.sluide.docker.tab.awaitTrue
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.shape.Polyline
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wiring of [StatsView]: the stream opened through the session only for a running container, samples into the
 * labels and sparklines; the numbers are in [StatsHistoryTest]. Needs a scene for the labels; see the `uiTest` Gradle
 * task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class StatsViewUiTest {
    private lateinit var env: TabTestSession
    private lateinit var view: StatsView
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
    }

    private fun open(
        state: ContainerState,
        robot: FxRobot,
    ) {
        env = TabTestSession(state)
        robot.interact {
            view = StatsView(env.session)
            stage.scene = Scene(view.node as Parent, 500.0, 500.0)
            stage.show()
        }
    }

    @AfterEach
    fun close() {
        if (::view.isInitialized) view.dispose()
        if (::env.isInitialized) env.close()
    }

    private fun text(
        robot: FxRobot,
        id: String,
    ): String {
        var result = ""
        robot.interact { result = robot.lookup("#$id").queryAs(Label::class.java).text }
        return result
    }

    /** The number of points of the first sparkline line (CPU). */
    private fun cpuPoints(robot: FxRobot): Int {
        var count = 0
        robot.interact {
            count =
                robot
                    .lookup(".sparkline-line")
                    .queryAllAs(Polyline::class.java)
                    .first()
                    .points.size / 2
        }
        return count
    }

    private fun sample(
        cpu: Double,
        received: Long = 0,
    ) = StatsSample(cpu, 214L * 1024 * 1024, 8L * 1024 * 1024 * 1024, received, 0, 0, 0, pids = 7)

    private fun running(robot: FxRobot) {
        open(ContainerState.RUNNING, robot)
        robot.interact { view.show("id-web") }
        awaitTrue("the stream") { env.cli.statsStreams.size == 1 }
    }

    @Test
    fun `emitted samples update the labels and the sparklines`(robot: FxRobot) {
        running(robot)
        assertEquals("", text(robot, "stats-note"))

        env.cli.statsStreams[0].emit(sample(12.43))
        awaitTrue("the CPU") { text(robot, "stats-cpu") == "12.4 %" }
        assertEquals("214 MiB / 8.0 GiB (2.6 %)", text(robot, "stats-memory"))
        assertEquals("PIDs: 7", text(robot, "stats-pids"))
        assertEquals("–", text(robot, "stats-network"), "a rate needs two samples")
        assertEquals(1, cpuPoints(robot))

        env.cli.statsStreams[0].emit(sample(1.0, received = 10_000_000))
        awaitTrue("the network") { text(robot, "stats-network").startsWith("↓ ") }
        assertEquals(2, cpuPoints(robot))
    }

    @Test
    fun `a container that is not running shows a note and opens no stream`(robot: FxRobot) {
        open(ContainerState.EXITED, robot)
        robot.interact { view.show("id-web") }
        assertEquals("Not running", text(robot, "stats-note"))
        Thread.sleep(200) // no signal for "nothing happens": the stream would open on a thread of its own
        assertTrue(env.cli.statsStreams.isEmpty())

        env.changeState(ContainerState.RUNNING)
        awaitTrue("the stream") { env.cli.statsStreams.size == 1 }
        awaitTrue("the note gone") { text(robot, "stats-note") == "" }

        env.changeState(ContainerState.EXITED)
        awaitTrue("the note again") { text(robot, "stats-note") == "Not running" }
        assertTrue(env.cli.statsStreams[0].closed)
    }

    @Test
    fun `showing another container closes the stream`(robot: FxRobot) {
        running(robot)
        env.cli.statsStreams[0].emit(sample(5.0))
        awaitTrue("the CPU") { text(robot, "stats-cpu") == "5.0 %" }

        robot.interact { view.show("id-other") }
        awaitTrue("closed") { env.cli.statsStreams[0].closed }
        assertEquals("–", text(robot, "stats-cpu"))
        assertEquals("Not running", text(robot, "stats-note"), "the other container is not in the list")
    }

    @Test
    fun `showing none closes the stream`(robot: FxRobot) {
        running(robot)
        env.cli.statsStreams[0].emit(sample(5.0))
        awaitTrue("the CPU") { text(robot, "stats-cpu") == "5.0 %" }

        robot.interact { view.show(null) }
        awaitTrue("closed") { env.cli.statsStreams[0].closed }
        assertEquals("–", text(robot, "stats-cpu"))
        assertEquals("", text(robot, "stats-note"))
    }

    @Test
    fun `a stream that ends while the container runs shows a note and a restart opens it again`(robot: FxRobot) {
        running(robot)
        env.cli.statsStreams[0].end()
        awaitTrue("the note") { text(robot, "stats-note") == "Stream ended" }

        env.changeState(ContainerState.RUNNING, status = "Up Less than a second")
        awaitTrue("the reopened stream") { env.cli.statsStreams.size == 2 }
        awaitTrue("the note gone") { text(robot, "stats-note") == "" }
    }
}
