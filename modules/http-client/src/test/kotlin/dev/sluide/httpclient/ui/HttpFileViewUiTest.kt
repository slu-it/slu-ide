package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewers
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpFileViewer
import dev.sluide.httpclient.send.TestServer
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCharacterCombination
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `.http` files in their view. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class HttpFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val viewer = HttpFileViewer()
    private val server = TestServer()
    private val session = HttpClientSession()

    @AfterEach
    fun stop() {
        session.close()
        server.close()
    }

    /** A host of a window with [session]. */
    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = session.takeIf(type::isInstance)?.let(type::cast)
        }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 700.0, 400.0)
        stage.show()
    }

    private fun open(
        file: Path,
        robot: FxRobot,
    ): HttpFileView {
        val view = WaitForAsyncUtils.asyncFx<HttpFileView> { viewer.open(file) as HttpFileView }.get()
        robot.interact { container.children.setAll(view.node) }
        WaitForAsyncUtils.waitForFxEvents()
        return view
    }

    private fun FxRobot.area() = lookup(".code-area").queryAs(CodeArea::class.java)

    /** Opens [text] as `api.http` in a view of a window with the [session]. */
    private fun openHosted(
        text: String,
        robot: FxRobot,
    ): HttpFileView =
        open(dir.resolve("api.http").apply { writeText(text) }, robot).also { robot.interact { it.host = host } }

    /** Waits until the response's status is not [running][ResponsePane.running] (and not empty) any more. */
    private fun awaitResult(view: HttpFileView): String {
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) {
            view.response.status.text
                .let { it.isNotEmpty() && it != "Running…" }
        }
        WaitForAsyncUtils.waitForFxEvents()
        return view.response.status.text
    }

    private fun twoRequests() =
        """
        |### First
        |GET ${server.base}/echo?n=1
        |
        |### Second
        |POST ${server.base}/echo?n=2
        |Authorization: Bearer secret
        |
        |hello
        |
        """.trimMargin()

    @Test
    fun `opens http and rest files that are text`() {
        assertTrue(viewer.canOpen(dir.resolve("api.http").apply { writeText("GET http://x\n") }))
        assertTrue(viewer.canOpen(dir.resolve("api.REST").apply { writeText("GET http://x\n") }))
        assertFalse(viewer.canOpen(dir.resolve("bin.http").apply { writeBytes(byteArrayOf(0, 1, 2)) }))
        assertFalse(viewer.canOpen(dir.resolve("api.txt").apply { writeText("GET http://x\n") }))
        assertTrue(FileViewers(listOf(viewer), DefaultTextFileViewer()).showsDiffs(viewer))
    }

    @Test
    fun `shows the file highlighted, editable and savable`(robot: FxRobot) {
        val file = dir.resolve("api.http").apply { writeText("### Users\nGET https://example.com/users\n") }
        val view = open(file, robot)
        val area = robot.area()

        assertEquals("### Users\nGET https://example.com/users\n", area.text)
        assertEquals(listOf("comment"), area.getStyleOfChar(1).toList())
        assertEquals(listOf("keyword"), area.getStyleOfChar(11).toList())
        assertIs<HttpFileView>(view)

        robot.interact { area.insertText(area.length, "Accept: */*\n") }
        assertTrue(view.dirty.get())
        robot.interact { view.save() }
        assertFalse(view.dirty.get())
        assertEquals("### Users\nGET https://example.com/users\nAccept: */*\n", file.readText())
    }

    @Test
    fun `without a window session nothing runs`(robot: FxRobot) {
        val view = open(dir.resolve("api.http").apply { writeText("GET http://x\n") }, robot)

        assertTrue(view.runButton.isDisable)
        assertEquals("Requests run in an IDE window", view.runButton.tooltip.text)
        assertFalse(view.response.node in view.split.items)
    }

    @Test
    fun `runs the request at the caret from the toolbar and shows the response`(robot: FxRobot) {
        val view = openHosted(twoRequests(), robot)
        assertFalse(view.runButton.isDisable)

        robot.interact {
            robot.area().moveTo(7, 0)
            view.runButton.fire()
        }

        assertEquals("200 OK", awaitResult(view))
        assertTrue(view.response.node in view.split.items)
        assertTrue("http-status-success" in view.response.status.styleClass)
        assertTrue(
            view.response.body.text
                .startsWith("POST\n"),
            view.response.body.text,
        )
        assertTrue(
            view.response.body.text
                .endsWith("\n\nhello"),
            view.response.body.text,
        )
        assertTrue("authorization: Bearer secret" in view.response.body.text)
        assertTrue(
            view.response.details.text
                .endsWith("${server.base}/echo?n=2"),
            view.response.details.text,
        )
        assertTrue(
            view.response.request.text
                .startsWith("POST ${server.base}/echo?n=2 HTTP/1.1\n"),
        )
        assertTrue("Authorization: Bearer ••••••" in view.response.request.text, view.response.request.text)
        assertTrue("Content-length" in view.response.headers.text || "content-length" in view.response.headers.text)
    }

    @Test
    fun `sends unsaved edits and runs with Shortcut+Enter`(robot: FxRobot) {
        val view = openHosted(twoRequests(), robot)

        robot.interact {
            val area = robot.area()
            area.insertText(2, 0, "X-Unsaved: yes\n")
            area.moveTo(1, 0)
            Event.fireEvent(area, keyPress(KeyCode.ENTER))
        }

        assertEquals("200 OK", awaitResult(view))
        assertTrue(
            view.response.body.text
                .startsWith("GET\n"),
            view.response.body.text,
        )
        assertTrue("x-unsaved: yes" in view.response.body.text, view.response.body.text)
        assertTrue(view.dirty.get())
    }

    @Test
    fun `run icons beside the request lines run their request`(robot: FxRobot) {
        val view = openHosted(twoRequests(), robot)
        WaitForAsyncUtils.waitForFxEvents()

        val icons = robot.lookup(".http-run-icon").queryAll<Node>().sortedBy { it.localToScene(0.0, 0.0).y }
        assertEquals(2, icons.size)
        robot.interact { Event.fireEvent(icons[1], click(icons[1])) }

        assertEquals("200 OK", awaitResult(view))
        assertTrue(
            view.response.body.text
                .startsWith("POST\n"),
            view.response.body.text,
        )
        assertEquals(0, robot.area().currentParagraph, "the caret stays")
    }

    @Test
    fun `run icons follow edits`(robot: FxRobot) {
        openHosted("GET http://x\n", robot)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, robot.lookup(".http-run-icon").queryAll<Node>().size)

        robot.interact { robot.area().appendText("\n###\nGET http://y\n") }

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { robot.lookup(".http-run-icon").queryAll<Node>().size == 2 }
    }

    @Test
    fun `unresolved variables are errors and nothing is sent`(robot: FxRobot) {
        val view = openHosted("GET ${server.base}/echo/{{missing}}\n", robot)

        robot.interact { view.runButton.fire() }

        assertEquals("Failed", awaitResult(view))
        assertTrue("missing" in view.response.error.text, view.response.error.text)
        assertSame(view.response.error, view.response.bodyTab.content)
        assertTrue("http-status-failure" in view.response.status.styleClass)
    }

    @Test
    fun `a running request can be cancelled`(robot: FxRobot) {
        val reached = CountDownLatch(1)
        server.handle("/slow") {
            reached.countDown()
            server.released.await(30, TimeUnit.SECONDS)
        }
        val view = openHosted("GET ${server.base}/slow\n", robot)

        robot.interact { view.runButton.fire() }
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        assertFalse(view.cancelButton.isDisable)
        robot.interact { view.cancelButton.fire() }

        assertEquals("Cancelled", awaitResult(view))
        assertTrue(view.cancelButton.isDisable)
    }

    @Test
    fun `runs with the environment chosen for the file in the window`(robot: FxRobot) {
        dir.resolve("http-client.env.json").writeText(
            """{"dev": {"host": "${server.base}"}, "local": {"host": "http://127.0.0.1:1"}}""",
        )
        dir.resolve("http-client.private.env.json").writeText("""{"dev": {"token": "private-token"}}""")
        val text = "GET {{host}}/echo\nX-Token: {{token}}\n"
        val view = openHosted(text, robot)

        awaitEnvironments(view, "No environment", "dev", "local")
        assertEquals("No environment", view.environments.value)
        robot.interact { view.environments.value = "dev" }
        assertEquals("dev", session.environment(dir.resolve("api.http")))
        robot.interact { view.runButton.fire() }

        assertEquals("200 OK", awaitResult(view))
        assertTrue("x-token: private-token" in view.response.body.text, view.response.body.text)
        // Another view of the file in the same window starts with the same environment.
        val other = openHosted(text, robot)
        awaitEnvironments(other, "No environment", "dev", "local")
        assertEquals("dev", other.environments.value)
    }

    @Test
    fun `environment files are read again on each run and their problems shown`(robot: FxRobot) {
        val envFile = dir.resolve("http-client.env.json").apply { writeText("""{"dev": {"host": "x"}}""") }
        val view = openHosted("GET ${server.base}/echo\n", robot)
        assertFalse(view.warning.isVisible)

        envFile.writeText("""{"dev": {"host": "x"}, "staging": {"host": "y"}, "${'$'}shared": {}}""")
        robot.interact { view.runButton.fire() }
        awaitResult(view)

        awaitEnvironments(view, "No environment", "dev", "staging")
        assertTrue(view.warning.isVisible)
        assertEquals("\"${'$'}shared\" is not supported and ignored", view.warning.text)

        envFile.writeText("{ not json")
        robot.interact { view.runButton.fire() }
        awaitResult(view)
        awaitEnvironments(view, "No environment")
        assertTrue(view.warning.text.startsWith("http-client.env.json: "), view.warning.text)
    }

    /** Waits until the environment files were read (off the FX thread) and [names] are offered. */
    private fun awaitEnvironments(
        view: HttpFileView,
        vararg names: String,
    ) {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.environments.items == names.toList() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun click(node: Node) =
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            0.0,
            0.0,
            0.0,
            0.0,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            true,
            false,
            false,
            null,
        ).copyFor(node, node)

    @Test
    fun `comments lines with #`(robot: FxRobot) {
        val view = open(dir.resolve("api.http").apply { writeText("GET http://x\n") }, robot)
        val toggle = KeyCharacterCombination("/", KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_ANY)
        val (code, shift) =
            WaitForAsyncUtils
                .asyncFx<Pair<KeyCode, Boolean>> {
                    KeyCode.entries.firstNotNullOf { code ->
                        listOf(false, true).firstOrNull { toggle.match(keyPress(code, it)) }?.let { code to it }
                    }
                }.get()

        robot.press(robot.area(), code, shortcut = true, shift = shift)

        assertEquals("# GET http://x\n", robot.area().text)
        assertTrue(view.dirty.get())
    }

    @Test
    fun `follows the file when it is moved, with its environment`(robot: FxRobot) {
        val session = HttpClientSession()
        val old = dir.resolve("api.http").apply { writeText("GET http://x\n") }
        val view = open(old, robot)
        robot.interact {
            view.host =
                object : FileViewHost {
                    override fun openFile(file: Path) = false

                    override fun openWebPage(uri: URI) {}

                    override fun <T : Any> windowService(type: Class<T>): T? =
                        session.takeIf(type::isInstance)?.let(type::cast)
                }
        }
        session.chooseEnvironment(old, "dev")
        val new = dir.resolve("users.http")
        Files.move(old, new)

        assertTrue(view.moved(new))
        robot.interact {
            robot.area().insertText(0, "# moved\n")
            view.save()
        }

        assertEquals(new, view.file)
        assertEquals("# moved\nGET http://x\n", new.readText())
        assertEquals("dev", session.environment(new))
        assertNull(session.environment(old))
        session.close()
    }

    @Test
    fun `Shortcut+Enter outside the text runs nothing`(robot: FxRobot) {
        val view = openHosted(twoRequests(), robot)

        robot.press(view.runButton, KeyCode.ENTER, shortcut = true)

        assertFalse(view.response.node in view.split.items)
    }

    @Test
    fun `a run without a request replaces the running one`(robot: FxRobot) {
        val reached = CountDownLatch(1)
        server.handle("/slow") {
            reached.countDown()
            server.released.await(30, TimeUnit.SECONDS)
        }
        val view = openHosted("GET ${server.base}/slow\n\n###\n@v = 1\n", robot)
        robot.interact { view.run(0) }
        assertTrue(reached.await(10, TimeUnit.SECONDS))

        robot.interact { view.run(3) }
        WaitForAsyncUtils.sleep(500, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals("Failed", view.response.status.text)
        assertEquals("There is no request at line 4", view.response.error.text)
        assertTrue(view.cancelButton.isDisable)
    }
}
