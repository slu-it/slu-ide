package dev.sluide.codeintelligence.quickfix

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.diagnostics.PlacedDiagnostic
import dev.sluide.codeintelligence.diagnostics.diagnosticsFor
import dev.sluide.codeintelligence.diagnostics.withRange
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.ui.CaretPopup
import dev.sluide.codeintelligence.ui.PopupCell
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.RequestSlot
import dev.sluide.languageservers.ServerState
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.WorkspaceEdit

/** Alt+Enter: the quick fixes at the caret. */
internal val QUICK_FIX_KEYS = KeyCodeCombination(KeyCode.ENTER, KeyCombination.ALT_DOWN)

internal const val CODE_ACTION_METHOD = "textDocument/codeAction"

/** Why the quick fixes cannot be asked for, `null` if they can. */
internal fun quickFixBlocker(
    state: ServerState,
    supported: Boolean,
): String? =
    when {
        state != ServerState.Running -> "server not running"
        !supported -> "not offered by the server"
        else -> null
    }

/**
 * The quick fixes of one bound editor (ADR 0148): Alt+Enter and "Show Quick Fixes" in the context menu ask the server
 * for the code actions at the caret or selection, with the diagnostics there, and list them at the caret (preferred
 * first, disabled ones with their reason and not choosable). The one taken is resolved first if it has neither edit
 * nor command, then its edit applied through [edits] and its command run (`workspace/executeCommand`). A text changed
 * since the request refuses it. What failed shows at the caret. FX thread.
 */
internal class QuickFixes(
    private val binding: DocumentBinding,
    private val diagnostics: () -> List<PlacedDiagnostic>,
    private val edits: WorkspaceEdits,
) : AutoCloseable {
    private val session = binding.session
    private val editor = binding.editor
    private val slot = RequestSlot()
    private val handles = mutableListOf<AutoCloseable>()
    private var popup: CaretPopup<QuickFix>? = null
    private var notice: CaretPopup<String>? = null
    private var requestedVersion = -1
    private var closed = false

    init {
        handles += editor.addKeyBinding(QUICK_FIX_KEYS) { show() }
        handles += binding.addContextMenuItems { listOf(menuItem()) }
    }

    private fun blocker() = quickFixBlocker(session.state.value, session.features.supports(CODE_ACTION_METHOD))

    private fun menuItem(): MenuItem {
        val reason = blocker()
        return MenuItem(if (reason == null) "Show Quick Fixes" else "Show Quick Fixes ($reason)").apply {
            // Shown only: the editor's context menu installs no accelerators, the key binding above takes the key.
            accelerator = QUICK_FIX_KEYS
            isDisable = reason != null
            setOnAction { show() }
        }
    }

    /** Asks for the quick fixes at the caret or selection and shows them; `false` if the server cannot answer. */
    fun show(): Boolean {
        if (closed || blocker() != null) return false
        val selection = editor.selection
        val shown = popup ?: newPopup().also { popup = it }
        notice?.hide()
        shown.placeholder = "Searching…"
        shown.setItems(emptyList())
        if (!shown.show(editor.caretOffset)) return true
        ask(selection.start, selection.end) { fixes, failure ->
            if (shown.isShowing) {
                shown.placeholder = failure ?: "No quick fixes"
                shown.setItems(fixes)
            }
        }
        return true
    }

    /**
     * Asks the server for the code actions of [start, end) with the diagnostics there; [answer] gets them in the order
     * shown, or a failure to show instead. A later request replaces it.
     */
    internal fun ask(
        start: Int,
        end: Int,
        answer: (List<QuickFix>, String?) -> Unit,
    ) {
        requestedVersion = binding.document.version
        val params =
            CodeActionParams(
                binding.documentId,
                binding.rangeOf(start, end),
                CodeActionContext(
                    // Where they are now: an edit since the server's answer moved them.
                    diagnosticsFor(diagnostics(), start, end).map {
                        withRange(it.diagnostic, binding.rangeOf(it.ownStart, it.ownEnd))
                    },
                ).apply {
                    triggerKind = CodeActionTriggerKind.Invoked
                },
            )
        slot.send(
            session,
            { it.textDocumentService.codeAction(params) },
            { result -> if (!closed) answer(quickFixesOf(result), null) },
            { error ->
                if (!closed) {
                    answer(
                        emptyList(),
                        if (error is LanguageServerNotRunningException) "The server is not running" else null,
                    )
                }
            },
        )
    }

    private fun newPopup() =
        CaretPopup<QuickFix>(
            editor,
            cell = { fix -> PopupCell(fix.title, fix.disabledReason) },
            onChoose = ::choose,
            acceptTab = false,
            choosable = { it.enabled },
        )

    /** Applies [fix], taken from the answer of the last [ask]. */
    internal fun choose(fix: QuickFix) {
        val action = fix.action
        if (action != null && needsResolve(action, session.features.supports("codeAction/resolve"))) {
            session.request(
                { it.textDocumentService.resolveCodeAction(action) },
                { resolved ->
                    if (resolved == null) {
                        tell("The server did not resolve the quick fix.")
                    } else {
                        perform(resolved.edit, resolved.command)
                    }
                },
                { error -> tell("The quick fix could not be resolved: ${reasonOf(error)}") },
            )
        } else {
            perform(action?.edit, fix.command)
        }
    }

    private fun perform(
        edit: WorkspaceEdit?,
        command: Command?,
    ) {
        if (closed) return
        if (binding.document.version != requestedVersion) {
            tell("The text changed. Ask for the quick fixes again.")
            return
        }
        if (edit == null) {
            command?.let(::execute)
            return
        }
        edits.apply(edit) { result ->
            if (result.applied) {
                command?.let(::execute)
            } else {
                tell(quickFixFailure(result))
            }
        }
    }

    private fun execute(command: Command) {
        session.request(
            { it.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)) },
            {},
            { error -> tell("The quick fix's command failed: ${reasonOf(error)}") },
        )
    }

    /** What shows a failure; tests replace it. */
    internal var tell: (String) -> Unit = ::showNotice

    /** Shows [message] at the caret until the next key, click or scroll. */
    private fun showNotice(message: String) {
        if (closed) return
        val shown = notice ?: CaretPopup<String>(editor, { PopupCell(it) }, {}, acceptTab = false).also { notice = it }
        shown.placeholder = message
        shown.show(editor.caretOffset)
    }

    override fun close() {
        closed = true
        slot.cancel()
        handles.forEach { it.close() }
        handles.clear()
        popup?.close()
        notice?.close()
    }
}

private fun reasonOf(error: Throwable): String = error.message ?: error.javaClass.simpleName
