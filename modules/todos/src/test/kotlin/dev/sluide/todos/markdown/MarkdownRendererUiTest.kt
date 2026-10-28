package dev.sluide.todos.markdown

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private typealias Check = (List<Node>) -> Boolean

/** UI tests of the native Markdown renderer. Needs a scene for controls and layout, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MarkdownRendererUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var holder: StackPane

    @Start
    fun start(stage: Stage) {
        holder = StackPane()
        stage.scene = Scene(holder, 400.0, 300.0)
        stage.show()
    }

    private fun descendants(node: Node): List<Node> =
        listOf(node) + ((node as? Parent)?.childrenUnmodifiable.orEmpty().flatMap(::descendants))

    private fun show(
        robot: FxRobot,
        source: String,
        context: MarkdownContext = MarkdownContext(dir, {}, {}, {}),
    ): Region {
        var rendered: Region? = null
        robot.interact {
            rendered = MarkdownRenderer(context).render(source)
            holder.children.setAll(rendered)
        }
        return requireNotNull(rendered)
    }

    private fun Node.has(vararg classes: String) = styleClass.containsAll(classes.toList())

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    fun `snippets render to the matching nodes and style classes`(
        source: String,
        check: Check,
        robot: FxRobot,
    ) {
        val nodes = descendants(show(robot, source))
        assertTrue(check(nodes), "$source rendered as ${nodes.map { it.javaClass.simpleName + it.styleClass }}")
    }

    @Test
    fun `the root has the style class and the stylesheet`(robot: FxRobot) {
        val root = show(robot, "text")
        assertTrue(root.has("todo-markdown"))
        assertTrue(root.stylesheets.any { it.endsWith("/dev/sluide/todos/todos.css") })
    }

    @Test
    fun `a task checkbox reports the toggled source and later toggles build on it`(robot: FxRobot) {
        var changed: String? = null
        val root = show(robot, "- [ ] one\n- [x] two", MarkdownContext(dir, {}, {}) { changed = it })
        val boxes = descendants(root).filterIsInstance<CheckBox>()
        assertEquals(listOf(false, true), boxes.map { it.isSelected })
        robot.interact { boxes[1].fire() }
        assertEquals("- [ ] one\n- [ ] two", changed)
        robot.interact { boxes[0].fire() }
        assertEquals("- [x] one\n- [ ] two", changed)
    }

    @ParameterizedTest(name = "{0} -> box {1}")
    @MethodSource("taskClicks")
    fun `the clicked checkbox toggles its own marker`(
        source: String,
        index: Int,
        expected: String?,
        robot: FxRobot,
    ) {
        var changed: String? = null
        val root = show(robot, source, MarkdownContext(dir, {}, {}) { changed = it })
        val boxes = descendants(root).filterIsInstance<CheckBox>()
        if (expected == null) assertTrue(boxes[index].isDisable, "a box without a marker in the source is disabled")
        robot.interact { boxes[index].fire() }
        assertEquals(expected, changed)
    }

    @Test
    fun `task checkboxes are disabled without a source callback`(robot: FxRobot) {
        val root = show(robot, "- [ ] one", MarkdownContext(dir, {}, {}, null))
        assertTrue(descendants(root).filterIsInstance<CheckBox>().single().isDisable)
    }

    private fun click(
        robot: FxRobot,
        node: Node,
    ) = robot.interact {
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    @Test
    fun `a link is wrapping styled text that opens a web page or a project file on a click`(robot: FxRobot) {
        val pages = mutableListOf<URI>()
        val files = mutableListOf<Path>()
        val root =
            show(
                robot,
                "[web](https://example.com/x) [file](notes/a.md) [bad](../out.md) [**bold** x](https://e.com)",
                MarkdownContext(dir, pages::add, files::add, null),
            )
        val links = descendants(root).filterIsInstance<Text>().filter { it.has("link") }
        assertEquals(listOf("web", "file", "bad", "bold", " x"), links.map { it.text })
        assertTrue(links[3].has("strong", "link", "text"))
        links.forEach { click(robot, it) }
        assertEquals(listOf(URI("https://example.com/x"), URI("https://e.com"), URI("https://e.com")), pages)
        assertEquals(listOf(dir.resolve("notes/a.md")), files)
    }

    private fun png(
        width: Int,
        height: Int,
    ) = ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
        .toByteArray()

    /** Waits until [root] has [count] image views; the scene graph is read on the FX thread. */
    private fun waitForImages(
        robot: FxRobot,
        root: Region,
        count: Int = 1,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var found = 0
        while (found < count && System.nanoTime() < deadline) {
            robot.interact { found = descendants(root).count { it is ImageView } }
            if (found < count) Thread.sleep(20) // polling for the background image load, bounded by the deadline
        }
        assertEquals(count, found, "image views")
    }

    @Test
    fun `a local image under the project is shown`(robot: FxRobot) {
        Files.write(dir.resolve("p.png"), png(1, 1))
        val root = show(robot, "![alt text](p.png)")
        waitForImages(robot, root)
        val view = descendants(root).filterIsInstance<ImageView>().single()
        assertTrue(view.isPreserveRatio)
        assertTrue(descendants(root).none { it is Text && it.has("image-alt") })
    }

    @Test
    fun `an image is no wider than the text flow it is in`(robot: FxRobot) {
        Files.write(dir.resolve("wide.png"), png(600, 10))
        val root = show(robot, "- > ![wide](wide.png)")
        waitForImages(robot, root)
        robot.interact { root.layout() }
        val view = descendants(root).filterIsInstance<ImageView>().single()
        val flow = generateSequence(view.parent) { it.parent }.filterIsInstance<TextFlow>().first()
        assertTrue(flow.width < root.width - 10, "flow ${flow.width} in root ${root.width}")
        assertTrue(view.fitWidth <= flow.width + 0.5, "image ${view.fitWidth} in flow ${flow.width}")
    }

    @Test
    fun `an image that is missing or no image keeps its alt text`(robot: FxRobot) {
        Files.write(dir.resolve("p.png"), png(1, 1))
        Files.writeString(dir.resolve("bad.png"), "not an image")
        val root = show(robot, "![missing](nope.png) ![broken](bad.png) ![fine](p.png)")
        waitForImages(robot, root)
        // A failed load has no signal of its own: the loads run side by side, so a short wait after the good one.
        Thread.sleep(200)
        robot.interact {
            val alts = descendants(root).filter { it is Text && it.has("image-alt") }.map { (it as Text).text }
            assertEquals(listOf("missing", "broken"), alts)
            assertEquals(1, descendants(root).count { it is ImageView })
        }
    }

    @Test
    fun `text follows the theme and headings are bold and larger`(robot: FxRobot) {
        val root = show(robot, "# Title\n\nbody")
        robot.interact {
            holder.style = "-fx-base: #3c3f41;"
            holder.applyCss()
            holder.layout()
        }
        val texts = descendants(root).filterIsInstance<Text>()
        val title = texts.first { it.text == "Title" }
        val body = texts.first { it.text == "body" }
        assertTrue(title.font.style.contains("Bold"), "font ${title.font}")
        assertTrue(title.font.size > body.font.size)
        val fill = body.fill as Color
        assertTrue(fill != Color.BLACK && fill.brightness > 0.5, "fill $fill")
    }

    companion object {
        private fun text(
            nodes: List<Node>,
            vararg classes: String,
        ) = nodes.filterIsInstance<Text>().filter { it.styleClass.containsAll(classes.toList()) }

        @JvmStatic
        fun taskClicks(): List<Arguments> =
            listOf(
                Arguments.of("- [ ] a\n- \\[ ] b\n- [ ] c", 2, "- [ ] a\n- \\[ ] b\n- [x] c"),
                Arguments.of("- [ ] a\n- \\[ ] b\n- [ ] c", 1, null),
                Arguments.of("- [ ] a\n  - [ ] b\n- [ ] c", 1, "- [ ] a\n  - [x] b\n- [ ] c"),
                Arguments.of("- [ ] a\n  - [ ] b\n- [ ] c", 2, "- [ ] a\n  - [ ] b\n- [x] c"),
                Arguments.of("> - [ ] a\n> - [x] b", 1, "> - [ ] a\n> - [ ] b"),
                Arguments.of("1. [ ] a\n2. [ ] b", 1, "1. [ ] a\n2. [x] b"),
                Arguments.of("text with [ ] here\n\n- [X] a", 0, "text with [ ] here\n\n- [ ] a"),
            )

        @JvmStatic
        fun snippets(): List<Arguments> =
            listOf(
                "# Title" to
                    { n: List<Node> -> n.any { it is TextFlow && it.styleClass.containsAll(listOf("heading", "h1")) } },
                "###### Small" to { n: List<Node> -> n.any { it is TextFlow && it.styleClass.contains("h6") } },
                "*a **b***" to
                    { n: List<Node> ->
                        text(n, "em", "strong").map { it.text } == listOf("b") && text(n, "em").size == 2
                    },
                "~~gone~~" to { n: List<Node> -> text(n, "strike").map { it.text } == listOf("gone") },
                "use `x` here" to { n: List<Node> -> text(n, "code").map { it.text } == listOf("x") },
                "```\nfoo\nbar\n```" to { n: List<Node> ->
                    n.any { it is TextFlow && it.styleClass.contains("code-block") } &&
                        text(n, "code").single().text == "foo\nbar"
                },
                "- a\n  - b\n- c" to { n: List<Node> ->
                    n.filterIsInstance<Label>().count { it.styleClass.contains("bullet") } == 3 &&
                        n.count { it.styleClass.contains("list") } == 2
                },
                "3. a\n4. b" to { n: List<Node> -> n.filterIsInstance<Label>().map { it.text } == listOf("3.", "4.") },
                "> quoted" to { n: List<Node> -> n.any { it.styleClass.contains("quote") } },
                "a\n\n---\n\nb" to { n: List<Node> -> n.any { it is Separator } },
                "<b>x</b>" to { n: List<Node> -> text(n).map { it.text }.containsAll(listOf("<b>", "</b>")) },
                "<div>\nx\n</div>" to { n: List<Node> -> text(n).any { it.text == "<div>\nx\n</div>" } },
                "[ref]: https://e.com\n\ntext" to { n: List<Node> -> n.filterIsInstance<TextFlow>().size == 1 },
                "5) a\n6) b" to { n: List<Node> -> n.filterIsInstance<Label>().map { it.text } == listOf("5)", "6)") },
                "a <i>x</i> b" to { n: List<Node> -> text(n).any { it.text == "<i>" } },
            ).map { (source, check) -> Arguments.of(source, check) }
    }
}
