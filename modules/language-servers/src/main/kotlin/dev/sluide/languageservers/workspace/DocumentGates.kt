package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.files.DirectoryWatcher
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What the spec answered for one document: the workspace folder that serves it (`null`: the window folder) and its
 * gate (ADR 0160; [DocumentGate.SERVED] for a spec that does not gate documents).
 */
internal data class Resolution(
    val folder: Path?,
    val gate: DocumentGate,
) {
    companion object {
        /** The gate of a document whose gating spec failed or did not answer in time: not served (fail closed). */
        val NOT_SERVED = DocumentGate(false)
    }
}

/**
 * The watches of the folders the open documents' gate answers name (`DocumentGate.watched`, ADR 0160): one
 * non-recursive [DirectoryWatcher] watch per folder while some open document names it. A report there becomes file
 * changes (created, changed, deleted, from the folder's entries as last listed) and reaches [onChange] with the folder.
 *
 * Which document names which folder is kept on the FX thread ([name], [forget], [naming]); the watches are registered,
 * listed and ended on the session's folder worker ([onWorker], one thread at a time), so that the FX thread does no
 * disk work. [close] may run on any thread.
 */
internal class DocumentGates(
    private val watcher: DirectoryWatcher,
    /** Runs on the folder worker; does nothing once it is shut down. */
    private val onWorker: (() -> Unit) -> Unit,
    /** Called on the folder worker with a watched folder and what changed in it (possibly nothing). */
    private val onChange: (folder: Path, changes: List<FileChange>) -> Unit,
) {
    /** A watched folder: its watch and the names of its entries as last listed. */
    private class Watch(
        val handle: AutoCloseable,
        var entries: Set<String>,
    )

    /** The folders each open document's answer names; FX thread. */
    private val named = HashMap<WorkspaceDocument, List<Path>>()

    /** The folders some open document names; written on the FX thread, read on the worker. */
    private val wanted = ConcurrentHashMap.newKeySet<Path>()

    private val lock = Any()
    private val watches = HashMap<Path, Watch>()
    private var closed = false

    /** [document]'s answer names [folders] now (absolute, normalized): watches what is new, ends what nobody names. */
    fun name(
        document: WorkspaceDocument,
        folders: List<Path>,
    ) {
        val before = named[document].orEmpty()
        if (folders.isEmpty()) named.remove(document) else named[document] = folders
        update(before, folders)
    }

    /** [document] closed (or asks again after a move): the folders only it named are no longer watched. */
    fun forget(document: WorkspaceDocument) = update(named.remove(document).orEmpty(), emptyList())

    /** The open documents whose answer names [folder]; FX thread. */
    fun naming(folder: Path): List<WorkspaceDocument> = named.filterValues { folder in it }.keys.toList()

    /** Watches [folders] again if still named, renewing a watch that ended with its folder; folder worker. */
    fun renew(folders: Collection<Path>) = folders.filter { it in wanted }.forEach(::watchNow)

    /** Ends every watch for good: none is registered any more. Any thread. */
    fun close() {
        val old =
            synchronized(lock) {
                closed = true
                watches.values.map { it.handle }.also { watches.clear() }
            }
        old.forEach(AutoCloseable::close)
    }

    private fun update(
        before: List<Path>,
        after: List<Path>,
    ) {
        val stillNamed = named.values.flatten().toSet()
        val gone = before.filter { it !in stillNamed }
        val new = after.filter { wanted.add(it) }
        wanted.removeAll(gone.toSet())
        if (gone.isNotEmpty()) onWorker { gone.filter { it !in wanted }.forEach(::unwatch) }
        if (new.isNotEmpty()) onWorker { new.filter { it in wanted }.forEach(::watchNow) }
    }

    /** Watches [folder] (again: an ended watch is renewed), then lists it; folder worker. */
    private fun watchNow(folder: Path) {
        val old =
            synchronized(lock) {
                if (closed || folder !in wanted) return
                val handle = watcher.watch(folder) { paths -> onWorker { reported(folder, paths) } }
                watches.put(folder, Watch(handle, emptySet()))
            }
        old?.handle?.close()
        val entries = list(folder)
        synchronized(lock) { watches[folder]?.entries = old?.entries ?: entries }
        // A folder renewed after it went and came back: what changed meanwhile is reported now.
        if (old != null && old.entries != entries) reported(folder, listOf(folder))
    }

    private fun unwatch(folder: Path) {
        val old = synchronized(lock) { watches.remove(folder) }
        old?.handle?.close()
    }

    /** Turns what the watch of [folder] reported into file changes, against the entries as last listed; worker. */
    private fun reported(
        folder: Path,
        paths: List<Path>,
    ) {
        val changes = mutableListOf<FileChange>()
        for (path in paths.map { it.toAbsolutePath().normalize() }) {
            val watch = synchronized(lock) { watches[folder] } ?: return
            if (path == folder) {
                // The folder itself: deleted, or events were lost. Its entries are compared whole.
                val now = list(folder)
                val before = watch.entries
                (before - now).forEach { changes += FileChange(folder.resolve(it), FileChangeKind.DELETED) }
                (now - before).forEach { changes += FileChange(folder.resolve(it), FileChangeKind.CREATED) }
                synchronized(lock) { watch.entries = now }
                continue
            }
            if (path.parent != folder) continue
            val name = path.fileName.toString()
            val known = name in watch.entries
            val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            val kind =
                when {
                    exists && known -> FileChangeKind.CHANGED
                    exists -> FileChangeKind.CREATED
                    known -> FileChangeKind.DELETED
                    else -> null
                } ?: continue
            synchronized(lock) { watch.entries = if (exists) watch.entries + name else watch.entries - name }
            changes += FileChange(path, kind)
        }
        onChange(folder, changes)
    }

    /** The names of [folder]'s entries; none if it is no folder (yet) or cannot be listed. */
    private fun list(folder: Path): Set<String> =
        try {
            if (!Files.isDirectory(folder)) {
                emptySet()
            } else {
                Files.newDirectoryStream(folder).use { stream -> stream.map { it.fileName.toString() }.toSet() }
            }
        } catch (_: IOException) {
            emptySet()
        }
}
