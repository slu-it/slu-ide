package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewers
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpFileViewer
import javafx.scene.Scene
import javafx.scene.input.KeyCharacterCombination
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `.http` files in their view. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class HttpFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val viewer = HttpFileViewer()

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
        val session = HttpClientSession(Runnable::run)
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
}
