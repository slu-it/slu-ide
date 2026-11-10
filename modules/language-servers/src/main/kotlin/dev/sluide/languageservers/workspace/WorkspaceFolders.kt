package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.workspaceFolder
import dev.sluide.languageservers.files.DirectoryWatcher
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

private val log = System.getLogger(WorkspaceFolders::class.java.name)

/** An open document as the workspace folders see it; FX thread. */
internal interface WorkspaceDocument {
    val file: Path

    /** The latest resolution of its workspace folder and gate. */
    val resolution: CompletableFuture<Resolution>

    /**
     * Takes [resolution] as its own; the values of `workspaceFolderOf` and `gateOf` follow. Whether that opened or
     * closed the document on the server (its gate flipped), so that it needs no reopen.
     */
    fun adopt(resolution: CompletableFuture<Resolution>): Boolean

    /**
     * Opens the document on the server again, `didClose`, then `didOpen` with its current text, under its folder; only
     * if it is open there (served).
     */
    fun reopenOnServer()
}

/** What [WorkspaceFolders] needs from its session; FX thread. */
internal interface WorkspaceHost {
    /** The open documents. */
    fun documents(): Collection<WorkspaceDocument>

    /** Sends to the announced server in order with everything else (`LanguageServerSession.notify`). */
    fun notify(action: (LanguageServer) -> Unit)

    /**
     * Runs [action] on the outbound thread in order with everything sent, whatever server runs then (`null`: none),
     * also one not announced yet: for what must happen even while the server restarts.
     */
    fun onOutbound(action: (LanguageServer?) -> Unit)

    /** Tells the listeners of `workspaceChanges`. */
    fun changed()

    /** Changes in a folder a gate watches, for the server's file watchers (`didChangeWatchedFiles`). */
    fun watchedFilesChanged(changes: List<FileChange>)
}

/**
 * The workspace folders of one session's servers (ADR 0151): the window folder first, then each folder the spec
 * names for an open document ([LanguageServerSpec.workspaceFolder]), added before that document's `didOpen` and kept
 * across restarts, until the window folder changes or the session closes.
 *
 * The set changes on the outbound thread only (at a start, before a `didOpen`, when a folder is removed), where it is
 * in order with what the server got; any thread reads it ([current]). The spec is asked on the session's [worker]. A
 * folder is refreshed (removed and added again, its documents re-opened: ty then reads its venv again) when a folder
 * the spec watches for it changes, or when the spec names other folders to watch than before. A project change the
 * spec names ([LanguageServerSpec.affectsWorkspaceFolders]) asks the folders of the open documents below it again.
 * Both wait [debounce] for the changes of a burst.
 *
 * For a spec that gates documents ([LanguageServerSpec.gatesDocuments], ADR 0160) the spec's gate is asked with each
 * folder, in the same [Resolution]; a change in a folder a gate answer names asks the gates of the documents that
 * named it again ([DocumentGates]).
 */
