package dev.sluide.languageservers

import dev.sluide.languageservers.workspace.WorkspaceDocument
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private val log = System.getLogger(DocumentSync::class.java.name)

/**
 * The documents a session's server has open (`didOpen`, `didChange`, `didSave`, `didClose`; ADR 0142), one per file
 * ([LanguageServerSession.documents]). Use it on the FX thread only.
 *
 * Each document keeps its own copy of the text and computes the ranges of changes on it, so a batch of changes whose
 * offsets are each in the text the one before left reaches the server as it happened. Every server that starts (also
 * after a restart) gets every open document again with `didOpen`, its current text and a new version; what was sent
 * before that went to the previous server or was dropped (ADR 0142, notifications belong to one connection).
 *
 * Each document's workspace folder is asked off the FX thread when it opens or moves; its `didOpen` waits for the
 * answer on the outbound thread, after the folder was added on the server (ADR 0151).
 */
class DocumentSync internal constructor(
    private val session: LanguageServerSession,
) {
    private val documents = LinkedHashMap<Path, Document>()

    init {
        session.onConnected { documents.values.forEach { it.reopen() } }
    }

    /**
     * Opens [file] on the server (`didOpen`, version 1) with [text], the editor's text (with `\n` line ends, as the
     * editor normalizes it), starting the server if it is stopped. Counted per file: opening an open file returns
     * another handle to the same document (with the text it has) and the server hears `didClose` when the last handle
     * closes. Only one editor feeds a file's changes (one editor per file, ADR 0134); other handles read positions and
     * save.
     */
    fun open(
        file: Path,
        languageId: String,
        text: String,
    ): SyncedDocument {
        val key = key(file)
        val document =
            documents[key] ?: Document(key, languageId, text).also {
                documents[key] = it
                it.resolveFolder()
            }
        document.handles++
        if (document.handles == 1) document.sendOpen()
        return SyncedDocument(document)
    }

    /** Whether [file] is open. */
    fun isOpen(file: Path): Boolean = key(file) in documents

    /** The workspace folder of the open [file] ([LanguageServerSession.workspaceFolderOf]). */
    internal fun folderOf(file: Path): ObservableValue<Path?> =
        documents[key(file)]?.folderValue ?: ReadOnlyObjectWrapper<Path?>(null).readOnlyProperty

    /** The open documents, for the workspace folders. */
    internal fun workspaceDocuments(): List<WorkspaceDocument> = documents.values.toList()

    private fun key(file: Path) = file.toAbsolutePath().normalize()

    /** The state of one open file, shared by its handles. */
    internal inner class Document(
        override var file: Path,
        private val languageId: String,
        text: String,
    ) : WorkspaceDocument {
        val text = StringBuilder(text)
        var version = 1
        var handles = 0

        /** A change did not fit the copy: the copy was replaced by the editor's text, the batch's rest is in it. */
        private var resynced = false
        private var warned = false

        /** Closed on the server because another document moved onto its file; its handles do nothing. */
        var detached = false
            private set

        private val uri get() = LspUris.of(file)

        override var folder: CompletableFuture<Path?> = CompletableFuture.completedFuture(null)
            private set

        private val folderWrapper = ReadOnlyObjectWrapper<Path?>(null)
        val folderValue: ObservableValue<Path?> = folderWrapper.readOnlyProperty

        /** Asks the spec for the workspace folder of [file]; the value follows on the FX thread. */
        fun resolveFolder() = adopt(session.workspace.resolve(file))

        override fun adopt(resolution: CompletableFuture<Path?>) {
            folder = resolution
            if (resolution.isDone) {
                show(resolution, resolution.getNow(null))
            } else {
                resolution.thenAccept { path -> session.fx { show(resolution, path) } }
            }
        }

        private fun show(
            resolution: CompletableFuture<Path?>,
            path: Path?,
        ) {
            if (folder === resolution && folderWrapper.get() != path) {
                folderWrapper.set(path)
                session.workspaceChanged()
            }
        }

        fun sendOpen() {
            val item = TextDocumentItem(uri, languageId, version, text.toString())
            session.notifyInFolder(folder) { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
        }

        override fun reopenOnServer() {
            if (detached) return
            sendClose()
            reopen()
        }

        fun reopen() {
            version++
            sendOpen()
        }

        fun changed(
            change: DocumentChange,
            currentText: () -> String,
        ) {
            if (detached) return
            if (resynced) {
                // The rest of the batch the resync took from the editor: already in the copy.
                if (text.contentEquals(currentText())) return
                resynced = false
            }
            val end = change.start + change.removed.length
            if (change.start < 0 || end > text.length ||
                !text.regionMatches(change.start, change.removed, 0, change.removed.length)
            ) {
                resync(change, currentText())
                return
            }
            val range = TextPositions.rangeOf(text, change.start, end)
            text.replace(change.start, end, change.inserted)
            version++
            when (session.features.textDocumentSyncKind()) {
                TextDocumentSyncKind.Incremental -> sendChange(TextDocumentContentChangeEvent(range, change.inserted))
                TextDocumentSyncKind.Full -> sendChange(TextDocumentContentChangeEvent(text.toString()))
                else -> Unit
            }
        }

        /** The copy went wrong (a programming error): logged once, the server gets the editor's whole text. */
        private fun resync(
            change: DocumentChange,
            editorText: String,
        ) {
            if (!warned) {
                warned = true
                log.log(
                    System.Logger.Level.WARNING,
                    "lsp ${session.spec.id}: change at ${change.start} (${change.removed.length} removed) " +
                        "does not fit the copy of $file (${text.length} chars); sending the whole text",
                )
            }
            text.setLength(0)
            text.append(editorText)
            version++
            resynced = true
            if (session.features.textDocumentSyncKind() != TextDocumentSyncKind.None) {
                sendChange(TextDocumentContentChangeEvent(editorText))
            }
        }

        private fun sendChange(event: TextDocumentContentChangeEvent) {
            val params = DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), listOf(event))
            session.notify { it.textDocumentService.didChange(params) }
        }

        fun saved() {
            if (detached) return
            val options = session.features.saveOptions() ?: return
            val params =
                DidSaveTextDocumentParams(
                    TextDocumentIdentifier(uri),
                    if (options.includeText) text.toString() else null,
                )
            session.notify { it.textDocumentService.didSave(params) }
        }

        fun moved(to: Path) {
            val target = key(to)
            if (detached || target == file) return
            documents[target]?.let { stale ->
                // Moves never replace a file (ADR 0073), so the document open there is a leftover of a missed close.
                log.log(
                    System.Logger.Level.WARNING,
                    "lsp ${session.spec.id}: $file moved onto open $target; closing it",
                )
                stale.detach()
            }
            sendClose()
            documents.remove(file, this)
            file = target
            documents[target] = this
            resolveFolder()
            reopen()
        }

        fun closed() {
            handles--
            if (handles > 0 || detached) return
            documents.remove(file, this)
            sendClose()
        }

        /** Closes the document on the server for good; its handles do nothing any more. */
        private fun detach() {
            sendClose()
            documents.remove(file, this)
            detached = true
        }

        private fun sendClose() {
            val params = DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
            session.notify { it.textDocumentService.didClose(params) }
        }
    }
}

