package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreLineEffect
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.code.Gutter
import dev.sluide.fileviewers.languages.IgnoreFileViewer
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
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
import kotlin.test.assertTrue

/** The dots beside the lines of a `.gitignore` in a real view. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitignoreFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private val effects = IgnoreRuleEffects()
    private var lookups = 0

    /** Answers of lookups wait here, to be given later, when [deferred]. */
    private var deferred = false
    private val pending = mutableListOf<(IgnoreFileEffects?) -> Unit>()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    // Lines: 0 comment, 1 build/ (active), 2 blank, 3 *.log (tracked only), 4 old (no effect).
    private val answer =
        IgnoreFileEffects(
            mapOf(
                1 to IgnoreLineEffect(1, 0, 0, listOf("build/"), emptyList()),
                3 to IgnoreLineEffect(0, 0, 2, emptyList(), listOf("a.txt", "b.txt")),
            ),
            complete = true,
        )

    private fun host(service: IgnoreRuleEffects?) =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? =
                if (type == IgnoreRuleEffects::class.java) type.cast(service) else null
        }

    private fun open(
        name: String,
        robot: FxRobot,
        service: IgnoreRuleEffects? = effects,
    ): FileView {
        service?.lookup = { _, done ->
            lookups++
            if (deferred) pending += done else done(answer)
        }
        val file = dir.resolve(name).apply { writeText("# c\nbuild/\n\n*.log\nold\n") }
        val view = WaitForAsyncUtils.asyncFx<FileView> { IgnoreFileViewer().open(file) }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host(service)
        }
        WaitForAsyncUtils.waitForFxEvents()
        return view
    }

    /** The dot nodes by the line number (1-based) of their row. */
    private fun dotNodes(robot: FxRobot): Map<String, Node> {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".${Gutter.GUTTER_STYLE}")
            .queryAllAs(HBox::class.java)
            .mapNotNull { row ->
                val dot = row.children.firstOrNull { "ignore-rule-dot" in it.styleClass } ?: return@mapNotNull null
                (row.children.first() as Label).text.trim() to dot
            }.toMap()
    }

    private fun kinds(robot: FxRobot) =
        dotNodes(robot).mapValues { (_, dot) -> dot.styleClass.single { it != "ignore-rule-dot" } }

    @Test
    fun `dots show the effect of each pattern line, none for comments and blanks`(robot: FxRobot) {
        open(".gitignore", robot)
        assertEquals(
            mapOf("2" to "ignore-rule-active", "4" to "ignore-rule-tracked", "5" to "ignore-rule-none"),
            kinds(robot),
        )
        val tooltip = dotNodes(robot).getValue("2").properties["javafx.scene.control.Tooltip"] as Tooltip
        assertEquals("Ignores 1 path: build/", tooltip.text)
    }

    @Test
    fun `without the service there are no dots`(robot: FxRobot) {
        open(".gitignore", robot, service = null)
        assertTrue(dotNodes(robot).isEmpty())
    }

    @Test
    fun `other ignore files stay plain code views`(robot: FxRobot) {
        val view = open(".dockerignore", robot)
        assertTrue(view is CodeView)
        assertTrue(dotNodes(robot).isEmpty())
    }

    @Test
    fun `an edited line loses its dot`(robot: FxRobot) {
        val view = open(".gitignore", robot) as GitignoreFileView
        robot.interact { view.source.area.insertText(view.source.area.getAbsolutePosition(1, 5), "s") }
        assertEquals(mapOf("4" to "ignore-rule-tracked", "5" to "ignore-rule-none"), kinds(robot))
    }

    @Test
    fun `saving looks the effects up again`(robot: FxRobot) {
        val view = open(".gitignore", robot) as GitignoreFileView
        val before = lookups
        robot.interact {
            view.source.area.appendText("more\n")
            view.save()
        }
        assertEquals(before + 1, lookups)
    }

    @Test
    fun `renamed away from gitignore the view keeps its text and loses its dots`(robot: FxRobot) {
        val view = open(".gitignore", robot) as GitignoreFileView
        robot.interact { view.source.area.appendText("more\n") }
        var moved = false
        robot.interact { moved = view.moved(dir.resolve("notes.txt")) }
        assertTrue(moved)
        assertTrue(dotNodes(robot).isEmpty())
        assertTrue(view.text.endsWith("more\n"))
    }

    @Test
    fun `an answer after dispose is dropped`(robot: FxRobot) {
        deferred = true
        val view = open(".gitignore", robot)
        robot.interact { view.dispose() }
        robot.interact { pending.single()(answer) }
        assertTrue(dotNodes(robot).isEmpty())
    }
}
