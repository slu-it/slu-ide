package dev.sluide.httpclient.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpFileViewer
import dev.sluide.httpclient.auth.TokenEndpoint
import dev.sluide.httpclient.send.TestServer
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
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
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val session = HttpClientSession(BackgroundTasks.forTests())

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

            private val services = WindowServices().apply { register(HttpClientSession::class.java, session) }

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
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
    fun `a view asked for the focus before it is shown focuses its text once it is`(robot: FxRobot) {
        val other = TextField()
        robot.interact {
            container.children.setAll(other)
            other.requestFocus()
        }
        val file = dir.resolve("api.http").apply { writeText("GET http://x\n") }
        val view = WaitForAsyncUtils.asyncFx<HttpFileView> { viewer.open(file) as HttpFileView }.get()

        // The text is in a split pane, which adds it to the scene only when it is skinned.
        robot.interact {
            view.focus()
            container.children.setAll(view.node)
        }

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { container.scene.focusOwner === robot.area() }.get()
        }
        assertEquals(0, WaitForAsyncUtils.asyncFx<Int> { robot.area().caretPosition }.get())
    }

    @Test
    fun `shows the file highlighted, editable and savable`(robot: FxRobot) {
        val file = dir.resolve("api.http").apply { writeText("### Users\nGET https://example.com/users\n") }
        val view = open(file, robot)
        val area = robot.area()

        assertEquals("### Users\nGET https://example.com/users\n", area.text)
        assertEquals(listOf("comment"), area.getStyleOfChar(1).toList())
        assertEquals(listOf("keyword"), area.getStyleOfChar(11).toList())

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
        // Long enough that the editor would not shrink to half the view on its own.
        val view = openHosted(twoRequests() + "\n".repeat(60), robot)
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
        assertEquals(0.5, view.split.dividerPositions.single(), 0.05, "the response takes half of the view")
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

    @Test
    fun `warns when git could commit the private environment file`(robot: FxRobot) {
        val private = dir.resolve("http-client.private.env.json").apply { writeText("""{"dev": {"secret": "s"}}""") }
        val asked = mutableListOf<Path>()
        session.wouldCommit = { file ->
            asked.add(file)
            true
        }

        val view = openHosted("GET ${server.base}/echo\n", robot)

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.warning.isVisible }
        assertEquals(
            "http-client.private.env.json is not ignored by git: its secrets could be committed",
            view.warning.text,
        )
        assertEquals(private, asked.last())

        // A change of the git status is enough, e.g. after adding the file to .gitignore.
        session.wouldCommit = { false }
        robot.interact { session.gitStatusChanged() }
        assertFalse(view.warning.isVisible)
    }

    @Test
    fun `acquires and clears tokens of the chosen environment`(robot: FxRobot) {
        TokenEndpoint().use { endpoint ->
            dir.resolve("http-client.env.json").writeText(
                """
                {"dev": {"Security": {"Auth": {"api": {
                  "Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "${endpoint.url}",
                  "Client ID": "c", "Client Secret": "s", "Acquire Automatically": false
                }}}}}
                """.trimIndent(),
            )
            val view =
                openHosted("GET ${server.base}/echo\nAuthorization: Bearer {{${'$'}auth.token(\"api\")}}\n", robot)
            awaitEnvironments(view, "No environment", "dev")

            robot.interact { view.auth.show() }
            assertEquals(
                listOf("Choose an environment first", "", "Clear All Tokens"),
                view.auth.items.map { it.text.orEmpty() },
            )
            robot.interact {
                view.auth.hide()
                view.environments.value = "dev"
                view.auth.show()
            }
            val api = view.auth.items.first() as Menu
            assertEquals("api", api.text)
            robot.interact {
                view.auth.hide()
                api.items.first { it.text == "Acquire Token" }.fire()
            }
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.message.text.startsWith("Token 'api' acquired") }
            assertEquals(1, endpoint.calls)

            robot.interact { view.runButton.fire() }
            assertEquals("200 OK", awaitResult(view))
            assertTrue("authorization: Bearer t1" in view.response.body.text, view.response.body.text)

            robot.interact { api.items.first { it.text == "Clear Token" }.fire() }
            assertEquals("Token 'api' cleared", view.message.text)
            robot.interact { view.runButton.fire() }
            assertEquals("Failed", awaitResult(view))
            assertTrue("Acquire the token for 'api' first" in view.response.error.text, view.response.error.text)
        }
    }

    @Test
    fun `failed token requests are shown`(robot: FxRobot) {
        dir.resolve("http-client.env.json").writeText(
            """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Client Credentials",
              "Token URL": "${server.base}/no-token", "Client ID": "c"}}}}}""",
        )
        val view = openHosted("GET ${server.base}/echo\n", robot)
        awaitEnvironments(view, "No environment", "dev")
        robot.interact {
            view.environments.value = "dev"
            view.auth.show()
        }
        val api = view.auth.items.first() as Menu
        robot.interact {
            view.auth.hide()
            api.items.first { it.text == "Acquire Token" }.fire()
        }

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.message.text.startsWith("Token 'api' not acquired: ") }
        assertTrue("http-message-error" in view.message.styleClass)
    }

    @Test
    fun `clears the cookies of the window`(robot: FxRobot) {
        server.handle("/login") { server.respond(it, 200, "ok", "Set-Cookie" to "session=abc; Path=/") }
        val view = openHosted("GET ${server.base}/login\n\n###\nGET ${server.base}/echo\n", robot)
        robot.interact { view.runButton.fire() }
        awaitResult(view)

        robot.interact { view.run(3) }
        awaitResult(view)
        assertTrue("cookie: session=abc" in view.response.body.text, view.response.body.text)

        robot.interact { view.clearCookies.fire() }
        assertEquals("Cookies cleared", view.message.text)
        robot.interact { view.run(3) }
        awaitResult(view)
        assertFalse("cookie:" in view.response.body.text, view.response.body.text)
    }

    @Test
    fun `a narrow view keeps its toolbar controls and shows warnings in a row of their own`(robot: FxRobot) {
        dir.resolve("http-client.private.env.json").writeText("""{"dev": {}}""")
        session.wouldCommit = { true }
        val view = openHosted("GET ${server.base}/echo\n" + "\n".repeat(80), robot)
        robot.interact {
            container.setMaxSize(420.0, 400.0)
            view.clearCookies.fire()
        }
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { view.warning.isVisible }
        WaitForAsyncUtils.waitForFxEvents()

        for (control in listOf(view.runButton, view.cancelButton, view.environments, view.auth, view.clearCookies)) {
            assertEquals(control.prefWidth(-1.0), control.width, 1.0, control.styleClass.toString())
        }
        assertTrue(view.warning.isVisible)
        assertFalse(view.warning in view.toolbar.children)
        assertTrue(view.warning.height > 0)

        robot.interact { view.run(0) }
        awaitResult(view)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(0.5, view.split.dividerPositions.single(), 0.05)
        assertTrue(view.response.node.boundsInParent.height > 100, "the response gets its half of a long file's view")
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

        robot.press(robot.area(), KeyCode.DIGIT7, shortcut = true, shift = true)

        assertEquals("# GET http://x\n", robot.area().text)
        assertTrue(view.dirty.get())
    }

    @Test
    fun `follows the file when it is moved, with its environment`(robot: FxRobot) {
        val session = HttpClientSession(BackgroundTasks.forTests())
        val old = dir.resolve("api.http").apply { writeText("GET http://x\n") }
        val view = open(old, robot)
        robot.interact {
            view.host =
                object : FileViewHost {
                    override fun openFile(file: Path) = false

                    override fun openWebPage(uri: URI) {}

                    private val services = WindowServices().apply { register(HttpClientSession::class.java, session) }

                    override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
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
