package dev.sluide.languageservers

import dev.sluide.languageservers.workspace.Resolution
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
 *
 * For a spec that gates documents ([LanguageServerSpec.gatesDocuments], ADR 0160) a document reaches the server only
 * while its gate answer says served: nothing is sent before the answer, nor ever for a document that is not served.
 * An answer that flips sends `didClose` (served to not served) or `didOpen` with the current text and the next version
 * (not served to served). Whether a document is open on the server is decided on the FX thread and checked once more
 * on the outbound thread per connection: a `didClose` goes only to a connection that got the document's `didOpen`
 * (the TypeScript server crashes on any other).
 */
class DocumentSync internal constructor(
    private val session: LanguageServerSession,
) {
    private val documents = LinkedHashMap<Path, Document>()

    init {
        session.onConnected { documents.values.filter { it.isOpenOnServer }.forEach { it.reopen() } }
    }

    /**
     * Opens [file] on the server (`didOpen`, version 1) with [text], the editor's text (with `\n` line ends, as the
     * editor normalizes it), starting the server if it is stopped. Counted per file: opening an open file returns
     * another handle to the same document (with the text it has) and the server hears `didClose` when the last handle
     * closes. Only one editor feeds a file's changes (one editor per file, ADR 0134); other handles read positions and
     * save. For a spec that gates documents, the `didOpen` (and the start) waits until the gate answered served.
     */
    fun open(
        file: Path,
        languageId: String,
        text: String,
    ): SyncedDocument {
        val key = key(file)
        documents[key]?.let { open ->
            open.handles++
            return SyncedDocument(open)
        }
        val document = Document(key, languageId, text)
        documents[key] = document
        document.handles = 1
        document.resolveFolder()
        document.sync()
        return SyncedDocument(document)
    }

    /** Whether [file] is open. */
    fun isOpen(file: Path): Boolean = key(file) in documents

    /** The gate answer of the open [file] ([LanguageServerSession.gateOf]). */
    internal fun gateOf(file: Path): DocumentGate? = documents[key(file)]?.gate

    /** Whether an open document is served. */
    internal fun anyServed(): Boolean = documents.values.any { it.gate?.served == true }

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

        override var resolution: CompletableFuture<Resolution> =
            CompletableFuture.completedFuture(Resolution(null, DocumentGate.SERVED))
            private set

        private val folderWrapper = ReadOnlyObjectWrapper<Path?>(null)
        val folderValue: ObservableValue<Path?> = folderWrapper.readOnlyProperty

        private val gating get() = session.spec.gatesDocuments

        /** The gate answer shown ([LanguageServerSession.gateOf]): `null` until a gating spec answered. */
        var gate: DocumentGate? = if (gating) null else DocumentGate.SERVED
            private set

        /** Whether a `didOpen` was queued since the last `didClose`: open on the server as the FX thread decided. */
        var isOpenOnServer = false
            private set

        /** Whether any `didOpen` was queued: the first one has version 1, every later one the next version. */
        private var announced = false

        /** The connection that got this document's `didOpen` and no `didClose` since; outbound thread only. */
        @Volatile
        private var openedOn: Int? = null

        /** The fail-closed wait of a gating spec's unanswered resolution. */
        private var timeout: AutoCloseable? = null

        private val isLive get() = !detached && handles > 0 && documents[file] === this

        /** Asks the spec for the workspace folder (and gate) of [file]; the values follow on the FX thread. */
        fun resolveFolder() {
            adopt(session.workspace.resolve(file))
        }

        override fun adopt(resolution: CompletableFuture<Resolution>): Boolean {
            this.resolution = resolution
            timeout?.close()
            timeout = null
            if (resolution.isDone) return show(resolution, resolution.join())
            resolution.thenAccept { answer -> session.fx { show(resolution, answer) } }
            if (gating) timeout = session.workspace.afterWait { failClosed(resolution) }
            return false
        }

        private fun show(
            resolution: CompletableFuture<Resolution>,
            answer: Resolution,
        ): Boolean {
            if (this.resolution !== resolution || detached || documents[file] !== this) return false
            timeout?.close()
            timeout = null
            return apply(answer.folder, answer.gate)
        }

        /** The gating spec did not answer within the folder wait: not served until it does (fail closed). */
        private fun failClosed(resolution: CompletableFuture<Resolution>) {
            if (this.resolution !== resolution || resolution.isDone || !isLive) return
            log.log(System.Logger.Level.WARNING, "lsp ${session.spec.id}: no gate answer for $file yet; not served")
            apply(folderWrapper.get(), Resolution.NOT_SERVED)
        }

        /** Shows [folder] and [newGate], opens or closes the document on the server as it says; whether it did. */
        private fun apply(
            folder: Path?,
            newGate: DocumentGate,
        ): Boolean {
            val folderChanged = folderWrapper.get() != folder
            if (folderChanged) folderWrapper.set(folder)
            val gateChanged = gate != newGate
            gate = newGate
            if (gateChanged && isLive) session.workspace.gates.name(this, newGate.watched)
            val synced = sync()
            if (folderChanged || gateChanged) session.workspaceChanged()
            return synced
        }

        /**
         * Opens the document on the server if it is open here and served but not open there, closes it if it is open
         * there but no longer here or no longer served; whether it sent one. A gating spec's server starts with the
         * first document served.
         */
        fun sync(): Boolean {
            val wanted = isLive && gate?.served == true
            if (wanted == isOpenOnServer) return false
            if (wanted) {
                if (gating) session.startServed()
                if (announced) version++
                sendOpen()
            } else {
                sendClose()
            }
            return true
        }

        private fun sendOpen() {
            isOpenOnServer = true
            announced = true
            val params = DidOpenTextDocumentParams(TextDocumentItem(uri, languageId, version, text.toString()))
            session.notifyInFolder(resolution) { running ->
                running.server.textDocumentService.didOpen(params)
                openedOn = running.epoch
            }
        }

        override fun reopenOnServer() {
            if (detached || !isOpenOnServer) return
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
            if (!isOpenOnServer) return
            val params = DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), listOf(event))
            session.notifyRunning { if (openedOn == it.epoch) it.server.textDocumentService.didChange(params) }
        }

        fun saved() {
            if (detached || !isOpenOnServer) return
            val options = session.features.saveOptions() ?: return
            val params =
                DidSaveTextDocumentParams(
                    TextDocumentIdentifier(uri),
                    if (options.includeText) text.toString() else null,
                )
            session.notifyRunning { if (openedOn == it.epoch) it.server.textDocumentService.didSave(params) }
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
            session.workspace.gates.forget(this)
            file = target
            documents[target] = this
            // The answer was for the old path: a gating spec is asked again, not served until it answers.
            val hadGate = gating && gate != null
            if (gating) gate = null
            resolveFolder()
            sync()
            if (hadGate && gate == null) session.workspaceChanged()
        }

        fun closed() {
            handles--
            if (handles > 0 || detached) return
            documents.remove(file, this)
            ended()
        }

        /** Closes the document on the server for good; its handles do nothing any more. */
        private fun detach() {
            documents.remove(file, this)
            detached = true
            ended()
        }

        /** The document is no longer open here: closed on the server if it was open there, its watches end. */
        private fun ended() {
            sendClose()
            session.workspace.gates.forget(this)
            timeout?.close()
            timeout = null
        }

        /**
         * `didClose`, decided on the outbound thread: only a connection that got the document's `didOpen` (and no
         * `didClose` since) gets it, whatever the FX thread believes; on another one (a new server, or none got it, the
         * document was not served) it is dropped, as the server never saw the document. Starts no server.
         */
        private fun sendClose() {
            isOpenOnServer = false
            val params = DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
            session.notifyRunning(start = false) { running ->
                if (openedOn != running.epoch) return@notifyRunning
                openedOn = null
                running.server.textDocumentService.didClose(params)
            }
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
     * on a new server. A document a gating spec does not serve keeps counting its changes; its first `didOpen` carries
     * the version it has then, a later one the next.
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
