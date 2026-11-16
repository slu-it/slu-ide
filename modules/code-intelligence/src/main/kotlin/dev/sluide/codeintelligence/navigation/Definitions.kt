package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.binding.toDefinitionTargets
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.DefinitionParams

/**
 * Go to definition in one bound editor (ADR 0146): asks the server (`textDocument/definition`) for the name at an
 * offset; no declaration shows a note there, one is jumped to, several are offered in the [chooser]. FX thread.
 *
 * @param requests the editor's slot for navigation requests: a new one replaces the one on its way.
 */
internal class Definitions(
    private val binding: DocumentBinding,
    private val chooser: TargetChooser,
    private val navigation: Navigation,
    private val requests: RequestSlot,
    private val libraryDocuments: LibraryDocuments = LibraryDocuments(),
) {
    /** The documents of the last answer on their way, if any. */
    private var fetching: AutoCloseable? = null

    /** Drops the documents on their way: nothing opens for them. */
    fun close() {
        fetching?.close()
    }

    /** Goes to the declaration of the name at [offset] (the caret, or where Shortcut was clicked). */
    fun go(offset: Int) {
        val range = binding.editor.wordAt(offset, orBefore = true) ?: (offset until offset)
        chooser.hide()
        close()
        val blocker = navigationBlocker(binding, DEFINITION)
        if (blocker != null) {
            chooser.note(range, unavailable(ACTION, blocker))
            return
        }
        val at = if (range.isEmpty() || offset in range) offset else range.first
        requests.send(
            binding.session,
            { server ->
                server.textDocumentService.definition(DefinitionParams(binding.documentId, binding.positionOf(at)))
            },
            { result ->
                if (!binding.isClosed) {
                    fetching =
                        libraryDocuments.resolve(
                            binding.session,
                            result.toDefinitionTargets(binding.spec::libraryDocument),
                        ) { positions -> if (!binding.isClosed) found(range, positions) }
                }
            },
            { error -> if (!binding.isClosed) chooser.note(range, failure(ACTION, error)) },
        )
    }

    private fun found(
        range: IntRange,
        positions: List<SourcePosition>,
    ) {
        when (positions.size) {
            0 -> chooser.note(range, "No declaration found")
            1 -> navigation.jump(binding, positions.single())
            else -> chooser.choose(range, positions)
        }
    }

    private companion object {
        const val ACTION = "Go to Definition"
    }
}

/** The note for [action] that cannot be done for [reason] ([navigationBlocker]). */
internal fun unavailable(
    action: String,
    reason: String,
): String = "$action is not available ($reason)"

/** The note for [action] whose request failed with [error]. */
internal fun failure(
    action: String,
    error: Throwable,
): String =
    if (error is LanguageServerNotRunningException) {
        unavailable(action, "server not running")
    } else {
        "$action failed: ${error.message ?: error.javaClass.simpleName}"
    }
