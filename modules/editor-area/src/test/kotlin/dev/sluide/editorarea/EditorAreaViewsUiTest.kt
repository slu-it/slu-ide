package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.env.EnvFileViewer
import dev.sluide.previews.bicep.BicepFileViewer
import dev.sluide.previews.image.ImageFileViewer
import dev.sluide.previews.image.SvgFileViewer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.markdown.MermaidFileViewer
import dev.sluide.previews.notebook.NotebookFileViewer
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.layout.Pane
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
import java.util.concurrent.TimeUnit
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
        assertEquals(listOf("a.txt", "Terminal 1"), tabTitles(robot))
        assertSame(tab, editor.selectedTab)
        assertNull(editor.selectedPath)
        assertEquals(listOf(a), editor.openPaths)
        assertTrue(robot.lookup(".editor-content .other-content").tryQuery<Node>().isPresent)

        robot.interact { shellTitle.set("vim README.md") }
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

        val codeText =
            robot
                .lookup(".editor-content .code-area .text")
                .queryAllAs(javafx.scene.text.Text::class.java)
                .first { it.text.contains("print") }
        assertEquals(21.0, codeText.font.size, "code view text inherits the size")
        assertEquals("Serif", codeText.font.family)
        robot.interact { editor.editorTabs.select(checkNotNull(editor.editorTabs.find(text))) }
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
    fun `file views can open files and web pages and look up services through their host`(robot: FxRobot) {
        val viewer = HostRecordingViewer()
        val area = areaWith(robot, viewer)
        val opened = mutableListOf<URI>()
        area.openWebPage = { opened += it }
        area.projectFolder = { dir }
        val service = StringBuilder("session")
        area.windowServices.register(StringBuilder::class.java, service)
        val other = textFile("other.txt")

        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)
        robot.interact { assertTrue(host.openFile(other)) }
        host.openWebPage(URI("https://example.com"))

        assertEquals(other, area.selectedPath)
        assertEquals(listOf(URI("https://example.com")), opened)
        assertEquals(dir, host.projectFolder)
        assertSame(service, host.windowService(StringBuilder::class.java))
        assertNull(host.windowService(Thread::class.java))
    }

    @Test
    fun `a file opened at a line through the host has the caret there, a preview shown alone shows its source too`(
        robot: FxRobot,
    ) {
        val viewer = HostRecordingViewer()
        val area = areaWith(robot, viewer, MarkdownFileViewer())
        val text = textFile("lines.txt", (1..100).joinToString("\n") { "line $it" })
        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)

        robot.interact { assertTrue(host.openFile(text, 79)) }

        val code = robot.lookup(".editor-content .code-area").queryAs(CodeArea::class.java)
        assertEquals(text, area.selectedPath)
        assertEquals(79 to 0, code.currentParagraph to code.caretColumn)
        assertTrue(code.isFocused)
        assertTrue(79 in code.firstVisibleParToAllParIndex()..code.lastVisibleParToAllParIndex(), "scrolled to it")
        robot.interact { assertTrue(host.openFile(text, 500)) }
        assertEquals(99, code.currentParagraph, "beyond the end: the last line")

        val markdown = textFile("notes.md", (1..200).joinToString("\n\n") { "Paragraph $it" })
        robot.interact { host.openFile(markdown) }
        val tab = checkNotNull(area.selectedTab).content

        fun modeButton(label: String) =
            robot
                .from(tab)
                .lookup(".toggle-button")
                .queryAllAs(ToggleButton::class.java)
                .single { it.text == label }
        robot.interact { modeButton("Preview").fire() }
        robot.interact { area.open(text) }

        robot.interact { assertTrue(host.openFile(markdown, 300)) }

        assertTrue(modeButton("Split").isSelected, "the source shows beside the preview")
        val source = robot.from(tab).lookup(".code-area").queryAs(CodeArea::class.java)
        assertEquals(300, source.currentParagraph)
        eventually(robot) {
            assertTrue(300 in source.firstVisibleParToAllParIndex()..source.lastVisibleParToAllParIndex(), "shown")
        }
        assertTrue(source.isFocused, "the source, not the preview: ${stage.scene.focusOwner}")
        robot.interact { assertFalse(host.openFile(dir.resolve("missing.txt"), 3), "a file that cannot be opened") }
    }

    @Test
    fun `a zooming preview opened at a line focuses its source, not its diagram`(robot: FxRobot) {
        val viewer = HostRecordingViewer()
        val area = areaWith(robot, viewer, MermaidFileViewer())
        robot.interact { area.open(textFile("readme.link")) }
        val host = assertNotNull(viewer.views.single().host)
        val chart = textFile("flow.mmd", "flowchart TB\n" + (1..200).joinToString("\n") { "  n$it --> n${it + 1}" })

        // Opened anew, its view asks to focus the diagram once its split pane is in the scene.
        robot.interact { assertTrue(host.openFile(chart, 150)) }

        val source =
            robot
                .from(
                    checkNotNull(area.selectedTab).content,
                ).lookup(".code-area")
                .queryAs(CodeArea::class.java)
        eventually(robot) {
            assertEquals(150, source.currentParagraph)
            assertTrue(150 in source.firstVisibleParToAllParIndex()..source.lastVisibleParToAllParIndex(), "shown")
        }
        // Whatever the view asked for before runs by now.
        WaitForAsyncUtils.sleep(300, TimeUnit.MILLISECONDS)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(source.isFocused, "the source, not the diagram: ${stage.scene.focusOwner}")
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
        robot.interact { field.requestFocus() }
        open(robot, png)
        eventually(robot) { assertEquals(WEB_VIEW, stage.scene.focusOwner.javaClass.simpleName) }
        val tab = checkNotNull(editor.editorTabs.find(png))

        // Renamed within its type while the focus is elsewhere: the same tab and view, the focus stays.
        robot.interact { field.requestFocus() }
        val renamed = dir.resolve("renamed.png")
        move(robot, png, renamed)
        WaitForAsyncUtils.waitForFxEvents()

        assertSame(tab, editor.editorTabs.find(renamed), "the same tab and view")
        assertSame(field, focusOwner())
        assertEquals(
            1,
            robot
                .lookup(".editor-content")
                .queryAs(Pane::class.java)
                .children.size,
        )

        robot.interact { editor.focusSelected() }
        eventually(robot) { assertEquals(WEB_VIEW, stage.scene.focusOwner.javaClass.simpleName) }
        val gif = dir.resolve("image.gif")
        move(robot, renamed, gif)

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
}

/** The class of a web view, which the editor module does not see. */
private const val WEB_VIEW = "WebView"
