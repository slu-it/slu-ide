package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.FILE_STRUCTURE_KEYS
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.navigation.Navigation
import dev.sluide.codeintelligence.navigation.failure
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.codeintelligence.navigation.unavailable
import dev.sluide.codeintelligence.ui.CaretNote
import dev.sluide.codeintelligence.ui.SearchAnchor
import dev.sluide.codeintelligence.ui.SearchPopup
import dev.sluide.codeintelligence.ui.SearchView
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.DocumentSymbolParams

internal const val DOCUMENT_SYMBOL = "textDocument/documentSymbol"

/**
 * The file structure of one bound editor (ADR 0149): asks the server for the file's symbols
 * (`textDocument/documentSymbol`) and lists them in the search popup, nested by indentation, the symbol at the caret
 * selected; typing filters them ([shownSymbols]). Taking one jumps to its name, recorded in Back and Forward. When the
 * server cannot answer, [note] says why at the caret. FX thread.
 */
internal class FileStructure(
    private val binding: DocumentBinding,
    private val navigation: Navigation,
    private val view: SearchView<ShownSymbol>,
    private val note: (Int, String) -> Unit,
) : AutoCloseable {
    private val slot = RequestSlot()
    private var rows: List<SymbolRow> = emptyList()
    private var atCaret: SymbolRow? = null

    init {
        view.onQuery = ::filter
        view.onChoose = { shown ->
            val start = shown.row.selection.start
            navigation.jump(binding, SourcePosition(binding.file, start.line, start.character))
        }
        view.onHidden = slot::cancel
    }

    /** Shows the structure of the file, the symbol at the caret selected. */
    fun show() {
        val caret = binding.editor.caretOffset
        val blocker = navigationBlocker(binding, DOCUMENT_SYMBOL)
        if (blocker != null) return note(caret, unavailable(ACTION, blocker))
        rows = emptyList()
        atCaret = null
        view.placeholder = "Searching…"
        view.setItems(emptyList())
        if (!view.open()) return
        val position = binding.positionOf(caret)
        slot.send(
            binding.session,
            { it.textDocumentService.documentSymbol(DocumentSymbolParams(binding.documentId)) },
            { result ->
                if (view.isShowing && !binding.isClosed) {
                    rows = symbolRows(result)
                    atCaret = symbolAt(rows, position)
                    view.placeholder = "No symbols"
                    filter(view.query)
                }
            },
            { error -> if (view.isShowing) view.placeholder = failure(ACTION, error) },
        )
    }

    /** Shows the rows for [query]: the symbol at the caret selected while it is blank, else the best match. */
    private fun filter(query: String) {
        val shown = shownSymbols(rows, query)
        val selected = if (query.isBlank()) shown.firstOrNull { it.row == atCaret } else null
        view.setItems(shown, selected ?: shown.firstOrNull())
    }

    override fun close() {
        slot.cancel()
        view.close()
    }

    private companion object {
        const val ACTION = "File Structure"
    }
}

/**
 * File structure in a bound editor: Shortcut+F12, the editor's own binding, always taken in a bound editor (a note
 * says why when it cannot act), and Navigate → File Structure through [Navigation.registerStructure].
 */
internal class FileStructureFeature(
    private val navigation: Navigation,
    private val view: (ExtensibleCodeEditor) -> SearchView<ShownSymbol> = { editor ->
        SearchPopup({ editorAnchor(editor) }, ::symbolCell)
    },
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        val notes = CaretNote(editor)
        val structure = FileStructure(binding, navigation, view(editor), notes::show)
        val handles =
            listOf(
                notes,
                structure,
                navigation.registerStructure(binding, structure::show),
                editor.addKeyBinding(FILE_STRUCTURE_KEYS) {
                    structure.show()
                    true
                },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

/** The search popup's place for [editor]: the top centre of its text area, which gets the focus back. */
internal fun editorAnchor(editor: ExtensibleCodeEditor): SearchAnchor? {
    val area = editor.popupOwner ?: return null
    val window = area.scene?.window ?: return null
    val bounds = area.localToScreen(area.boundsInLocal) ?: return null
    return SearchAnchor(window, bounds, area)
}
