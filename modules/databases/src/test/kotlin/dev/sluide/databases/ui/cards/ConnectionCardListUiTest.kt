package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.ui.ConnectionStatus
import dev.sluide.databases.ui.ModelFixture
import dev.sluide.databases.ui.dialog.ConnectionEdit
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.MenuButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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
 * The connection cards in a scene: they follow the model, selection and keys, the buttons and the "+" menu call the
 * model, and a drag ends in a move. The card logic is in [CardLogicTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConnectionCardListUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private lateinit var f: ModelFixture
    private lateinit var root: Parent

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
            root = ConnectionCardList(f.model).node as Parent
            stage.scene = Scene(root, 260.0, 400.0)
            stage.show()
        }
    }

    private fun cards(robot: FxRobot) = fx(robot) { root.lookupAll(".db-card").map { it as ConnectionCard } }

    private fun names(robot: FxRobot) =
        fx(robot) {
            cards(robot).map {
                it.entry.config
                    .get()
                    .name
            }
        }

    private fun button(
        card: ConnectionCard,
        cssClass: String,
    ) = card.lookup(".$cssClass") as Button

    private fun click(
        card: Node,
        count: Int = 1,
    ) {
        card.fireEvent(mouse(MouseEvent.MOUSE_PRESSED, 1))
        card.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, count))
    }

    private fun mouse(
        type: EventType<MouseEvent>,
        count: Int,
    ) = MouseEvent(
        type,
        5.0,
        5.0,
        5.0,
        5.0,
        MouseButton.PRIMARY,
        count,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        true,
        false,
        true,
        null,
    )

    private fun key(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        val list = checkNotNull(root.lookup(".db-card-list"))
        Event.fireEvent(list, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    @Test
    fun `cards follow the model's add, move, rename and delete`(robot: FxRobot) {
        show(robot)
        assertEquals(listOf("alpha", "beta", "gamma"), names(robot))

        robot.interact { f.model.move(0, 2) }
        assertEquals(listOf("beta", "gamma", "alpha"), names(robot))

        f.dialogs.edit = { ConnectionEdit(it.copy(name = "renamed"), "pw".toCharArray()) }
        robot.interact { f.model.edit(f.model.connections[0]) }
        assertEquals(listOf("renamed", "gamma", "alpha"), names(robot))

        robot.interact { f.model.delete(f.model.connections[1]) }
        assertEquals(listOf("renamed", "alpha"), names(robot))
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
    fun `Enter connects and Delete asks to delete the selected card`(robot: FxRobot) {
        show(robot)
        f.dialogs.delete = false
        robot.interact { f.model.selected.set(f.model.connections[1]) }

        key(robot, KeyCode.DELETE)
        assertEquals(listOf("delete beta"), f.dialogs.asked)

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
    fun `dragging a card below another ends in a move`(robot: FxRobot) {
        show(robot)
        val (alpha, _, gamma) = cards(robot)
        val target = fx(robot) { gamma.localToScreen(gamma.width / 2, gamma.height - 2) }
        // Real mouse input: the drag follows the pointer's press, move and release events, which a call cannot stand in for.
        robot.drag(alpha).dropTo(target)
        assertEquals(listOf("beta", "gamma", "alpha"), names(robot))
    }
}
