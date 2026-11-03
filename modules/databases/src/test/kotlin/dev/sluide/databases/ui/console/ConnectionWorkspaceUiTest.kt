package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.ModelFixture
import dev.sluide.databases.ui.result.ExecutingSession
import dev.sluide.databases.ui.result.FakeCursor
import dev.sluide.databases.ui.result.OutputLine
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The consoles of a connection in a scene: tabs, keys, the chooser, the transaction controls and what the tree asks of
 * the workspace. The run sequence is in [ConsoleRunnerTest]; this is the wiring that needs controls. Needs a display,
 * see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConnectionWorkspaceUiTest {
    @TempDir
    lateinit var dir: Path

    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "key").apply { connected(fake) }
    private lateinit var f: ModelFixture
    private lateinit var workspace: ConnectionWorkspace
    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private var discard = true
    private var asked = 0
    private var stop = true
    private val stopAsked = mutableListOf<String>()
    private val pending = mutableSetOf<Int>()

    @Start
    fun start(stage: Stage) {
        f = ModelFixture(dir)
        workspace =
            ConnectionWorkspace(
                entry,
                f.model,
                QueryHistory(),
                { 2 },
                { 100 },
                SimpleStringProperty(""),
                { stage },
                { it.run() },
                {
                    asked++
                    discard
                },
                { area -> workspace.consoles.indexOfFirst { it.results === area } in pending },
                {
                    stopAsked += it
                    stop
                },
            )
        stage.scene = Scene(workspace.node as Parent, 900.0, 600.0)
        stage.show()
    }

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    private fun key(
        target: javafx.scene.Node,
        code: KeyCode,
        shortcut: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) = Event.fireEvent(
        target,
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, alt, shortcut && mac),
    )

    private fun console(index: Int = 0) = workspace.consoles[index]

    private fun bottom() = (workspace.node.items[1] as StackPane).children.single()

    @Test
    fun `Shortcut+Enter in the editor runs through the session`(robot: FxRobot) {
        robot.interact {
            console().pane.editor.replaceText("select 1")
            key(console().pane.editor.node, KeyCode.ENTER, shortcut = true)
        }
        assertEquals(listOf("select 1" to 2), fake.executed)
    }

    @Test
    fun `the key hook sees the keys first and can consume them`(robot: FxRobot) {
        robot.interact {
            console().pane.editor.replaceText("select 1")
            console().pane.keyHook = { it.code == KeyCode.ENTER }
            key(console().pane.editor.node, KeyCode.ENTER, shortcut = true)
        }
        assertTrue(fake.executed.isEmpty())
    }

    @Test
    fun `the chooser opens for two statements and Enter runs the one at the caret`(robot: FxRobot) {
        robot.interact {
            console().pane.editor.replaceText("select 1;\nselect 2")
            console().pane.editor.selectRange(18, 18)
            key(console().pane.editor.node, KeyCode.ENTER, shortcut = true)
        }
        val chooser = assertNotNull(console().pane.chooser)
        assertTrue(fx(robot) { chooser.popup.isShowing })
        robot.interact { key(chooser.list, KeyCode.ENTER) }
        assertEquals(listOf("select 2"), fake.executed.map { it.first })
        assertFalse(fx(robot) { chooser.popup.isShowing })
    }

    @Test
    fun `Run and Cancel follow the running flag and call the session`(robot: FxRobot) {
        robot.interact {
            assertTrue(
                console()
                    .pane.bar.cancel.isDisable,
            )
            entry.setRunning(true)
        }
        assertTrue(
            fx(robot) {
                console()
                    .pane.bar.run.isDisable
            },
        )
        robot.interact {
            console()
                .pane.bar.cancel
                .fire()
        }
        assertEquals(listOf("cancel"), fake.calls)
        robot.interact { entry.setRunning(false) }
    }

    @Test
    fun `consoles are added with the New Console button and the last one cannot be closed`(robot: FxRobot) {
        assertEquals(listOf("Console"), fx(robot) { workspace.consoles.map { it.tab.text } })
        assertFalse(fx(robot) { console().tab.isClosable })
        robot.interact { workspace.addButton.fire() }
        assertEquals(listOf("Console", "Console 2"), fx(robot) { workspace.consoles.map { it.tab.text } })
        assertSame(console(1).tab, fx(robot) { workspace.tabs.selectionModel.selectedItem })
        assertTrue(fx(robot) { workspace.consoles.all { it.tab.isClosable } })
        robot.interact { requestClose(console(1)) }
        assertEquals(1, fx(robot) { workspace.consoles.size })
        assertFalse(fx(robot) { console().tab.isClosable })
    }

    @Test
    fun `the bottom follows the selected console`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        assertSame(console(1).results.node, fx(robot) { bottom() })
        robot.interact { workspace.tabs.selectionModel.select(console(0).tab) }
        assertSame(console(0).results.node, fx(robot) { bottom() })
    }

    @Test
    fun `closing a console with pending edits asks, and Cancel keeps it`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        pending += 1
        discard = false
        robot.interact { requestClose(console(1)) }
        assertEquals(1, asked)
        assertTrue(fx(robot) { console(1).tab.tabPane != null })
        discard = true
        robot.interact { requestClose(console(1)) }
        assertEquals(2, asked)
        assertTrue(fx(robot) { workspace.consoles.size == 1 })
    }

    private fun requestClose(console: Console) {
        val event = Event(console.tab, console.tab, javafx.scene.control.Tab.TAB_CLOSE_REQUEST_EVENT)
        Event.fireEvent(console.tab, event)
        if (!event.isConsumed) {
            workspace.tabs.tabs.remove(console.tab)
            Event.fireEvent(console.tab, Event(javafx.scene.control.Tab.CLOSED_EVENT))
        }
    }

    @Test
    fun `the transaction toggle switches the mode`(robot: FxRobot) {
        robot.interact {
            console()
                .pane.bar.manual.isSelected = true
        }
        assertEquals(listOf("mode MANUAL"), fake.calls)
        assertEquals(CommitMode.MANUAL, fx(robot) { entry.commitMode.get() })
        assertTrue(
            fx(robot) {
                console()
                    .pane.bar.manual.isSelected
            },
        )
    }

    @Test
    fun `the toggle snaps back when the question is cancelled`(robot: FxRobot) {
        fake.commitMode = CommitMode.MANUAL
        fake.pendingTransaction = true
        robot.interact {
            entry.setCommitMode(CommitMode.MANUAL)
            entry.setPending(true)
        }
        f.dialogs.transactionEnd = null
        robot.interact {
            console()
                .pane.bar.auto.isSelected = true
        }
        assertTrue(
            fx(robot) {
                console()
                    .pane.bar.manual.isSelected
            },
        )
        assertFalse(fake.calls.contains("mode AUTO"))
    }

    @Test
    fun `Commit and Roll Back are enabled by the pending transaction and call the model`(robot: FxRobot) {
        assertTrue(
            fx(robot) {
                console()
                    .pane.bar.commit.isDisable &&
                    console()
                        .pane.bar.rollback.isDisable
            },
        )
        fake.pendingTransaction = true
        robot.interact { entry.setPending(true) }
        assertFalse(
            fx(robot) {
                console()
                    .pane.bar.commit.isDisable ||
                    console()
                        .pane.bar.rollback.isDisable
            },
        )
        robot.interact {
            console()
                .pane.bar.commit
                .fire()
        }
        assertEquals(listOf("commit"), fake.calls)
    }

    @Test
    fun `workspaceActions openData reaches the selected console's results`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        robot.interact {
            entry.workspaceActions!!.openData(TableRef(DatabaseKind.POSTGRESQL, "public", "users"))
        }
        assertTrue(
            fake.executed
                .last()
                .first
                .contains("\"users\""),
        )
        assertEquals(2, fx(robot) { (console(1).results.node as TabPane).tabs.size })
        assertEquals(1, fx(robot) { (console(0).results.node as TabPane).tabs.size })
    }

    @Test
    fun `insertIntoConsole inserts on a new line when the caret line has text`(robot: FxRobot) {
        robot.interact {
            console().pane.editor.replaceText("select 1")
            entry.workspaceActions!!.insertIntoConsole("\"users\"")
        }
        assertEquals("select 1\n\"users\"", fx(robot) { console().pane.editor.text })
        robot.interact {
            console().pane.editor.replaceText("")
            entry.workspaceActions!!.insertIntoConsole("x")
        }
        assertEquals("x", fx(robot) { console().pane.editor.text })
    }

    @Suppress("UNCHECKED_CAST")
    private fun output(console: Console) =
        ((console.results.node as TabPane).tabs[0].content as ListView<OutputLine>).items.map {
            it.text.substringAfter(" ").trim() to
                it.error
        }

    private val columns = listOf(ColumnInfo("id", "id", "int4", 4, null, null, false, false))

    private fun showRows(console: Console): FakeCursor {
        val cursor = FakeCursor(columns, listOf(listOf<Any?>(1)))
        console.results.show(StatementOutcome.Rows("select 1", 1, columns, cursor, cursor.firstPage()))
        return cursor
    }

    @Test
    fun `Commit logs Committed and Roll Back logs Rolled back`(robot: FxRobot) {
        fake.pendingTransaction = true
        robot.interact {
            entry.setPending(true)
            console()
                .pane.bar.commit
                .fire()
            console()
                .pane.bar.rollback
                .fire()
        }
        assertEquals(listOf("Committed" to false, "Rolled back" to false), fx(robot) { output(console()) })
    }

    @Test
    fun `a failed Commit logs the error in red and no Committed`(robot: FxRobot) {
        fake.pendingTransaction = true
        fake.ends = java.sql.SQLException("could not serialize")
        robot.interact {
            entry.setPending(true)
            console()
                .pane.bar.commit
                .fire()
        }
        assertEquals(listOf("could not serialize" to true), fx(robot) { output(console()) })
    }

    @Test
    fun `a disconnect closes the cursors of every console, the tabs stay`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        val cursors = fx(robot) { workspace.consoles.map { showRows(it) } }
        robot.interact { entry.ended(null) }
        assertTrue(cursors.all { it.closed })
        assertEquals(2, fx(robot) { workspace.consoles.size })
    }

    @Test
    fun `closing a console closes its results`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        val cursor = fx(robot) { showRows(console(1)) }
        robot.interact { requestClose(console(1)) }
        assertTrue(cursor.closed)
    }

    @Test
    fun `closing a console that runs asks, Cancel keeps it and the sequence`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        fake.defer = true
        robot.interact {
            console(1).pane.editor.replaceText("select 1")
            console(1).pane.run()
        }
        stop = false
        robot.interact { requestClose(console(1)) }
        assertEquals(listOf("Console 2"), stopAsked)
        assertEquals(2, fx(robot) { workspace.consoles.size })
        stop = true
        robot.interact { requestClose(console(1)) }
        assertEquals(1, fx(robot) { workspace.consoles.size })
        assertEquals(listOf("cancel"), fake.calls)
        assertEquals(listOf("Console 2", "Console 2"), stopAsked)
    }

    @Test
    fun `another console cannot run while one does`(robot: FxRobot) {
        robot.interact { workspace.addButton.fire() }
        fake.defer = true
        robot.interact {
            console(0).pane.editor.replaceText("select 1")
            console(1).pane.editor.replaceText("select 2")
            console(0).pane.run()
            console(1).pane.run()
        }
        assertEquals(listOf("select 1"), fake.executed.map { it.first })
        assertTrue(
            fx(robot) {
                console(1)
                    .pane.bar.run.isDisable
            },
        )
    }

    @Test
    fun `Shortcut+F2 cancels`(robot: FxRobot) {
        robot.interact { key(console().pane.editor.node, KeyCode.F2, shortcut = true) }
        assertEquals(listOf("cancel"), fake.calls)
    }

    @Test
    fun `Shortcut+Alt+Shift+Enter commits only with a pending transaction`(robot: FxRobot) {
        robot.interact { key(console().pane.editor.node, KeyCode.ENTER, shortcut = true, alt = true, shift = true) }
        assertTrue(fake.calls.isEmpty())
        fake.pendingTransaction = true
        robot.interact {
            entry.setPending(true)
            key(console().pane.editor.node, KeyCode.ENTER, shortcut = true, alt = true, shift = true)
        }
        assertEquals(listOf("commit"), fake.calls)
        assertTrue(fake.executed.isEmpty(), "not taken for Run")
    }

    @Test
    fun `Shortcut+Alt+E shows the history`(robot: FxRobot) {
        var shown = 0
        robot.interact {
            console().pane.showHistory = { shown++ }
            key(console().pane.editor.node, KeyCode.E, shortcut = true, alt = true)
        }
        assertEquals(1, shown)
    }

    @Test
    fun `keys outside the editor, in the toolbar, do not run`(robot: FxRobot) {
        robot.interact {
            console().pane.editor.replaceText("select 1")
            key(console().pane.bar.node, KeyCode.ENTER, shortcut = true)
        }
        assertTrue(fake.executed.isEmpty())
    }

    @Test
    fun `only nodes inside the editor that are not text fields count as the editor`(robot: FxRobot) {
        val field = TextField()
        val inner = Pane()
        val root = Pane(inner, Pane(field))
        robot.interact {
            assertTrue(insideEditor(inner, root))
            assertFalse(insideEditor(field, root), "a text field, like a find bar field")
            assertFalse(insideEditor(Pane(), root))
            assertFalse(insideEditor(null, root))
        }
    }

    @Test
    fun `unsubmittedEdits reflects the results areas`(robot: FxRobot) {
        assertFalse(entry.unsubmittedEdits())
        pending += 0
        assertTrue(entry.unsubmittedEdits())
    }
}
