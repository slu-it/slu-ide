package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.toSourcePosition
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams

/**
 * Find usages in one bound editor (ADR 0146): asks the server (`textDocument/references`, without the declaration) for
 * the name at an offset, showing "Finding usages…" there meanwhile; the usages are listed in the editor's file first,
 * then by path and line ([sortedUsages]); a single one is jumped to, none shows "No usages found". FX thread.
 *
 * @param requests the editor's slot for navigation requests: a new one replaces the one on its way.
 */
internal class Usages(
    private val binding: DocumentBinding,
    private val chooser: TargetChooser,
    private val navigation: Navigation,
    private val requests: RequestSlot,
) {
    /** Finds the usages of the name at [offset] (the caret). */
    fun find(offset: Int) {
        val range = binding.editor.wordAt(offset, orBefore = true) ?: (offset until offset)
        val blocker = navigationBlocker(binding, REFERENCES)
        if (blocker != null) {
            chooser.hide()
            chooser.note(range, unavailable(ACTION, blocker))
            return
        }
        chooser.waiting(range, "Finding usages…")
        val at = if (range.isEmpty() || offset in range) offset else range.first
        val params =
            ReferenceParams(binding.documentId, binding.positionOf(at), ReferenceContext(false))
        requests.send(
            binding.session,
            { server -> server.textDocumentService.references(params) },
            { locations ->
                if (binding.isClosed) return@send
                val usages = sortedUsages(locations.orEmpty().mapNotNull { it?.toSourcePosition() }, binding.file)
                when (usages.size) {
                    0 -> {
                        chooser.note(range, "No usages found")
                    }

                    1 -> {
                        chooser.hide()
                        navigation.jump(binding, usages.single())
                    }

                    else -> {
                        chooser.choose(range, usages)
                    }
                }
            },
            { error -> if (!binding.isClosed) chooser.note(range, failure(ACTION, error)) },
        )
    }

    private companion object {
        const val ACTION = "Find Usages"
    }
}