/**
 * One handle to a document open on a language server ([DocumentSync.open]); FX thread only. Its methods do nothing
 * once it is closed, or once another document moved onto its file, so late editor events are harmless.
 */
class SyncedDocument internal constructor(
    private val document: DocumentSync.Document,
) : AutoCloseable {
    private var closed = false

    /** The document's file; changes with [moved]. */
    val file: Path get() = document.file

    /**
     * The version the server last got or will get: 1 at open, one more with every change, every move and every reopen
     * on a new server.
     */
    val version: Int get() = document.version

    /** The document's own copy of the text, which the changes have been applied to. */
    val text: String get() = document.text.toString()

    /**
     * Applies [change] to the copy and sends it (`didChange`, new version): as a range on the copy before the change
     * if the server syncs incrementally, the whole text if it syncs fully, nothing if it does not want changes. Call it
     * once per change, in order, as the editor delivers them. A change that does not fit the copy (its `removed` is
     * not there: a bug) is logged once and the server gets [currentText] (the editor's text) as a whole instead; the
     * changes of the same batch that follow are in that text already and are skipped.
     */
    fun changed(
        change: DocumentChange,
        currentText: () -> String,
    ) {
        if (!closed) document.changed(change, currentText)
    }

    /** The file was saved: `didSave` (with the text if the server asks for it), if the server wants it at all. */
    fun saved() {
        if (!closed) document.saved()
    }

    /**
     * The file was renamed or moved to [to] and the editor kept: `didClose` for the old URI, `didOpen` for the new one
     * with the current text and the language id given at open. The caller checks first that [to] is still inside the
     * project and served by this spec, and closes the document otherwise. A document still open at [to] (a missed
     * close) is logged and closed.
     */
    fun moved(to: Path) {
        if (!closed) document.moved(to)
    }

    /** The position of [offset] in the copy (clamped), for request parameters. */
    fun positionOf(offset: Int): Position = TextPositions.positionOf(document.text, offset)

    /** The offset of [position] in the copy (clamped), for results. */
    fun offsetOf(position: Position): Int = TextPositions.offsetOf(document.text, position)

    /** Closes this handle; the last one closes the document on the server (`didClose`). */
    override fun close() {
        if (closed) return
        closed = true
        document.closed()
    }
}
