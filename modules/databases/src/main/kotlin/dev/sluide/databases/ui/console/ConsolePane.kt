package dev.sluide.databases.ui.console

import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.ui.ConnectFailures
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import dev.sluide.databases.ui.result.ResultArea
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.TextEditor
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane

/** Whether [target] is in the editor's text under [editorNode], not e.g. a field of a find bar. */
internal fun insideEditor(
    target: Any?,
    editorNode: Node,
): Boolean {
    if (target !is Node || target is TextInputControl) return false
    return generateSequence(target) { it.parent }.any { it === editorNode }
}

/**
 * One console (ADR 0121): a toolbar over an SQL [editor]. Run, Cancel, Commit and History work from the toolbar and
 * from keys while the focus is in the editor. [keyHook] and [showHistory] are where the completion and history popups
 * plug in.
 */
class ConsolePane internal constructor(
    private val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
    history: QueryHistory,
    pageSize: () -> Int,
    editorCss: ObservableValue<String>,
    private val results: ResultArea,
    fx: (Runnable) -> Unit = Platform::runLater,
    gate: RunGate = RunGate(),
) {
    val editor: TextEditor =
        CodeEditors.text("", Highlighters.forLanguage("sql"), "--", "/*" to "*/").also {
            it.node.styleProperty().bind(editorCss)
        }

    /**
     * Called for every KEY_PRESSED inside the editor before the console's own keys; return true when consumed. The
     * completion popup registers here.
     */
    var keyHook: (KeyEvent) -> Boolean = { false }

    /** The History button's action; the history popup registers here. */
    var showHistory: () -> Unit = {}

    private val runner =
        ConsoleRunner(
            entry,
            object : RunEditor {
                override val text get() = editor.text
                override val selectionStart get() = editor.selection.start
                override val selectionEnd get() = editor.selection.end
                override val caret get() = editor.caretOffset

                override fun markError(offset: Int?) = editor.markError(offset)
            },
            object : RunTarget {
                override fun show(
                    outcome: StatementOutcome,
                    replaceUnpinned: Boolean,
                ) = results.show(outcome, replaceUnpinned)

                override fun log(
                    text: String,
                    error: Boolean,
                ) = results.log(text, error)
            },
            history,
            pageSize,
            fx,
            ::choose,
            gate,
        )

    private val toolbar =
        ConsoleToolbar(
            entry,
            model,
            ConsoleActions(
                run = runner::run,
                cancel = runner::cancel,
                commit = ::commit,
                rollback = ::rollback,
                history = { showHistory() },
            ),
            gate,
        )

    val node: Node =
        BorderPane(editor.node).apply {
            styleClass += "console-pane"
            top = toolbar.node
            stylesheets += checkNotNull(ConsolePane::class.java.getResource("console.css")).toExternalForm()
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (inEditor(event.target) && (keyHook(event) || ownKeys(event))) event.consume()
            }
        }

    /** The toolbar, for tests. */
    internal val bar: ConsoleToolbar get() = toolbar

    /** Runs the selection, the only statement, or asks which. */
    fun run() = runner.run()

    private fun choose(
        choice: RunChoice.Choose,
        then: (List<SqlStatement>) -> Unit,
    ) {
        val bounds = editor.caretScreenBounds()
        val corner = editor.node.localToScreen(0.0, 0.0)
        val x = bounds?.minX ?: corner?.x ?: 0.0
        val y = bounds?.maxY ?: corner?.y ?: 0.0
        chooser = StatementChooser(ChooserModel(choice), then).also { it.show(editor.node, x, y) }
    }

    /** The open statement chooser, for tests. */
    internal var chooser: StatementChooser? = null
        private set

    private fun commit() = end("Committed") { done -> model.commit(entry, done) }

    private fun rollback() = end("Rolled back") { done -> model.rollback(entry, done) }

    /** Runs [action]; its outcome is logged: [done] on success, the error in red on failure. */
    private fun end(
        done: String,
        action: ((Throwable?) -> Boolean) -> Unit,
    ) = action { error ->
        // Once disposed the results are gone: a failure falls back to the model's error dialog.
        if (disposed) {
            false
        } else {
            if (error == null) results.log(done, false) else results.log(ConnectFailures.futureMessage(error), true)
            true
        }
    }

    private var disposed = false

    /** Whether a run sequence of this console is under way. */
    internal val running: Boolean get() = runner.running

    /** The console is closed: stops its run sequence and lets go of the entry's properties. */
    internal fun dispose() {
        disposed = true
        runner.stop()
        chooser?.popup?.hide()
        toolbar.dispose()
    }

    private fun ownKeys(event: KeyEvent): Boolean {
        when {
            RUN.match(event) -> runner.run()
            CANCEL.match(event) -> runner.cancel()
            COMMIT.match(event) -> if (entry.pendingTransaction.get()) commit()
            HISTORY.match(event) -> showHistory()
            else -> return false
        }
        return true
    }

    private fun inEditor(target: Any?): Boolean = insideEditor(target, editor.node)

    private companion object {
        val RUN = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
        val CANCEL = KeyCodeCombination(KeyCode.F2, KeyCombination.SHORTCUT_DOWN)
        val COMMIT =
            KeyCodeCombination(
                KeyCode.ENTER,
                KeyCombination.SHORTCUT_DOWN,
                KeyCombination.ALT_DOWN,
                KeyCombination.SHIFT_DOWN,
            )
        val HISTORY = KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
    }
}
