package dev.sluide.editor

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.ImageFileViewer
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the editor area: other file views: tabs, fonts, host and images. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaViewsUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `other tabs show their content, follow title changes and are disposed on close`(robot: FxRobot) {
        val a = textFile("a.txt")
        open(robot, a)
        var disposed = false
        val shellTitle = SimpleStringProperty("Terminal 1")
        val tab =
            object : EditorTab {
                override val title = shellTitle
                override val tooltip = "a terminal"
                override val content = Label("terminal content").apply { styleClass += "other-content" }

                override fun dispose() {
                    disposed = true
                }
            }

        robot.interact { editor.open(tab) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("a.txt", "Terminal 1"), tabTitles(robot))
        assertSame(tab, editor.selectedTab)
        assertNull(editor.selectedPath)
        assertEquals(listOf(a), editor.openPaths)
        assertTrue(robot.lookup(".editor-content .other-content").tryQuery<Node>().isPresent)

        robot.interact { shellTitle.set("vim README.md") }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("a.txt", "vim README.md"), tabTitles(robot))

        robot.interact { editor.closeAll() }
        assertTrue(disposed)
        assertEquals(0, editor.watchCount)
    }

    @Test
    fun `open text and code views use the editor font and follow its changes`(robot: FxRobot) {
        val text = textFile("notes.txt")
        val code = textFile("main.py", "print(1)")
        open(robot, text, code)
        val serif = FontSpec("Serif", 21.0, FontStyle.ITALIC)

        robot.interact { fontSettings.apply(mapOf(FontArea.EDITOR to serif)) }
        WaitForAsyncUtils.waitForFxEvents()

        val codeText =
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(javafx.scene.text.Text::class.java)
                .first { it.text.contains("print") }
        assertEquals(21.0, codeText.font.size, "code view text inherits the size")
        assertEquals("Serif", codeText.font.family)
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(text))) }
        WaitForAsyncUtils.waitForFxEvents()
        val plainText =
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(javafx.scene.text.Text::class.java)
                .first { it.text.contains("content") }
        assertEquals(21.0, plainText.font.size)
        assertTrue("Italic" in plainText.font.style || "Oblique" in plainText.font.style, plainText.font.style)

        val closed = checkNotNull(editor.editorTabs.find(text))
        robot.interact { editor.editorTabs.close(closed) }
        assertFalse(closed.content.styleProperty().isBound, "closed views are unbound")
    }

    /** Opens `.link` files with a view that remembers the host the editor area gives it. */
    private class HostRecordingViewer : FileViewer {
        val views = mutableListOf<FileView>()

        override fun canOpen(file: Path) = file.toString().endsWith(".link")

        override fun open(file: Path): FileView =
            object : FileView {
                override val node = Label(file.fileName.toString())
                override var host: FileViewHost? = null

                override fun reload() {}
            }.also { views += it }
    }

    @Test
    fun `file views can open files and web pages through their host`(robot: FxRobot) {
        val viewer = HostRecordingViewer()
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, viewer), fallback), watcher, fontSettings)
        val opened = mutableListOf<URI>()
        area.openWebPage = { opened += it }
        area.projectFolder = { dir }
        robot.interact { stage.scene = Scene(area, 800.0, 400.0) }
        val other = textFile("other.txt")

        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)
        robot.interact { assertTrue(host.openFile(other)) }
        host.openWebPage(URI("https://example.com"))

        assertEquals(other, area.selectedPath)
        assertEquals(listOf(URI("https://example.com")), opened)
        assertEquals(dir, host.projectFolder)
    }

    @Test
    fun `file views look up the services of their window`(robot: FxRobot) {
        val viewer = HostRecordingViewer()
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, viewer), fallback), watcher, fontSettings)
        val service = StringBuilder("session")
        area.windowServices = listOf(42, service)
        robot.interact { stage.scene = Scene(area, 800.0, 400.0) }

        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)

        assertSame(service, host.windowService(StringBuilder::class.java))
        assertSame(service, host.windowService(CharSequence::class.java))
        assertEquals(42, host.windowService(Number::class.java))
        assertNull(host.windowService(Thread::class.java))
    }

    @Test
    fun `opening an image focuses its web view so that the zoom keys work`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        robot.interact {
            editor = EditorArea(FileViewers(listOf(fallback, ImageFileViewer()), fallback), watcher, fontSettings)
            stage.scene = Scene(editor, 800.0, 400.0)
        }
        val png = dir.resolve("image.png")
        ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), "png", png.toFile())

        open(robot, png)
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(
            WaitForAsyncUtils
                .asyncFx<Boolean> {
                    stage.scene.focusOwner
                        ?.javaClass
                        ?.simpleName == "WebView"
                }.get(),
        )
    }

    @Test
    fun `renaming an image keeps its view and the focus where it is`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val other = TextField()
        robot.interact {
            editor = EditorArea(FileViewers(listOf(fallback, ImageFileViewer()), fallback), watcher, fontSettings)
            stage.scene = Scene(VBox(other, editor), 800.0, 400.0)
        }
        val png = dir.resolve("image.png")
        ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), "png", png.toFile())
        open(robot, png)
        val tab = checkNotNull(editor.editorTabs.find(png))
        robot.interact { other.requestFocus() }
        WaitForAsyncUtils.waitForFxEvents()
        assertSame(other, WaitForAsyncUtils.asyncFx<Any?> { stage.scene.focusOwner }.get())

        val renamed = dir.resolve("renamed.png")
        move(robot, png, renamed)
        WaitForAsyncUtils.waitForFxEvents()

        assertSame(tab, editor.editorTabs.find(renamed), "the same tab and view")
        assertSame(other, WaitForAsyncUtils.asyncFx<Any?> { stage.scene.focusOwner }.get())
        assertEquals(
            1,
            robot
                .lookup(".editor-content")
                .queryAs(Pane::class.java)
                .children.size,
        )
    }
}
