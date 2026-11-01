package dev.sluide.docker.tab.logs

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.tab.TabTestSession
import dev.sluide.docker.tab.awaitTrue
import dev.sluide.docker.tab.onFx
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wiring of [LogView]: streams opened and closed through the session, lines from the reader thread into the
 * list, the toolbar's controls, scrolling and the reopening after a restart; the rules are in [LogTextTest] and
 * [dev.sluide.docker.tab.HandOffQueueTest]. Needs a scene for the list and its controls; see the `uiTest` Gradle task.
 * Scroll and key events are fired at the nodes, not typed.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class LogViewUiTest {
    private val handOffs = mutableListOf<Runnable>()
    private var manual = false
    private lateinit var env: TabTestSession
    private lateinit var view: LogView
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
    }

    private fun open(
        robot: FxRobot,
        state: ContainerState = ContainerState.RUNNING,
        manualHandOff: Boolean = false,
        maxLines: Int = MAX_LOG_LINES,
    ) {
        manual = manualHandOff
        env = TabTestSession(state)
        robot.interact {
            view = LogView(env.session, { if (manual) handOffs += it else Platform.runLater(it) }, maxLines)
            stage.scene = Scene(view.node as Parent, 600.0, 400.0)
            stage.show()
        }
    }

    @AfterEach
    fun close() {
        if (::view.isInitialized) view.dispose()
        if (::env.isInitialized) env.close()
    }

    private fun list(robot: FxRobot) = robot.lookup("#log-list").queryAs(ListView::class.java)

    private fun texts(robot: FxRobot): List<String> {
        var result = emptyList<String>()
        robot.interact { result = list(robot).items.map { (it as LogLine).text } }
        return result
    }

    private fun cellTexts(robot: FxRobot): List<String> {
        var result = emptyList<String>()
        robot.interact {
            result = robot.lookup("#log-list .list-cell").queryAllAs(ListCell::class.java).mapNotNull { it.text }
        }
        return result
    }

    private fun show(
        robot: FxRobot,
        id: String?,
    ) = robot.interact { view.show(id) }

    private fun runHandOffs(robot: FxRobot) =
        robot.interact { while (handOffs.isNotEmpty()) handOffs.removeFirst().run() }

    /** The container shown, with its log stream open. */
    private fun shown(robot: FxRobot) {
        show(robot, "id-web")
        awaitTrue("the stream") { env.cli.logStreams.size == 1 }
    }

    private fun ToggleButton.select(robot: FxRobot) = robot.interact { isSelected = true }

    @Test
    fun `show opens a log stream with the default tail and the lines appear`(robot: FxRobot) {
        open(robot)
        shown(robot)
        assertTrue("logs id-web tail=1000" in env.cli.calls)

        env.cli.logStreams[0].emit("2026-11-01T13:44:03Z \u001B[31mhello\u001B[0m")
        env.cli.logStreams[0].emit("2026-11-01T13:44:04Z world")
        awaitTrue("the lines") { texts(robot) == listOf("hello", "world") }
    }

    @Test
    fun `a burst of lines is handed over once`(robot: FxRobot) {
        open(robot, manualHandOff = true)
        shown(robot)
        repeat(500) { env.cli.logStreams[0].emit("line $it") }

        assertEquals(1, handOffs.size)
        assertEquals(emptyList(), texts(robot), "nothing shown before the hand-off runs")
        runHandOffs(robot)
        assertEquals(500, texts(robot).size)
    }

    @Test
    fun `lines still queued when another container is shown are dropped`(robot: FxRobot) {
        open(robot, manualHandOff = true)
        shown(robot)
        repeat(3) { env.cli.logStreams[0].emit("old $it") }

        show(robot, "id-other")
        runHandOffs(robot)
        assertEquals(emptyList(), texts(robot))
    }

    @Test
    fun `lines still queued when nothing is shown any more are dropped`(robot: FxRobot) {
        open(robot, manualHandOff = true)
        shown(robot)
        repeat(3) { env.cli.logStreams[0].emit("old $it") }

        show(robot, null)
        runHandOffs(robot)
        assertEquals(emptyList(), texts(robot))
    }

    @Test
    fun `showing another container closes the previous stream and null closes the last`(robot: FxRobot) {
        open(robot)
        shown(robot)
        env.cli.logStreams[0].emit("old")
        awaitTrue("the line") { texts(robot) == listOf("old") }

        show(robot, "id-other")
        awaitTrue("the second stream") { env.cli.logStreams.size == 2 }
        assertTrue(env.cli.logStreams[0].closed)
        assertEquals(emptyList(), texts(robot))

        show(robot, null)
        awaitTrue("the second stream closed") { env.cli.logStreams[1].closed }
    }

    @Test
    fun `the filter hides lines that do not match, also lines that arrive later`(robot: FxRobot) {
        open(robot)
        shown(robot)
        listOf("INFO start", "ERROR boom", "INFO done").forEach { env.cli.logStreams[0].emit(it) }
        awaitTrue("the lines") { texts(robot).size == 3 }

        robot.interact { robot.lookup("#log-filter").queryAs(TextField::class.java).text = "error" }
        assertEquals(listOf("ERROR boom"), texts(robot))

        env.cli.logStreams[0].emit("INFO later")
        env.cli.logStreams[0].emit("error again")
        awaitTrue("the matching line") { texts(robot) == listOf("ERROR boom", "error again") }

        robot.interact { robot.lookup("#log-filter").queryAs(TextField::class.java).text = "" }
        assertEquals(5, texts(robot).size)
    }

    @Test
    fun `changing the tail reopens the stream`(robot: FxRobot) {
        open(robot)
        shown(robot)

        robot.interact {
            @Suppress("UNCHECKED_CAST")
            (robot.lookup("#log-tail").queryAs(ComboBox::class.java) as ComboBox<TailChoice>).value = TailChoice.ALL
        }
        awaitTrue("the reopened stream") { env.cli.logStreams.size == 2 }
        assertTrue(env.cli.logStreams[0].closed)
        assertTrue("logs id-web tail=null" in env.cli.calls)
    }

    @Test
    fun `an ended stream shows a note and the container running again reopens it`(robot: FxRobot) {
        open(robot, ContainerState.EXITED)
        shown(robot)
        env.cli.logStreams[0].emit("last words")
        env.cli.logStreams[0].end()
        awaitTrue("the note") { texts(robot) == listOf("last words", "— container stopped —") }

        env.changeState(ContainerState.RUNNING)
        awaitTrue("the reopened stream") { env.cli.logStreams.size == 2 }
        assertEquals(emptyList(), texts(robot), "reloaded from scratch")
    }

    @Test
    fun `a restart that the list never showed as stopped reopens the stream too`(robot: FxRobot) {
        open(robot)
        shown(robot)
        env.cli.logStreams[0].end()
        awaitTrue("the note") { texts(robot) == listOf("— container stopped —") }

        env.changeState(ContainerState.RUNNING, status = "Up Less than a second")
        awaitTrue("the reopened stream") { env.cli.logStreams.size == 2 }
    }

    @Test
    fun `only the last lines up to the cap stay, with and without a filter`(robot: FxRobot) {
        open(robot, manualHandOff = true, maxLines = 3)
        shown(robot)

        // One batch per step: lines of an earlier batch must be trimmed from the list when later ones push them out.
        fun emit(vararg lines: String) {
            lines.forEach { env.cli.logStreams[0].emit(it) }
            runHandOffs(robot)
        }
        emit("line 1", "line 2")
        emit("line 3", "line 4", "line 5")
        assertEquals(listOf("line 3", "line 4", "line 5"), texts(robot))

        robot.interact { robot.lookup("#log-filter").queryAs(TextField::class.java).text = "keep" }
        emit("keep 1")
        emit("drop", "drop", "drop", "keep 2")
        assertEquals(listOf("keep 2"), texts(robot))
    }

    @Test
    fun `the timestamps toggle shows the time of each line`(robot: FxRobot) {
        open(robot)
        shown(robot)
        env.cli.logStreams[0].emit("2026-11-01T13:44:03.123Z hello")
        env.cli.logStreams[0].emit("no time here")
        awaitTrue("the lines") { cellTexts(robot).toSet() == setOf("hello", "no time here") }

        robot.lookup("#log-timestamps").queryAs(ToggleButton::class.java).select(robot)
        val time = formatLogTime(Instant.parse("2026-11-01T13:44:03.123Z"))
        awaitTrue("the time") { cellTexts(robot).toSet() == setOf("$time  hello", "no time here") }
    }

    @Test
    fun `clear empties the view and the stream goes on`(robot: FxRobot) {
        open(robot)
        shown(robot)
        env.cli.logStreams[0].emit("before")
        awaitTrue("the line") { texts(robot) == listOf("before") }

        robot.interact { robot.lookup("#log-clear").queryAs(Button::class.java).fire() }
        assertEquals(emptyList(), texts(robot))
        assertEquals(false, env.cli.logStreams[0].closed)

        env.cli.logStreams[0].emit("after")
        awaitTrue("the next line") { texts(robot) == listOf("after") }
    }

    @Test
    fun `copy puts the selected lines on the clipboard, from the menu and with the shortcut`(robot: FxRobot) {
        open(robot)
        shown(robot)
        listOf("one", "two", "three").forEach { env.cli.logStreams[0].emit(it) }
        awaitTrue("the lines") { texts(robot).size == 3 }
        robot.interact { list(robot).selectionModel.selectIndices(0, 2) }

        fun clipboard(): String? {
            var text: String? = null
            robot.interact { text = Clipboard.getSystemClipboard().string }
            return text
        }
        robot.interact { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString("empty") }) }
        robot.interact {
            list(robot)
                .contextMenu.items
                .single { it.text == "Copy" }
                .fire()
        }
        assertEquals("one\nthree", clipboard())

        robot.interact { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString("empty") }) }
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        robot.interact {
            Event.fireEvent(
                list(robot),
                KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, !mac, false, mac),
            )
        }
        assertEquals("one\nthree", clipboard())
    }

    @Test
    fun `scrolling up turns follow off and scrolling to the end turns it on`(robot: FxRobot) {
        open(robot)
        shown(robot)
        (1..200).forEach { env.cli.logStreams[0].emit("line $it") }
        awaitTrue("the lines") { texts(robot).size == 200 }
        val follow = robot.lookup("#log-follow").queryAs(ToggleButton::class.java)
        assertTrue(onFx { follow.isSelected })

        fun wheel(deltaY: Double) =
            robot.interact {
                val cell = robot.lookup("#log-list .list-cell").queryAllAs(ListCell::class.java).first()
                Event.fireEvent(cell, wheelEvent(deltaY))
            }
        wheel(300.0)
        awaitTrue("follow off") { !onFx { follow.isSelected } }

        wheel(-100_000.0)
        awaitTrue("follow on again") { onFx { follow.isSelected } }
    }

    @Test
    fun `a wheel notch up is not undone by a line that is waiting to be handed over`(robot: FxRobot) {
        open(robot, manualHandOff = true)
        shown(robot)
        (1..200).forEach { env.cli.logStreams[0].emit("line $it") }
        runHandOffs(robot)
        val follow = robot.lookup("#log-follow").queryAs(ToggleButton::class.java)
        assertTrue(onFx { follow.isSelected })

        env.cli.logStreams[0].emit("one more") // waits for its hand-off
        robot.interact {
            val cell = robot.lookup("#log-list .list-cell").queryAllAs(ListCell::class.java).first()
            Event.fireEvent(cell, wheelEvent(40.0))
            while (handOffs.isNotEmpty()) handOffs.removeFirst().run()
        }
        assertEquals(false, onFx { follow.isSelected })
        assertEquals(201, texts(robot).size)
    }

    private fun wheelEvent(deltaY: Double) =
        ScrollEvent(
            ScrollEvent.SCROLL,
            10.0,
            10.0,
            10.0,
            10.0,
            false,
            false,
            false,
            false,
            true,
            false,
            0.0,
            deltaY,
            0.0,
            deltaY,
            ScrollEvent.HorizontalTextScrollUnits.NONE,
            0.0,
            ScrollEvent.VerticalTextScrollUnits.NONE,
            0.0,
            0,
            null,
        )
}
