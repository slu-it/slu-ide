package dev.sluide.editorarea

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.bicep.BicepFileViewer
import dev.sluide.fileviewers.env.EnvFileViewer
import dev.sluide.fileviewers.image.ImageFileViewer
import dev.sluide.fileviewers.image.SvgFileViewer
import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.markdown.MermaidFileViewer
import dev.sluide.fileviewers.notebook.NotebookFileViewer
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
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
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the editor area: other file views: tabs, fonts, host and images. Needs a display, see the `uiTest` Gradle
 * task.
 */
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

    @Test
    fun `other tabs use the editor font only if they ask for it`(robot: FxRobot) {
        fun tab(
            name: String,
            editorFont: Boolean,
        ) = object : EditorTab {
            override val title = SimpleStringProperty(name)
            override val tooltip = name
            override val content = Pane()
            override val usesEditorFont = editorFont
        }
        val diff = tab("a diff", editorFont = true)
        val terminal = tab("a terminal", editorFont = false)
        robot.interact {
            editor.open(diff)
            editor.open(terminal)
            fontSettings.apply(mapOf(FontArea.EDITOR to FontSpec("Serif", 21.0)))
        }

        assertEquals(FontSpec("Serif", 21.0).toCss(), diff.content.style)
        assertEquals("", terminal.content.style)

        robot.interact { editor.editorTabs.close(diff) }
        assertFalse(diff.content.styleProperty().isBound, "closed tabs are unbound")
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
        val field = fieldAbove(robot, listOf(ImageFileViewer()))
        robot.interact { field.requestFocus() }

        open(robot, png("image.png"))

        eventually(robot) { assertEquals(WEB_VIEW, stage.scene.focusOwner.javaClass.simpleName) }
    }

    @Test
    fun `an opened file focuses its source, or its preview if that shows alone or zooms`(robot: FxRobot) {
        val field =
            fieldAbove(
                robot,
                listOf(
                    MarkdownFileViewer(),
                    SvgFileViewer(),
                    NotebookFileViewer(),
                    MermaidFileViewer(),
                    BicepFileViewer(),
                    EnvFileViewer(),
                ),
            )
        val files =
            mapOf(
                textFile("notes.txt") to "CodeArea",
                textFile(".env", "KEY=value\n") to "CodeArea",
                textFile("README.md", "# Title\n") to "CodeArea",
                textFile("main.bicep", "param name string\n") to "CodeArea",
                textFile("flow.mmd", "flowchart LR\n  A --> B\n") to WEB_VIEW,
                textFile("icon.svg", """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""") to
                    WEB_VIEW,
                textFile("book.ipynb", """{"cells": [], "metadata": {}, "nbformat": 4, "nbformat_minor": 5}""") to
                    WEB_VIEW,
            )
        for ((file, focused) in files) {
            robot.interact { field.requestFocus() }

            open(robot, file)

            eventually(robot) {
                val owner = stage.scene.focusOwner
                assertEquals(focused, owner.javaClass.simpleName, file.name)
                assertTrue(owner.isInside(editor.selectedTab!!.content), "${file.name}: in its own tab")
            }
        }
        // Shown again, each takes the focus the same way.
        for ((file, focused) in files) {
            robot.interact { field.requestFocus() }

            robot.interact { click(tabLabel(robot, file.name)) }

            eventually(robot) {
                assertEquals(focused, stage.scene.focusOwner.javaClass.simpleName, "${file.name} again")
            }
        }
    }

    @Test
    fun `renaming an image to another type gives its tab a new view, which takes the focus if the old one had it`(
        robot: FxRobot,
    ) {
        val field = fieldAbove(robot, listOf(ImageFileViewer()))
        val png = png("image.png")
        open(robot, png)
        eventually(robot) { assertEquals(WEB_VIEW, stage.scene.focusOwner.javaClass.simpleName) }
        val tab = checkNotNull(editor.editorTabs.find(png))

        val gif = dir.resolve("image.gif")
        move(robot, png, gif)

        val replacement = checkNotNull(editor.editorTabs.find(gif))
        assertNotSame(tab, replacement)
        eventually(robot) { assertTrue(stage.scene.focusOwner.isInside(replacement.content)) }

        // Renamed in the file tree, which has the focus: it stays there.
        robot.interact { field.requestFocus() }
        val jpg = dir.resolve("image.jpg")
        move(robot, gif, jpg)
        WaitForAsyncUtils.waitForFxEvents()

        assertNotSame(replacement, editor.editorTabs.find(jpg))
        assertSame(field, focusOwner())
    }

    @Test
    fun `a Bicep file in Split focuses its diagram, as it zooms`(robot: FxRobot) {
        val field = fieldAbove(robot, listOf(BicepFileViewer()))
        val bicep = textFile("main.bicep", "param name string\n")
        open(robot, bicep)
        val split =
            robot
                .lookup(".editor-content .preview-mode")
                .queryAllAs(ToggleButton::class.java)
                .single { it.text == "Split" }
        robot.interact { split.fire() }
        robot.interact { field.requestFocus() }

        open(robot, bicep)

        eventually(robot) { assertEquals(WEB_VIEW, stage.scene.focusOwner.javaClass.simpleName) }
    }

    private fun png(name: String): Path =
        dir.resolve(name).also {
            ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), "png", it.toFile())
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

/** The class of a web view, which the editor module does not see. */
private const val WEB_VIEW = "WebView"