internal class WorkspaceFolders(
    private val spec: LanguageServerSpec,
    private val projectFolder: () -> Path?,
    private val worker: Executor,
    /** Runs on the FX thread unless the session closed. */
    private val dispatch: (Runnable) -> Unit,
    private val scheduler: Scheduler,
    debounce: Duration,
    /** How long a `didOpen` waits for its document's folder before the window folder serves it. */
    private val resolveWait: Duration,
    directoryWatcher: DirectoryWatcher,
    private val host: WorkspaceHost,
) {
    private val lock = Any()

    @Volatile
    private var known: List<Path> = emptyList()

    @Volatile
    private var closed = false

    private val debouncer = Debouncer<Pending>(scheduler, debounce)
    private val watches =
        FolderWatches(directoryWatcher) { folder ->
            debouncer.trigger(Pending.Refresh(folder)) { fx { refresh(folder) } }
        }

    /** The watches of the folders the gate answers name, and which documents named them. */
    val gates: DocumentGates =
        DocumentGates(directoryWatcher, { task -> onWorker(task = task) }) { folder, changes ->
            if (changes.isNotEmpty()) fx { host.watchedFilesChanged(changes) }
            debouncer.trigger(Pending.Gate(folder)) { fx { reResolve(gates.naming(folder)) } }
        }

    /** What a document resolves to while its spec cannot answer: the window folder; for a gating spec not served. */
    private val unanswered = Resolution(null, if (spec.gatesDocuments) Resolution.NOT_SERVED else DocumentGate.SERVED)

    /** Resolutions a `didOpen` stopped waiting for; their answer re-opens the document when it comes. */
    private val late = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))

    /** The workspace folders now, the window folder first; any thread, never blocks (`workspace/workspaceFolders`). */
    val current: List<Path> get() = known

    /**
     * The folders a server started for the window folder [root] gets in `initialize`: those known, or only [root] if
     * the window folder changed (its watches end). Outbound thread.
     */
    fun foldersFor(root: Path): List<Path> {
        val window = root.toAbsolutePath().normalize()
        val reset =
            synchronized(lock) {
                if (known.firstOrNull() == window) return known
                known = listOf(window)
                true
            }
        if (reset) {
            watches.closeAll()
            watchLater(window)
        }
        return known
    }

    /**
     * Asks the spec on the worker which folder serves [file], and its gate; FX thread. Never fails: a failure is the
     * window folder, and for a gating spec not served.
     */
    fun resolve(file: Path): CompletableFuture<Resolution> {
        val result = CompletableFuture<Resolution>()
        val root = window() ?: return result.also { it.complete(unanswered) }
        onWorker({ result.complete(unanswered) }) { result.complete(ask(file, root)) }
        return result
    }

    /**
     * Runs [action] on the FX thread once the folder wait passed, unless the session closed: when a gating spec's
     * answer counts as not served. Closing the result cancels it.
     */
    fun afterWait(action: () -> Unit): AutoCloseable = scheduler.schedule(resolveWait) { fx(action) }

    /**
     * Adds the folder [folder] resolves to (waiting for it) on [server] unless it is known: `didChangeWorkspaceFolders`,
     * right before the document's `didOpen`. A folder outside the window folder (one resolved for the window folder
     * before it changed) is not added; the window folder serves that document. An answer that takes longer than
     * [resolveWait] leaves the document to the window folder; when it comes, the document is opened again under it.
     * Outbound thread.
     */
    fun announce(
        folder: CompletableFuture<Resolution>,
        server: LanguageServer,
    ) {
        val path = await(folder)?.folder ?: return
        synchronized(lock) {
            val window = known.firstOrNull()
            if (closed || window == null || !path.startsWith(window) || path in known) return
            // listOf: a Path is an Iterable of its names, `known + path` would add those.
            known = known + listOf(path)
        }
        server.workspaceService.didChangeWorkspaceFolders(change(added = path))
        watchLater(path)
    }

    /**
     * Changes on disk ([dev.sluide.languageservers.LanguageServers.filesChanged], unfiltered): those the spec names ask
     * the folders of the open documents below their parent again, after [debounce]. FX thread.
     */
    fun filesChanged(changes: List<FileChange>) {
        if (closed) return
        for (change in changes) {
            if (!affects(change.path)) continue
            val parent =
                change.path
                    .toAbsolutePath()
                    .normalize()
                    .parent ?: continue
            if (host.documents().none { it.file.startsWith(parent) }) continue
            debouncer.trigger(Pending.Resolve(parent)) {
                fx { reResolve(host.documents().filter { it.file.startsWith(parent) }) }
            }
        }
    }

    /** Ends the watches and waits; the set is empty. */
    fun close() {
        closed = true
        debouncer.close()
        watches.closeAll(forGood = true)
        gates.close()
        synchronized(lock) { known = emptyList() }
    }

    /**
     * Removes [folder] from the server and adds it again, then re-opens the documents it serves (ty forgets them with
     * the folder) and asks the spec again what to watch for it. FX thread.
     */
    internal fun refresh(folder: Path) {
        val window = known.firstOrNull() ?: return
        if (folder !in known) return
        reAdd(folder)
        host.documents().filter { servedBy(it, folder, window) }.forEach(WorkspaceDocument::reopenOnServer)
        host.changed()
    }

    /**
     * Asks the folders (and gates) of the open [documents] again, and for the folders that serve them (before and
     * after) what to watch, on the worker; then [reResolved] applies what changed. The folders their gates name are
     * watched again (a watch that ended with its folder is renewed).
     */
    private fun reResolve(documents: List<WorkspaceDocument>) {
        val root = window()
        if (documents.isEmpty() || root == null) return
        val before = documents.map { it.resolution }
        val files = documents.map { it.file }
        onWorker {
            val answers = files.map { ask(it, root) }
            gates.renew(answers.flatMap { it.gate.watched }.toSet())
            val served =
                (before.map { it.getNow(null)?.folder } + answers.map { it.folder }).map { it ?: root }.distinct()
            val rewatch = served.filter { it in known && watchesChanged(it) }
            fx { reResolved(documents.indices.map { Answer(documents[it], before[it], answers[it]) }, rewatch, root) }
        }
    }

    /**
     * Applies a re-resolution: folders no open document resolves to any more are removed, folders whose watched
     * folders changed are refreshed, and documents whose folder changed, that a removed folder served or that a
     * refreshed folder serves are opened again, each once and after those notifications. A document whose gate
     * flipped was opened or closed by its new answer and is not opened again. FX thread.
     */
    private fun reResolved(
        answers: List<Answer>,
        rewatch: List<Path>,
        root: Path,
    ) {
        // No server ran yet (a gating spec whose documents are not served): nothing is known, the answers still count.
        val window = known.firstOrNull() ?: root
        val open = host.documents()
        val moved = LinkedHashSet<WorkspaceDocument>()
        val synced = HashSet<WorkspaceDocument>()
        val left = LinkedHashSet<Path>()
        for ((document, before, answer) in answers) {
            // Closed, moved or asked again meanwhile: the newer answer counts.
            if (document !in open || document.resolution !== before) continue
            val old = before.getNow(null)
            if (old == answer) continue
            if (document.adopt(CompletableFuture.completedFuture(answer))) synced.add(document)
            val oldFolder = old?.folder
            if ((oldFolder ?: window) != (answer.folder ?: window)) {
                moved.add(document)
                if (oldFolder != null && oldFolder != window) left.add(oldFolder)
            }
        }
        val removed = left.filter { folder -> open.none { resolvesTo(it, folder) } }
        // ty served the documents below a removed folder from it (the innermost folder wins): they go with it.
        val reopen = moved + open.filter { document -> removed.any { innermost(document.file) == it } }
        removed.forEach(::remove)
        val refreshed = rewatch.filter { it !in removed && it in known }
        refreshed.forEach(::reAdd)
        val all = reopen + open.filter { document -> refreshed.any { servedBy(document, it, window) } }
        all.filter { it !in synced }.forEach(WorkspaceDocument::reopenOnServer)
        host.changed()
    }

    /**
     * Removes [folder] from the set, and from the server that runs, and ends its watches; never the window folder.
     * Decided on the outbound thread, in order with the adds before it, and done whatever server runs: one that runs
     * got the folder (in `initialize` or added since, both on that thread), one that starts later does not.
     */
    private fun remove(folder: Path) {
        watches.remove(folder)
        host.onOutbound { server ->
            synchronized(lock) {
                if (folder !in known || folder == known.first()) return@onOutbound
                known = known - setOf(folder)
            }
            // Again: a watch the worker registered while the folder was still known.
            watches.remove(folder)
            server?.workspaceService?.didChangeWorkspaceFolders(change(removed = folder))
        }
    }

    /** Removes [folder] from the server and adds it again, and asks what to watch for it again. */
    private fun reAdd(folder: Path) {
        host.notify { server ->
            if (folder in known) {
                server.workspaceService.didChangeWorkspaceFolders(change(removed = folder))
                server.workspaceService.didChangeWorkspaceFolders(change(added = folder))
            }
        }
        watchLater(folder)
    }

    /** Whether [folder] serves [document]: its folder, or one still being resolved (its didOpen may come first). */
    private fun servedBy(
        document: WorkspaceDocument,
        folder: Path,
        window: Path,
    ): Boolean {
        val resolution = document.resolution
        return !resolution.isDone || (resolution.getNow(null)?.folder ?: window) == folder
    }

    private fun resolvesTo(
        document: WorkspaceDocument,
        folder: Path,
    ): Boolean = !document.resolution.isDone || document.resolution.getNow(null)?.folder == folder

    /** The innermost known folder that holds [file]: the one ty serves it from. */
    private fun innermost(file: Path): Path? = known.filter { file.startsWith(it) }.maxByOrNull { it.nameCount }

    /** Whether the spec names other folders to watch for [folder] than those watched; worker. */
    private fun watchesChanged(folder: Path): Boolean {
        val watched = watches.paths(folder) ?: return false
        return watchedFolders(folder) != watched
    }

    /** The spec's answers for [file]: its folder and, for a gating spec, its gate; worker. */
    private fun ask(
        file: Path,
        root: Path,
    ) = Resolution(askFolder(file, root), if (spec.gatesDocuments) askGate(file, root) else DocumentGate.SERVED)

    private fun askFolder(
        file: Path,
        root: Path,
    ): Path? =
        try {
            spec
                .workspaceFolder(file, root)
                ?.toAbsolutePath()
                ?.normalize()
                ?.takeIf { it.startsWith(root) }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: no workspace folder for $file", e)
            null
        }

    private fun askGate(
        file: Path,
        root: Path,
    ): DocumentGate =
        try {
            spec.gate(file, root).let { gate ->
                gate.copy(watched = gate.watched.map { it.toAbsolutePath().normalize() }.distinct())
            }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: no gate answer for $file; not served", e)
            Resolution.NOT_SERVED
        }

    private fun window(): Path? = projectFolder()?.toAbsolutePath()?.normalize()

    private fun affects(path: Path): Boolean =
        try {
            spec.affectsWorkspaceFolders(path)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: affectsWorkspaceFolders failed for $path", e)
            false
        }

    private fun watchedFolders(folder: Path): List<Path> =
        try {
            spec.watchedFolders(folder).map { it.toAbsolutePath().normalize() }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: no watched folders for $folder", e)
            emptyList()
        }

    /** Asks the spec on the worker which folders to watch for [folder], and watches those while it is known. */
    private fun watchLater(folder: Path) =
        onWorker {
            if (closed || folder !in known) return@onWorker
            watches.update(folder, watchedFolders(folder)) { !closed && folder in known }
        }

    private fun await(folder: CompletableFuture<Resolution>): Resolution? =
        try {
            folder.get(resolveWait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: no workspace folder after $resolveWait")
            if (late.add(folder)) folder.thenAccept { path -> fx { lateAnswer(folder, path) } }
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            // Closing: what is sent now no longer matters.
            Thread.currentThread().interrupt()
            null
        }

    /** A folder [announce] stopped waiting for came: its document, opened under the window folder, opens again. */
    private fun lateAnswer(
        resolution: CompletableFuture<Resolution>,
        answer: Resolution,
    ) {
        late.remove(resolution)
        val path = answer.folder
        if (path == null || path == known.firstOrNull()) return
        host.documents().filter { it.resolution === resolution }.forEach(WorkspaceDocument::reopenOnServer)
        host.changed()
    }

    private fun onWorker(
        rejected: () -> Unit = {},
        task: () -> Unit,
    ) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            rejected()
        }
    }

    private fun fx(action: () -> Unit) = dispatch(Runnable { if (!closed) action() })

    private fun change(
        added: Path? = null,
        removed: Path? = null,
    ) = DidChangeWorkspaceFoldersParams(
        WorkspaceFoldersChangeEvent(
            listOfNotNull(added).map(::workspaceFolder),
            listOfNotNull(removed).map(::workspaceFolder),
        ),
    )

    /** The answer of a re-resolution for [document], whose folder was [before] when it was asked. */
    private data class Answer(
        val document: WorkspaceDocument,
        val before: CompletableFuture<Resolution>,
        val resolution: Resolution,
    )

    private sealed interface Pending {
        data class Refresh(
            val folder: Path,
        ) : Pending

        data class Resolve(
            val parent: Path,
        ) : Pending

        data class Gate(
            val folder: Path,
        ) : Pending
    }
}
