package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.result.RecordingJdbc
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.FakeSession
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TabPane
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.testfx.api.FxRobot
import java.sql.Connection
import java.sql.Types
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** A session whose edit submits run on a recording connection. */
internal class EditingSession(
    config: ConnectionConfig,
    counts: (String) -> Int = { 1 },
    autoCommit: Boolean = true,
) : FakeSession(config) {
    val jdbc = RecordingJdbc(startAutoCommit = autoCommit, counts = counts)
    val changes = mutableListOf<Boolean>()
    val executed = mutableListOf<String>()

    /** The answer of a reload (the statement is run again after a submit). */
    var reloaded: (String) -> StatementOutcome = { error("no reload expected: $it") }

    /** While set, a submit does not run until [release]. */
    var hold = false
    private var held: (() -> Unit)? = null

    /** A submit's future fails with this instead of running. */
    var failure: Throwable? = null

    fun release() = held?.invoke().also { held = null }

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> {
        executed += sql
        return CompletableFuture.completedFuture(reloaded(sql))
    }

    override fun <T> withSession(
        changes: Boolean,
        work: (Connection) -> T,
    ): CompletableFuture<T> {
        this.changes += changes
        failure?.let { return CompletableFuture.failedFuture(it) }
        if (hold) {
            val future = CompletableFuture<T>()
            held = {
                try {
                    future.complete(work(jdbc.connection))
                } catch (e: RuntimeException) {
                    future.completeExceptionally(e)
                }
            }
            return future
        }
        return try {
            CompletableFuture.completedFuture(work(jdbc.connection))
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }
}

/**
 * The result area of [ResultEditingUiTest] and [ResultSubmitUiTest]: a PostgreSQL connection whose session is
 * [fake], a result of `users` (key `id`) with pages of two rows, and lookups of the grid's controls.
 */
abstract class ResultEditingUiFixture {
    internal val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    internal val fake = EditingSession(config)
    internal val rejecting = EditingSession(config, { 0 })
    internal var lastCursor = FakeCursor(emptyList(), emptyList())
    internal val entry = ConnectionEntry(config, "k").apply { connected(fake) }
    internal val scratch = mutableListOf<Pair<String, String>>()
    internal lateinit var area: ResultArea
    internal val mac = System.getProperty("os.name").lowercase().contains("mac")

    internal val columns = listOf(column("id", "users", "int4"), column("name", "users"))

    internal fun startArea(stage: Stage) {
        area = ResultArea(entry, { 2 }, { 100 }, clipboard = {}, openScratch = { ext, text -> scratch += ext to text })
        area.primaryKeyLookup = { _, _ -> CompletableFuture.completedFuture(listOf("id")) }
        stage.scene = Scene(area.node as Parent, 700.0, 400.0)
        stage.show()
    }

    internal fun rows(
        cols: List<ColumnInfo> = columns,
        sql: String = "select * from users",
    ): StatementOutcome.Rows {
        val cursor = FakeCursor(cols, listOf(listOf<Any?>(1, "a"), listOf<Any?>(2, "b"), listOf<Any?>(3, "c")))
        lastCursor = cursor
        return StatementOutcome.Rows(sql, 5, cols, cursor, cursor.firstPage())
    }

    /** The result of the statement run again after a submit: the rows as they are in the database then. */
    internal fun reloadsWith(
        session: EditingSession,
        name: String,
    ) {
        session.reloaded = { sql ->
            val cursor = FakeCursor(columns, listOf(listOf<Any?>(1, name), listOf<Any?>(2, "b"), listOf<Any?>(3, "c")))
            StatementOutcome.Rows(sql, 5, columns, cursor, cursor.firstPage())
        }
    }

    internal fun showRows(
        robot: FxRobot,
        cols: List<ColumnInfo> = columns,
    ): ResultTab {
        robot.interact { area.show(rows(cols)) }
        robot.interact { } // the answer of the key lookup comes through runLater
        return tab()
    }

    internal fun tab() = (area.node as TabPane).tabs[1] as ResultTab

    internal fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun grid() = tab().content.lookup(".result-grid") as TableView<List<Any?>>

    internal fun button(tip: String) =
        tab()
            .content
            .lookupAll(".result-button")
            .filterIsInstance<Button>()
            .first { it.tooltip.text == tip }

    /** The paging bar's buttons in order: first, previous, next, last, Reload. */
    internal fun pagingButton(index: Int) =
        tab()
            .content
            .lookupAll(".result-button")
            .filterIsInstance<Button>()
            .toList()[index]

    internal fun pageField() =
        tab()
            .content
            .lookupAll(".text-field")
            .filterIsInstance<TextField>()
            .first { it.promptText == "2" }

    internal fun editors() = grid().lookupAll(".text-field").filterIsInstance<TextField>()

    internal fun status() = (tab().content.lookup(".result-status") as Label).text

    internal fun key(
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) = Event.fireEvent(
        grid(),
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac),
    )

    internal fun selectCell(
        row: Int,
        column: Int,
    ) {
        val table = grid()
        table.selectionModel.clearSelection()
        table.selectionModel.select(row, table.columns[column + 1])
        table.focusModel.focus(row, table.columns[column + 1])
    }

    internal fun editName(
        robot: FxRobot,
        row: Int,
        text: String,
    ) = editCell(robot, row, 1, text)

    internal fun editCell(
        robot: FxRobot,
        row: Int,
        column: Int,
        text: String,
    ) {
        robot.interact {
            selectCell(row, column)
            key(KeyCode.ENTER)
        }
        robot.interact {
            val field = editors().first()
            field.text = text
            field.onAction.handle(ActionEvent())
        }
    }

    internal fun submitButton() = button("Submit (Shortcut+Enter)")

    internal fun lines(): List<String> =
        ((area.node as TabPane).tabs[0].content.lookupAll(".list-cell"))
            .mapNotNull { (it as ListCell<*>).text }

    internal fun pane() = area.node as TabPane

    internal fun transposeToggle() =
        tab()
            .content
            .lookupAll(".toggle-button")
            .filterIsInstance<ToggleButton>()
            .first()

    internal fun header(column: Int) = grid().columns[column + 1].graphic as Label

    internal fun ids() = grid().items.map { it[0] }

    /** A click on a header that is still since the press (what sorts). */
    internal fun click(label: Node) {
        Event.fireEvent(
            label,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0.0,
                0.0,
                0.0,
                0.0,
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

    internal fun waitUntil(
        robot: FxRobot,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!fx(robot) { condition() }) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.onSpinWait()
        }
    }

    internal val numbers =
        listOf(ColumnInfo("id", "id", "int4", Types.INTEGER, null, "users", false, false), columns[1])
}
