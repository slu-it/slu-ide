package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.languages.PythonFileViewer
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualFlow
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A restyle that replaces every shown paragraph (an overlay over the viewport) keeps the shown lines where they are:
 * with a follow-caret request in its pulse (before or after it) or without one, with a partly scrolled top line, and
 * with soft wrap in the middle of a paragraph taller than the view. RichTextFX drops the cells of restyled paragraphs;
 * Flowless then shows the caret paragraph at the top for a follow request, and its scroll pane scrolls to the top
 * without one. UI test because the cells exist only in a scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class RestyleKeepsViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 800.0)
        stage.show()
    }

    /** A code view of [text] in the scene, with an editor of its extension. */
    private fun open(
        robot: FxRobot,
        text: String,
    ): Pair<CodeView, ExtensibleCodeEditor> {
        val file = dir.resolve("main.py").apply { writeText(text) }
        val view =
            WaitForAsyncUtils
                .asyncFx<CodeView> {
                    CodeView(file, PythonFileViewer().highlighter, highlightPause = Duration.ofDays(1)) {}
                }.get()
        val editors = mutableListOf<ExtensibleCodeEditor>()
        val extensions =
            CodeEditorExtensions().apply {
                add { editor ->
                    editors += editor
                    null
                }
            }
        val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }
        robot.interact {
            container.children.setAll(view.node)
            view.host =
                object : FileViewHost {
                    override fun openFile(file: Path) = false

                    override fun openWebPage(uri: URI) {}

                    override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
                }
        }
        return view to editors.single()
    }

    private fun flow(area: CodeArea) = area.childrenUnmodifiable.filterIsInstance<VirtualFlow<*, *>>().single()

    /** The first shown paragraph and the offset of its top, as Flowless keeps them. FX thread. */
    private fun top(area: CodeArea): Pair<Int, Double> {
        val first = area.firstVisibleParToAllParIndex()
        val node = flow(area).getCellIfVisible(first).get().node
        return first to node.layoutY + node.layoutBounds.minY
    }

    /** Overlays the first character of the lines [lines] and runs [during] in the same FX step. */
    private fun restyle(
        robot: FxRobot,
        view: CodeView,
        editor: ExtensibleCodeEditor,
        lines: List<Int>,
        during: () -> Unit = {},
        before: Boolean = false,
    ) {
        robot.interact {
            val ranges =
                lines.map {
                    StyledRange(
                        view.area.getAbsolutePosition(it, 0),
                        view.area.getAbsolutePosition(it, 1),
                        "layer-probe",
                    )
                }
            if (before) during()
            editor.overlay("probe", ranges)
            if (!before) during()
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["follow request first", "restyle first", "no follow request"])
    fun `an overlay over the viewport keeps the first shown line, with a follow request in its pulse too`(
        order: String,
        robot: FxRobot,
    ) {
        val (view, editor) = open(robot, (0 until 200).joinToString("\n") { "x$it = $it" })
        val area = view.area
        robot.interact {
            area.showParagraphAtTop(25)
            area.moveTo(50, 0)
            container.layout()
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(25, WaitForAsyncUtils.asyncFx<Int> { area.firstVisibleParToAllParIndex() }.get(), "set up")
        assertTrue(WaitForAsyncUtils.asyncFx<Int> { area.lastVisibleParToAllParIndex() }.get() > 52, "caret line shown")

        val follow = order != "no follow request"
        restyle(
            robot,
            view,
            editor,
            lines = listOf(1, 150),
            during = { if (follow) area.requestFollowCaret() },
            before = order == "follow request first",
        )

        assertEquals(25, WaitForAsyncUtils.asyncFx<Int> { area.firstVisibleParToAllParIndex() }.get())
    }

    @Test
    fun `a partly scrolled top line stays where it is`(robot: FxRobot) {
        val (view, editor) = open(robot, (0 until 200).joinToString("\n") { "x$it = $it" })
        val area = view.area
        robot.interact {
            area.showParagraphAtTop(25)
            area.moveTo(50, 0)
            container.layout()
            area.scrollYBy(8.0)
            container.layout()
        }
        val before = WaitForAsyncUtils.asyncFx<Pair<Int, Double>> { top(area) }.get()
        assertEquals(25, before.first, "set up")
        assertTrue(before.second < -1.0, "the top line is cropped: ${before.second}")

        restyle(robot, view, editor, listOf(1, 150), { area.requestFollowCaret() })

        assertEquals(before, WaitForAsyncUtils.asyncFx<Pair<Int, Double>> { top(area) }.get())
    }

    @Test
    fun `with soft wrap the caret row of a paragraph taller than the view stays where it is`(robot: FxRobot) {
        val words = (0 until 3000).joinToString(" ") { "word$it" }
        val (view, editor) = open(robot, "$words\nsecond\nthird")
        val area = view.area
        robot.interact {
            area.wrapTextProperty().unbind()
            area.wrapTextProperty().set(true)
            container.layout()
            area.moveTo(0, words.length / 2)
            area.requestFollowCaret()
            container.layout()
            area.scrollYBy(150.0)
            container.layout()
        }
        WaitForAsyncUtils.waitForFxEvents()
        val before = WaitForAsyncUtils.asyncFx<Pair<Int, Double>> { top(area) }.get()
        val caret = WaitForAsyncUtils.asyncFx<Double> { area.caretBounds.get().minY }.get()
        assertTrue(before.second < -100.0, "scrolled into the paragraph: ${before.second}")

        restyle(robot, view, editor, listOf(0, 2), { area.requestFollowCaret() })

        assertEquals(before, WaitForAsyncUtils.asyncFx<Pair<Int, Double>> { top(area) }.get())
        assertEquals(caret, WaitForAsyncUtils.asyncFx<Double> { area.caretBounds.get().minY }.get(), 0.5)
    }
}
