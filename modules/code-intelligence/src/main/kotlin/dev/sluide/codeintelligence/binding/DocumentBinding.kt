package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.ui.HoverProviders
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.DocumentChange
import dev.sluide.languageservers.LanguageServerSession
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.SyncedDocument
import javafx.scene.control.MenuItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import java.nio.file.Path

private val log = System.getLogger(DocumentBinding::class.java.name)

/**
 * A feature of the code intelligence that works on one bound editor (ADR 0145): navigation, completion, diagnostics.
 * [attach] is called once per binding, in the order of the feature list, after the document is open; the handle
 * (`null` for none) is closed when the binding ends (the editor is disposed, or its file is renamed out of the
 * language). FX thread.
 */
fun interface BindingFeature {
    fun attach(binding: DocumentBinding): AutoCloseable?
}

/**
 * One code editor whose file a language server serves (ADR 0145): its file is open on the server ([document]), every
 * change of the editor's text is sent as it happens, a save as `didSave`. What the features attach to: the editor, the
 * spec and session, positions converted between LSP and the editor, context menu items and hover providers that live
 * as long as the binding. FX thread.
 *
 * Positions convert on the document's own copy of the text, which equals the editor's text whenever the FX thread is
 * not inside a batch of changes, so results that arrive later convert on the text they are shown in.
 */
class DocumentBinding internal constructor(
    /** The bound editor. */
    val editor: ExtensibleCodeEditor,
    /** The language of the file. */
    val spec: LanguageServerSpec,
    /** The window's language servers, e.g. for [LanguageServers.filesChanged]. */
    val languageServers: LanguageServers,
) : AutoCloseable {
    /** The server connection of [spec] in this window. */
    val session: LanguageServerSession = languageServers.session(spec)

    /** The file as it is open on the server. */
    val document: SyncedDocument

    /** The sources of the hover popup over this editor, asked in the order added. */
    val hovers = HoverProviders()

    private val menuProviders = mutableListOf<(EditorMenuTarget) -> List<MenuItem>>()
    private val handles = mutableListOf<AutoCloseable>()

    /** Whether the binding ended; features check it in late callbacks. */
    var isClosed = false
        private set

    init {
        val file = editor.file.value
        document = session.documents.open(file, spec.languageId(file), editor.text)
        handles +=
            editor.onTextChange { change ->
                document.changed(DocumentChange(change.start, change.removed, change.inserted)) { editor.text }
            }
        handles += editor.onSaved { document.saved() }
        // Also a server that was missing or untrusted before: installed or trusted since.
        session.ensureStarted()
    }

    /** The file the binding is for; follows moves within the language. */
    val file: Path get() = document.file

    /** The document's identifier for request parameters. */
    val documentId: TextDocumentIdentifier get() = TextDocumentIdentifier(LspUris.of(file))

    /** The LSP position of [offset] (clamped) in the editor's text. */
    fun positionOf(offset: Int): Position = document.positionOf(offset)

    /** The editor offset of [position] (clamped to its line and the text). */
    fun offsetOf(position: Position): Int = document.offsetOf(position)

    /** The parameters of a request at [offset]: this document and the position there. */
    fun positionParams(offset: Int): TextDocumentPositionParams =
        TextDocumentPositionParams(documentId, positionOf(offset))

    /** The LSP range of the editor's [start, end). */
    fun rangeOf(
        start: Int,
        end: Int,
    ): Range = Range(positionOf(start), positionOf(end))

    /**
     * The editor offsets of [range] as `start until end`: an empty range still has its offset as `first`. A range whose
     * end lies before its start is empty at its start.
     */
    fun offsetsOf(range: Range): IntRange {
        val start = offsetOf(range.start)
        return start until offsetOf(range.end).coerceAtLeast(start)
    }

    /** [edit] as an editor edit for [ExtensibleCodeEditor.applyEdits]. */
    fun editOf(edit: TextEdit): OffsetEdit {
        val offsets = offsetsOf(edit.range)
        return OffsetEdit(offsets.first, offsets.last + 1, edit.newText.orEmpty())
    }

    /** [edits] as editor edits, in the order given. */
    fun editsOf(edits: List<TextEdit>): List<OffsetEdit> = edits.map(::editOf)

    /**
     * Adds items to the editor's context menu while the binding lasts, after the editor's own and asked each time the
     * menu shows; closing the handle removes them.
     */
    fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>): AutoCloseable {
        menuProviders += items
        return AutoCloseable { menuProviders -= items }
    }

    /** Closes [handle] with the binding: for what a feature adds outside its own handle. */
    fun closeWithBinding(handle: AutoCloseable) {
        if (isClosed) handle.close() else handles += handle
    }

    /** The items of the features for the menu opened at [target]. */
    internal fun menuItems(target: EditorMenuTarget): List<MenuItem> =
        menuProviders.toList().flatMap { provider ->
            try {
                provider(target)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "A code intelligence menu item failed for $file", e)
                emptyList()
            }
        }

    /** Attaches [features] in order; one that throws is logged and left out. */
    internal fun attach(features: List<BindingFeature>) {
        for (feature in features) {
            try {
                feature.attach(this)?.let(handles::add)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "A code intelligence feature failed to attach to $file", e)
            }
        }
    }

    /** The editor's file moved to [to], still in this language and project: the server hears close and open. */
    internal fun moved(to: Path) = document.moved(to)

    /** Ends the binding: the features' handles close (last attached first), then the document closes on the server. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        for (handle in handles.asReversed()) {
            try {
                handle.close()
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "Could not close a code intelligence feature of $file", e)
            }
        }
        handles.clear()
        menuProviders.clear()
        document.close()
    }
}
