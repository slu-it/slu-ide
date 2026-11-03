package dev.sluide.databases.ui.console

import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabasesTabModel
import dev.sluide.databases.ui.result.ResultArea
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.TextEditor
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane

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

                override fun markError(offset: Int) = editor.markError(offset)
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

    private var ending: (() -> Unit)? = null

    private fun commit() = end("Committed") { model.commit(entry) }

    private fun rollback() = end("Rolled back") { model.rollback(entry) }

    /** Runs [action]; the transaction is over once `pendingTransaction` turns false, then [done] is logged. */
    private fun end(
        done: String,
        action: () -> Unit,
    ) {
        val property = entry.pendingTransaction
        ending?.invoke()
        lateinit var listener: ChangeListener<Boolean>
        listener =
            ChangeListener { _, _, pending ->
                if (!pending) {
                    property.removeListener(listener)
                    ending = null
                    results.log(done, false)
                }
            }
        ending = { property.removeListener(listener) }
        property.addListener(listener)
        action()
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

    /** Whether [target] is the editor's text, not e.g. a field of its find bar. */
    private fun inEditor(target: Any?): Boolean {
        if (target !is Node || target is TextInputControl) return false
        return generateSequence(target) { it.parent }.any { it === editor.node }
    }

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
