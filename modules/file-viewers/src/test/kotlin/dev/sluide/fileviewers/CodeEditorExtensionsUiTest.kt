package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.languages.PythonFileViewer
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Subscription
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CodeEditorExtensions] reaching the code views of the viewers and of [CodeEditors.open] through their host, and what
 * they see of the view (ADR 0108); when they attach is `CodeViewExtensionsTest`'s. Needs a display: shown lines and
 * gutter rows exist only in a laid-out area.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodeEditorExtensionsUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    /** A host of a window whose services hold [extensions]. */
    private fun host(extensions: CodeEditorExtensions) =
        object : FileViewHost {
            private val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    /** Extensions that record the editors they extend and the handles closed. */
    private fun recording(
        extended: MutableList<ExtensibleCodeEditor>,
        closed: MutableList<Path>,
    ) = CodeEditorExtensions().apply {
        add { editor ->
            extended += editor
            AutoCloseable { closed.add(editor.file.value) }
        }
    }

    private fun file(
        name: String,
        text: String = "x = 1\n",
    ) = dir.resolve(name).apply { writeText(text) }

    @Test
    fun `the views of viewers and CodeEditors are extended through their host, follow moves and close on dispose`(
        robot: FxRobot,
    ) {
        val extended = mutableListOf<ExtensibleCodeEditor>()
        val closed = mutableListOf<Path>()
        val extensions = recording(extended, closed)
        val view = WaitForAsyncUtils.asyncFx<FileView> { PythonFileViewer().open(file("view.py")) }.get()
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file("editor.py"), null) }.get()

        robot.interact {
            view.host = host(extensions)
            editor.host = host(extensions)
        }
        assertEquals(listOf(dir.resolve("view.py"), dir.resolve("editor.py")), extended.map { it.file.value })

        robot.interact {
            view.moved(dir.resolve("moved.py"))
            view.dispose()
            editor.dispose()
        }
        assertEquals(listOf(dir.resolve("moved.py"), dir.resolve("editor.py")), closed)
    }

    @Test
    fun `an extension sees the lines, adds a gutter column and follows the shown lines`(robot: FxRobot) {
        val extended = mutableListOf<ExtensibleCodeEditor>()
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    PythonFileViewer().open(file("main.py", (1..40).joinToString("\n") { "x = $it" })) as CodeView
                }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host(recording(extended, mutableListOf()))
        }
        val editor = extended.single()

        robot.interact {
            editor.addGutterColumn(30.0) { line -> Label("c$line").apply { styleClass += "extension-column" } }
        }

        val labels = robot.lookup(".extension-column").queryAllAs(Label::class.java).map { it.text }
        assertTrue("c0" in labels, "$labels")
        assertEquals(40, editor.lineCount)
        assertEquals("x = 2", editor.lineText(1))
        assertFailsWith<IndexOutOfBoundsException> { editor.lineText(40) }
        val shown = editor.shownLines
        assertEquals(0, shown.first)
        assertTrue(shown.last in 1 until 39, "$shown")

        var changes = 0
        val subscription = WaitForAsyncUtils.asyncFx<Subscription> { editor.onShownLinesChanged { changes++ } }.get()
        robot.interact { view.area.showParagraphAtTop(20) }
        assertTrue(changes > 0, "scrolled")
        assertEquals(20, editor.shownLines.first)
        robot.interact { subscription.unsubscribe() }
    }
}
