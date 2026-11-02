package dev.sluide.databases.ui

import dev.sluide.databases.DatabasesPanes
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.SessionEvent
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tab's split in a scene: the middle and right panes follow the selection and the connection status, the panes'
 * nodes are created once per entry and reused, and the dividers stay put. The model's logic is in
 * [DatabasesTabModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DatabasesTabViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
    }

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    @Test
    fun `middle and right follow selection and status, reusing each entry's nodes`(robot: FxRobot) {
        val f = ModelFixture(dir)
        val project = dir.resolve("project").createDirectories()
        f.store.save(
            project,
            listOf(
                ConnectionConfig("a", "orders-dev", DatabaseKind.POSTGRESQL),
                ConnectionConfig("b", "shop", DatabaseKind.MARIADB),
            ),
        )
        val created = mutableListOf<String>()
        val panes =
            DatabasesPanes(
                { Region() },
                { entry -> Label("console ${entry.config.get().name}").also { created += it.text } },
                { entry -> Label("tree ${entry.config.get().name}").also { created += it.text } },
            )
        lateinit var view: DatabasesTabView
        robot.interact {
            f.model.load(project)
            view = DatabasesTabView(f.model, panes)
            stage.scene = Scene(view.node, 1000.0, 500.0)
            stage.show()
        }
        val (orders, shop) = f.model.connections

        fun text(node: Node?) =
            (node as? Label)?.text ?: node?.lookupAll(".label")?.joinToString(" | ") { (it as Label).text }

        // Read on the FX thread: the middle's text, the right's text.
        fun shown() = fx(robot) { text(view.middleNode) to text(view.rightNode) }

        fun nodes() = fx(robot) { view.middleNode to view.rightNode }

        fun dividers() = fx(robot) { view.node.dividerPositions.toList() to view.node.width }
        assertEquals("Add a connection with +" to "Connect to browse", shown())
        val (initial, width) = dividers()
        assertEquals(240.0, initial[0] * width, 20.0)
        assertEquals(280.0, (1 - initial[1]) * width, 20.0)

        robot.interact { f.model.selected.set(orders) }
        assertEquals("orders-dev | Not connected | " to "Connect to browse", shown())
        val connect = fx(robot) { checkNotNull(view.middleNode).lookupAll(".button").single() as Button }
        f.passwords.put(project, "a", "pw".toCharArray())
        robot.interact { connect.fire() }
        assertEquals("orders-dev | Connecting… | ", shown().first)
        robot.interact { f.runWorkers() }
        assertEquals("console orders-dev" to "tree orders-dev", shown())
        val (console, tree) = nodes()

        robot.interact { view.node.setDividerPositions(0.3, 0.6) }
        robot.interact { f.model.selected.set(shop) }
        assertEquals("shop | Not connected | " to "Connect to browse", shown())

        robot.interact { f.model.selected.set(orders) }
        assertSame(console, nodes().first)
        assertSame(tree, nodes().second)

        robot.interact { f.sessions.single().emit(SessionEvent.Closed("server closed the connection")) }
        assertEquals("orders-dev | Not connected | server closed the connection" to "Connect to browse", shown())

        robot.interact {
            f.model.connect(orders)
            f.runWorkers()
        }
        assertSame(console, nodes().first, "reused after a reconnect")
        assertSame(tree, nodes().second)
        assertEquals(listOf("console orders-dev", "tree orders-dev"), created)
        val kept = dividers().first
        assertEquals(0.3, kept[0], 0.01)
        assertEquals(0.6, kept[1], 0.01)

        robot.interact { view.dispose() }
        robot.interact { f.model.selected.set(shop) }
        assertTrue(nodes().first == null, "a disposed view no longer follows the model")
    }
}
