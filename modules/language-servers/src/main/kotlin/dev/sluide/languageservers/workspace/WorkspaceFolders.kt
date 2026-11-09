package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.workspaceFolder
import dev.sluide.languageservers.files.DirectoryWatcher
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
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

    /** The resolution of its workspace folder (`null`: the window folder), the latest one. */
    val folder: CompletableFuture<Path?>

    /** Takes [resolution] as its folder; the value of `workspaceFolderOf` follows. */
    fun adopt(resolution: CompletableFuture<Path?>)

    /** Opens the document on the server again: `didClose`, then `didOpen` with its current text, under [folder]. */
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
 */
internal class WorkspaceFolders(
    private val spec: LanguageServerSpec,
    private val projectFolder: () -> Path?,
    private val worker: Executor,
    /** Runs on the FX thread unless the session closed. */
    private val dispatch: (Runnable) -> Unit,
    scheduler: Scheduler,
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

    /** Asks the spec on the worker which folder serves [file]; FX thread. Never fails: a failure is the window folder. */
    fun resolve(file: Path): CompletableFuture<Path?> {
        val result = CompletableFuture<Path?>()
        val root = window() ?: return result.also { it.complete(null) }
        onWorker({ result.complete(null) }) { result.complete(ask(file, root)) }
        return result
    }

    /**
     * Adds the folder [folder] resolves to (waiting for it) on [server] unless it is known: `didChangeWorkspaceFolders`,
     * right before the document's `didOpen`. A folder outside the window folder (one resolved for the window folder
     * before it changed) is not added; the window folder serves that document. An answer that takes longer than
     * [resolveWait] leaves the document to the window folder; when it comes, the document is opened again under it.
     * Outbound thread.
     */
    fun announce(
        folder: CompletableFuture<Path?>,
        server: LanguageServer,
    ) {
        val path = await(folder) ?: return
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
            debouncer.trigger(Pending.Resolve(parent)) { fx { reResolve(parent) } }
        }
    }

    /** Ends the watches and waits; the set is empty. */
    fun close() {
        closed = true
        debouncer.close()
        watches.closeAll(forGood = true)
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
     * Asks the folders of the open documents below [parent] again, and for the folders that serve them (before and
     * after) what to watch, on the worker; then [reResolved] applies what changed.
     */
    private fun reResolve(parent: Path) {
        val documents = host.documents().filter { it.file.startsWith(parent) }
        val root = window()
        if (documents.isEmpty() || root == null) return
        val before = documents.map { it.folder }
        val files = documents.map { it.file }
        onWorker {
            val answers = files.map { ask(it, root) }
            val served = (before.map { it.getNow(null) } + answers).map { it ?: root }.distinct()
            val rewatch = served.filter { it in known && watchesChanged(it) }
            fx { reResolved(documents.indices.map { Answer(documents[it], before[it], answers[it]) }, rewatch) }
        }
    }

    /**
     * Applies a re-resolution: folders no open document resolves to any more are removed, folders whose watched
     * folders changed are refreshed, and documents whose folder changed, that a removed folder served or that a
     * refreshed folder serves are opened again, each once and after those notifications. FX thread.
     */
    private fun reResolved(
        answers: List<Answer>,
        rewatch: List<Path>,
    ) {
        val window = known.firstOrNull() ?: return
        val open = host.documents()
        val moved = LinkedHashSet<WorkspaceDocument>()
        val left = LinkedHashSet<Path>()
        for ((document, before, answer) in answers) {
            // Closed, moved or asked again meanwhile: the newer answer counts.
            if (document !in open || document.folder !== before) continue
            val old = before.getNow(null)
            if (old == answer) continue
            document.adopt(CompletableFuture.completedFuture(answer))
            if ((old ?: window) != (answer ?: window)) {
                moved.add(document)
                if (old != null && old != window) left.add(old)
            }
        }
        val removed = left.filter { folder -> open.none { resolvesTo(it, folder) } }
        // ty served the documents below a removed folder from it (the innermost folder wins): they go with it.
        val reopen = moved + open.filter { document -> removed.any { innermost(document.file) == it } }
        removed.forEach(::remove)
        val refreshed = rewatch.filter { it !in removed && it in known }
        refreshed.forEach(::reAdd)
        val all = reopen + open.filter { document -> refreshed.any { servedBy(document, it, window) } }
        all.forEach(WorkspaceDocument::reopenOnServer)
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
        val resolution = document.folder
        return !resolution.isDone || (resolution.getNow(null) ?: window) == folder
    }

    private fun resolvesTo(
        document: WorkspaceDocument,
        folder: Path,
    ): Boolean = !document.folder.isDone || document.folder.getNow(null) == folder

    /** The innermost known folder that holds [file]: the one ty serves it from. */
    private fun innermost(file: Path): Path? = known.filter { file.startsWith(it) }.maxByOrNull { it.nameCount }

    /** Whether the spec names other folders to watch for [folder] than those watched; worker. */
    private fun watchesChanged(folder: Path): Boolean {
        val watched = watches.paths(folder) ?: return false
        return watchedFolders(folder) != watched
    }

    private fun ask(
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

    private fun await(folder: CompletableFuture<Path?>): Path? =
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
        resolution: CompletableFuture<Path?>,
        path: Path?,
    ) {
        late.remove(resolution)
        if (path == null || path == known.firstOrNull()) return
        host.documents().filter { it.folder === resolution }.forEach(WorkspaceDocument::reopenOnServer)
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
        val before: CompletableFuture<Path?>,
        val folder: Path?,
    )

    private sealed interface Pending {
        data class Refresh(
            val folder: Path,
        ) : Pending

        data class Resolve(
            val parent: Path,
        ) : Pending
    }
}

/** An [Observable] that tells its invalidation listeners each time it [fire]s; FX thread. */
internal class Signal : Observable {
    private val listeners = CopyOnWriteArrayList<InvalidationListener>()

    override fun addListener(listener: InvalidationListener) {
        listeners += listener
    }

    override fun removeListener(listener: InvalidationListener) {
        listeners -= listener
    }

    /** Calls every listener; one that throws is logged and the others still run. */
    fun fire() =
        listeners.forEach { listener ->
            try {
                listener.invalidated(this)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "lsp: a workspace listener failed", e)
            }
        }
}
