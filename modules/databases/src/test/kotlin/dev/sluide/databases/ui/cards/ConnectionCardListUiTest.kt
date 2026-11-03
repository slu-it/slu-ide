package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.ui.ConnectionStatus
import dev.sluide.databases.ui.ModelFixture
import dev.sluide.databases.ui.dialog.ConnectionEdit
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connection cards in a scene: they follow the model (also across leaving and re-entering the scene), selection
 * and keys, the buttons and the "+" menu call the model, a card's parts follow its entry, and drags end in a move.
 * The card logic is in [CardLogicTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConnectionCardListUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private lateinit var f: ModelFixture
    private lateinit var wrapper: StackPane
    private lateinit var root: Node
    private lateinit var scene: Scene

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

    private fun show(robot: FxRobot) {
        f = ModelFixture(dir)
        val project = dir.resolve("project").createDirectories()
        f.store.save(
            project,
            listOf(
                ConnectionConfig("a", "alpha", DatabaseKind.POSTGRESQL),
                ConnectionConfig("b", "beta", DatabaseKind.MARIADB),
                ConnectionConfig("c", "gamma", DatabaseKind.POSTGRESQL),
            ),
        )
        robot.interact {
            f.model.load(project)
            root = ConnectionCardList(f.model).node
            wrapper = StackPane(root)
            scene = Scene(wrapper, 260.0, 400.0)
            stage.scene = scene
            stage.show()
        }
    }

    private fun cards(robot: FxRobot) = fx(robot) { root.lookupAll(".db-card").map { it as ConnectionCard } }

    private fun names(robot: FxRobot) =
        fx(robot) {
            root.lookupAll(".db-card").map {
                (it as ConnectionCard)
                    .entry.config
                    .get()
                    .name
            }
        }

    private fun modelNames() = f.model.connections.map { it.config.get().name }

    private fun button(
        card: ConnectionCard,
        cssClass: String,
    ) = card.lookup(".$cssClass") as Button

    private fun mouse(
        node: Node,
        type: EventType<MouseEvent>,
        sceneY: Double,
        count: Int = 1,
    ) {
        val x = node.localToScene(node.boundsInLocal).minX + 30
        Event.fireEvent(
            node,
            MouseEvent(
                type,
                x,
                sceneY,
                x,
                sceneY,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                type != MouseEvent.MOUSE_RELEASED,
                false,
                false,
                true,
                false,
                true,
                null,
            ),
        )
    }

    private fun centerY(node: Node) = node.localToScene(node.boundsInLocal).let { (it.minY + it.maxY) / 2 }

    private fun click(
        card: Node,
        count: Int = 1,
    ) {
        mouse(card, MouseEvent.MOUSE_PRESSED, centerY(card))
        mouse(card, MouseEvent.MOUSE_CLICKED, centerY(card), count)
    }

    /** Presses on [node] (a card or a button in it) and drags to [toY]; releases there if [release]. */
    private fun drag(
        robot: FxRobot,
        node: Node,
        toY: () -> Double,
        release: Boolean = true,
    ) = robot.interact {
        val y = toY()
        mouse(node, MouseEvent.MOUSE_PRESSED, centerY(node))
        mouse(node, MouseEvent.MOUSE_DRAGGED, y)
        if (release) mouse(node, MouseEvent.MOUSE_RELEASED, y)
    }

    private fun key(
        robot: FxRobot,
        code: KeyCode,
        shortcut: Boolean = false,
    ) = robot.interact {
        val list = checkNotNull(root.lookup(".db-card-list"))
        Event.fireEvent(list, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, shortcut))
    }

    private fun line() = root.lookup(".db-card-drop-line") as Region

    @Test
    fun `cards follow the model's add, move, rename and delete`(robot: FxRobot) {
        show(robot)
        assertEquals(listOf("alpha", "beta", "gamma"), names(robot))

        robot.interact { f.model.move(0, 2) }
        assertEquals(listOf("beta", "gamma", "alpha"), names(robot))

        f.dialogs.edit = { ConnectionEdit(it.copy(name = "renamed"), "pw".toCharArray(), savePassword = false) }
        robot.interact { f.model.edit(f.model.connections[0]) }
        assertEquals(listOf("renamed", "gamma", "alpha"), names(robot))

        robot.interact { f.model.delete(f.model.connections[1]) }
        assertEquals(listOf("renamed", "alpha"), names(robot))
    }

    @Test
    fun `a list taken out of its scene and put back catches up with the model`(robot: FxRobot) {
        show(robot)
        robot.interact { wrapper.children.clear() }

        f.dialogs.edit = { ConnectionEdit(it.copy(name = "delta"), "pw".toCharArray(), savePassword = false) }
        robot.interact { f.model.add(DatabaseKind.POSTGRESQL) }
        f.passwords.put(f.model.folder, "a", "pw".toCharArray())
        robot.interact { f.model.connect(f.model.connections[0]) }
        robot.interact { wrapper.children.add(root) }

        assertEquals(listOf("alpha", "beta", "gamma", "delta"), names(robot))
        assertTrue(fx(robot) { cards(robot)[0].lookup(".db-card-progress") != null }, "the status shows")
        assertTrue(fx(robot) { "selected" in cards(robot)[3].styleClass }, "the selection shows")

        robot.interact { f.model.move(3, 0) }
        assertEquals(listOf("delta", "alpha", "beta", "gamma"), names(robot), "and it follows again")
    }

    @Test
    fun `a click selects and highlights a card, Up and Down move the selection, a double click connects`(
        robot: FxRobot,
    ) {
        show(robot)
        val (alpha, beta, gamma) = cards(robot)

        robot.interact { click(beta) }
        assertSame(beta.entry, f.model.selected.get())
        assertTrue(fx(robot) { "selected" in beta.styleClass && "selected" !in alpha.styleClass })

        key(robot, KeyCode.DOWN)
        assertSame(gamma.entry, f.model.selected.get())
        key(robot, KeyCode.UP)
        key(robot, KeyCode.UP)
        assertSame(alpha.entry, f.model.selected.get())

        f.passwords.put(f.model.folder, "a", "pw".toCharArray())
        robot.interact { click(alpha, 2) }
        assertEquals(ConnectionStatus.CONNECTING, alpha.entry.status.get())
    }

    @Test
    fun `Enter connects, Delete and Shortcut+Backspace ask to delete the selected card`(robot: FxRobot) {
        show(robot)
        f.dialogs.delete = false
        robot.interact { f.model.selected.set(f.model.connections[1]) }

        key(robot, KeyCode.DELETE)
        key(robot, KeyCode.BACK_SPACE)
        assertEquals(listOf("delete beta"), f.dialogs.asked, "a plain Backspace does nothing")
        key(robot, KeyCode.BACK_SPACE, shortcut = true)
        assertEquals(listOf("delete beta", "delete beta"), f.dialogs.asked)

        f.passwords.put(f.model.folder, "b", "pw".toCharArray())
        key(robot, KeyCode.ENTER)
        assertEquals(
            ConnectionStatus.CONNECTING,
            f.model.connections[1]
                .status
                .get(),
        )
    }

    @Test
    fun `the buttons connect, disconnect and edit their card`(robot: FxRobot) {
        show(robot)
        val (alpha, beta, _) = cards(robot)
        f.passwords.put(f.model.folder, "a", "pw".toCharArray())

        assertTrue(
            fx(robot) {
                button(alpha, "db-card-disconnect").isDisable &&
                    !button(alpha, "db-card-connect").isDisable
            },
        )
        robot.interact { button(alpha, "db-card-connect").fire() }
        robot.interact { f.runWorkers() }
        assertEquals(ConnectionStatus.CONNECTED, alpha.entry.status.get())
        assertTrue(
            fx(robot) {
                button(alpha, "db-card-connect").isDisable &&
                    !button(alpha, "db-card-disconnect").isDisable
            },
        )

        robot.interact { button(alpha, "db-card-disconnect").fire() }
        assertEquals(ConnectionStatus.DISCONNECTED, alpha.entry.status.get())

        robot.interact { button(beta, "db-card-edit").fire() }
        assertEquals(listOf("edit beta new=false names=[alpha, beta, gamma]"), f.dialogs.asked)
    }

    @Test
    fun `the plus menu adds a connection of the chosen kind`(robot: FxRobot) {
        show(robot)
        val add = fx(robot) { root.lookup(".db-card-add") as MenuButton }
        robot.interact { add.items.first { it.text == "MariaDB" }.fire() }
        assertEquals(listOf("edit MariaDB new=true names=[alpha, beta, gamma]"), f.dialogs.asked)
    }

    @Test
    fun `a card's dot, spinner, error line and context menu follow its entry`(robot: FxRobot) {
        show(robot)
        val card = cards(robot)[0]
        val entry = card.entry

        fun dotNode() =
            listOf("disconnected", "connected", "pending", "error").firstNotNullOfOrNull { card.lookup(".db-dot-$it") }

        fun dotClass() = fx(robot) { dotNode()?.styleClass?.single { it.startsWith("db-dot-") } }

        fun error() = card.lookup(".db-card-error") as Label

        fun menu() = fx(robot) { card.contextMenu.items.map { it.text } }

        assertEquals("db-dot-disconnected", dotClass())
        assertFalse(fx(robot) { error().isVisible })
        assertEquals(listOf("Connect", "Edit…", "Duplicate", null, "Delete…"), menu())

        f.passwords.put(f.model.folder, "a", "pw".toCharArray())
        robot.interact { f.model.connect(entry) }
        assertTrue(fx(robot) { card.lookup(".db-card-progress") != null }, "a spinner while connecting")
        assertEquals(null, fx(robot) { dotNode() }, "and no dot")

        robot.interact { f.runWorkers() }
        assertEquals("db-dot-connected", dotClass())
        assertEquals(listOf("Disconnect", "Edit…", "Duplicate", null, "Delete…"), menu())
        robot.interact { entry.setPending(true) }
        assertEquals("db-dot-pending", dotClass())

        robot.interact { entry.ended("server closed the connection") }
        assertEquals("db-dot-error", dotClass())
        assertTrue(fx(robot) { error().isVisible && error().text == "server closed the connection" })
        assertEquals("server closed the connection", fx(robot) { error().tooltip.text })
        assertEquals(listOf("Connect", "Edit…", "Duplicate", null, "Delete…"), menu())
    }

    @Test
    fun `a saved password shows a key icon and Forget Saved Password, which forgets it`(robot: FxRobot) {
        show(robot)
        val card = cards(robot)[0]

        fun key() = fx(robot) { card.lookup(".db-card-key")?.takeIf { it.isVisible } }

        fun menu() = fx(robot) { card.contextMenu.items.map { it.text } }
        assertEquals(null, key())
        assertEquals(listOf("Connect", "Edit…", "Duplicate", null, "Delete…"), menu())

        robot.interact { f.model.setSavedPassword(card.entry, "v1:x") }
        assertTrue(key() != null, "the key icon shows")
        assertEquals(listOf("Connect", "Edit…", "Duplicate", "Forget Saved Password", null, "Delete…"), menu())

        robot.interact {
            card.contextMenu.items
                .first { it.text == "Forget Saved Password" }
                .fire()
        }
        assertEquals(
            null,
            card.entry.config
                .get()
                .savedPassword,
        )
        assertEquals(null, key())
        assertEquals(listOf("Connect", "Edit…", "Duplicate", null, "Delete…"), menu())
    }

    @Test
    fun `dragging a card below another ends in a move`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        drag(robot, alpha, { centerY(gamma) + gamma.height / 4 })
        assertEquals(listOf("beta", "gamma", "alpha"), modelNames())
    }

    @Test
    fun `dragging a card upwards moves it before the card under the pointer`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        drag(robot, gamma, { centerY(alpha) - alpha.height / 4 })
        assertEquals(listOf("gamma", "alpha", "beta"), modelNames())
    }

    @Test
    fun `a press that moves less than the threshold is no drag`(robot: FxRobot) {
        show(robot)
        val alpha = cards(robot)[0]
        robot.interact {
            mouse(alpha, MouseEvent.MOUSE_PRESSED, centerY(alpha))
            mouse(alpha, MouseEvent.MOUSE_DRAGGED, centerY(alpha) + 3)
        }
        assertFalse(fx(robot) { "dragging" in alpha.styleClass })
        robot.interact { mouse(alpha, MouseEvent.MOUSE_RELEASED, centerY(alpha) + 3) }
        assertEquals(listOf("alpha", "beta", "gamma"), modelNames())
    }

    @Test
    fun `a press on a card button never starts a drag (the button consumes it)`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        drag(robot, fx(robot) { button(alpha, "db-card-edit") }, { centerY(gamma) })
        assertFalse(fx(robot) { "dragging" in alpha.styleClass })
        assertEquals(listOf("alpha", "beta", "gamma"), modelNames())
    }

    @Test
    fun `the insertion line shows during a drag and hides over the card's own place`(robot: FxRobot) {
        show(robot)
        val (_, beta, gamma) = cards(robot)
        val home = fx(robot) { centerY(beta) }
        drag(robot, beta, { centerY(gamma) + gamma.height / 4 }, release = false)
        assertTrue(fx(robot) { line().isVisible && "dragging" in beta.styleClass })

        robot.interact { mouse(beta, MouseEvent.MOUSE_DRAGGED, home) }
        assertFalse(fx(robot) { line().isVisible }, "over its own place")
        robot.interact { mouse(beta, MouseEvent.MOUSE_RELEASED, home) }
        assertEquals(listOf("alpha", "beta", "gamma"), modelNames())
    }

    @Test
    fun `a change of the list cancels a drag in progress`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        drag(robot, alpha, { centerY(gamma) + gamma.height / 4 }, release = false)
        assertTrue(fx(robot) { "dragging" in alpha.styleClass })

        robot.interact { f.model.move(2, 1) }
        robot.interact { mouse(alpha, MouseEvent.MOUSE_RELEASED, centerY(gamma)) }

        assertEquals(listOf("alpha", "gamma", "beta"), modelNames(), "only the change moved anything")
        assertFalse(fx(robot) { "dragging" in alpha.styleClass })
        assertEquals(0.0, fx(robot) { alpha.translateY })
        assertFalse(fx(robot) { line().isVisible })
    }

    @Test
    fun `Escape cancels a drag, a release afterwards moves nothing`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        drag(robot, alpha, { centerY(gamma) + gamma.height / 4 }, release = false)
        assertTrue(fx(robot) { alpha.translateY != 0.0 })

        robot.interact {
            Event.fireEvent(scene, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
            mouse(alpha, MouseEvent.MOUSE_RELEASED, centerY(gamma))
        }
        assertEquals(listOf("alpha", "beta", "gamma"), modelNames())
        assertFalse(fx(robot) { "dragging" in alpha.styleClass })
        assertEquals(0.0, fx(robot) { alpha.translateY })
        assertFalse(fx(robot) { line().isVisible })
    }
}
