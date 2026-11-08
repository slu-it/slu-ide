package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.NavigationHistory
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.Position
import java.nio.file.Path

internal const val DEFINITION = "textDocument/definition"
internal const val REFERENCES = "textDocument/references"
internal const val DOCUMENT_HIGHLIGHT = "textDocument/documentHighlight"

/** What the navigation of one bound editor does at an offset: the actions of the Navigate menu and the keys. */
internal interface BindingNavigation {
    fun goToDefinition(offset: Int)

    fun findUsages(offset: Int)
}

/**
 * The navigation of one window (ADR 0146), owned by its `CodeIntelligence`: the [history], the bound editor that has
 * the focus, the actions of each bound editor ([register]), and the jumps between places, which the history records.
 * FX thread.
 *
 * @param bindings the bound editors of the window.
 * @param fallbackHost the host to open files with when no bound editor is at hand (Back after its editor closed).
 */
internal class Navigation(
    val history: NavigationHistory,
    private val bindings: () -> List<DocumentBinding>,
    private val fallbackHost: () -> FileViewHost?,
) {
    /** Whether a binding's editor has the focus of its window; tests without a scene replace it. */
    var isFocused: (DocumentBinding) -> Boolean = ::hasFocus

    private val actions = LinkedHashMap<DocumentBinding, BindingNavigation>()

    /** Makes [navigation] the actions of [binding] until the handle is closed. */
    fun register(
        binding: DocumentBinding,
        navigation: BindingNavigation,
    ): AutoCloseable {
        actions[binding] = navigation
        return AutoCloseable { actions.remove(binding, navigation) }
    }

    /** The bound editor with the focus, `null` if the focus is elsewhere. */
    fun focused(): DocumentBinding? = bindings().firstOrNull { !it.isClosed && isFocused(it) }

    /** The actions of [binding], if its navigation is attached. */
    fun actionsOf(binding: DocumentBinding): BindingNavigation? = actions[binding]

    /** Jumps from the caret of [from] to [to], recorded in the history. */
    fun jump(
        from: DocumentBinding,
        to: SourcePosition,
    ) {
        history.record(caretPosition(from), to)
        go(to, from)
    }

    /** Goes back to the place before the last jump; `false` if there is none. */
    fun back(): Boolean = step(history::back)

    /** Goes forward again; `false` if there is nothing ahead. */
    fun forward(): Boolean = step(history::forward)

    private fun step(next: (SourcePosition?) -> SourcePosition?): Boolean {
        val focused = focused()
        val target = next(focused?.let(::caretPosition)) ?: return false
        go(target, focused)
        return true
    }

    /**
     * Shows [target]: in the editor of [from] if it has the focus and shows that file (the caret moved there), else
     * through the host, which selects the file's tab (or opens it) and puts the caret there.
     */
    private fun go(
        target: SourcePosition,
        from: DocumentBinding?,
    ) {
        if (from != null && !from.isClosed && sameFile(from.file, target.file) && isFocused(from)) {
            from.editor.moveCaret(from.offsetOf(Position(target.line, target.column)))
            from.editor.popupOwner?.requestFocus()
            return
        }
        val host = from?.editor?.host ?: bindings().firstOrNull()?.editor?.host ?: fallbackHost()
        host?.openFile(target.file, target.line, target.column)
    }
}

/** Where the caret of [binding] is. */
internal fun caretPosition(binding: DocumentBinding): SourcePosition {
    val position = binding.positionOf(binding.editor.caretOffset)
    return SourcePosition(binding.file, position.line, position.character)
}

/** Whether [a] and [b] name the same file, compared absolute and normalized (no file system access). */
internal fun sameFile(
    a: Path,
    b: Path,
): Boolean = a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

/** Whether the text area of [binding]'s editor is the focus owner of its scene. */
private fun hasFocus(binding: DocumentBinding): Boolean {
    val area = binding.editor.popupOwner ?: return false
    return area.scene?.focusOwner === area
}

/** Why a navigation request cannot go to a server in [state] that [supported] it or not; `null` if it can. */
internal fun navigationBlocker(
    state: ServerState,
    supported: Boolean,
): String? =
    when (state) {
        ServerState.Running -> if (supported) null else "not supported"
        ServerState.Starting, is ServerState.Restarting -> "server starting"
        ServerState.Stopped -> "server not started"
        ServerState.Unavailable -> "server not installed"
        ServerState.Untrusted -> "project not trusted"
        is ServerState.Failed -> "server stopped"
    }

/** Why [method] cannot be asked of [binding]'s server now; `null` if it can. */
internal fun navigationBlocker(
    binding: DocumentBinding,
    method: String,
): String? = navigationBlocker(binding.session.state.value, binding.session.features.supports(method))
