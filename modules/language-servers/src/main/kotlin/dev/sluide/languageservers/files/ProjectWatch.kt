package dev.sluide.languageservers.files

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.ProjectWatchOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executor

private val log = System.getLogger(ProjectWatch::class.java.name)

/**
 * Watches one folder without its subfolders (`FileWatcher.watch`): the changed entries, or the folder itself when it
 * was deleted or events were lost. Watching a folder again keeps a live watch and renews an ended one.
 */
internal fun interface DirectoryWatcher {
    fun watch(
        directory: Path,
        listener: (List<Path>) -> Unit,
    ): AutoCloseable
}

/**
 * Watches the project folder [root] with its subfolders for the language servers (ADR 0142): one [DirectoryWatcher]
 * watch per folder, except hidden and excluded folders, links and past [ProjectWatchOptions.maxDirectories]. Folders
 * that appear are watched too. A reported path is turned into [FileChange]s by listing the folder that reported it
 * again and comparing with what was there (existence, size, modification time); the changes found in one go reach
 * [onChanges] together through [dispatch]. Paths stay below [root] as given, the form the servers got as root URI.
 *
 * Disk work runs on [worker] (one thread at a time: the state below is confined to it); watch events may arrive on any
 * thread.
 */
internal class ProjectWatch(
    root: Path,
    private val watcher: DirectoryWatcher,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val options: ProjectWatchOptions,
    private val onChanges: (List<FileChange>) -> Unit,
) : AutoCloseable {
    private data class Entry(
        val isDirectory: Boolean,
        val size: Long,
        val modified: FileTime,
    )

    /** A watched folder: its watch and its entries as last listed. */
    private class Folder(
        var watch: AutoCloseable,
        var entries: Map<String, Entry>,
    )

    /** A path a watched folder reported: an entry of it, or the folder itself. */
    private data class Report(
        val folder: Path,
        val path: Path,
    )

    private val root = root.toAbsolutePath().normalize()
    private val folders = HashMap<Path, Folder>()
    private var capLogged = false

    private val lock = Any()
    private val reported = LinkedHashSet<Report>()
    private var drainQueued = false

    @Volatile
    private var closed = false

    /** Starts watching: lists the tree on the worker. */
    fun start() = worker.execute { if (!closed) addTree(root, null) }

    override fun close() {
        closed = true
        worker.execute {
            folders.values.forEach { it.watch.close() }
            folders.clear()
        }
    }

    /** The folders watched now; read on the worker (tests run it inline). */
    internal fun watchedFolders(): Set<Path> = folders.keys.toSet()

    private fun watch(folder: Path) = watcher.watch(folder) { paths -> onReported(folder, paths) }

    private fun onReported(
        folder: Path,
        paths: List<Path>,
    ) {
        val queue =
            synchronized(lock) {
                paths.forEach { reported.add(Report(folder, it.toAbsolutePath().normalize())) }
                !drainQueued.also { drainQueued = true }
            }
        if (queue) worker.execute(::drain)
    }

    private fun drain() {
        val reports =
            synchronized(lock) {
                drainQueued = false
                reported.toList().also { reported.clear() }
            }
        if (closed) return
        val rescans = LinkedHashMap<Path, Boolean>()
        for ((folder, path) in reports) {
            val itself = path == folder
            rescans[folder] = rescans[folder] == true || itself
        }
        val changes = mutableListOf<FileChange>()
        rescans.forEach { (folder, itself) -> rescan(folder, renew = itself, changes) }
        if (changes.isNotEmpty()) dispatch(Runnable { if (!closed) onChanges(changes) })
    }

    /** Watches [start] and the folders below it; their entries count as created if [changes] is given. */
    private fun addTree(
        start: Path,
        changes: MutableList<FileChange>?,
    ) {
        val pending = ArrayDeque(listOf(start))
        while (pending.isNotEmpty() && !closed) {
            val next = pending.removeFirst()
            if (next in folders) continue
            if (folders.size >= options.maxDirectories) {
                if (!capLogged) {
                    capLogged = true
                    log.log(
                        System.Logger.Level.INFO,
                        "lsp file watch: ${options.maxDirectories} folders watched in $root, not watching more",
                    )
                }
                return
            }
            // Watched before it is listed, so that nothing created in between is missed.
            val watch = watch(next)
            val entries = list(next).orEmpty()
            folders[next] = Folder(watch, entries)
            for ((name, entry) in entries) {
                val child = next.resolve(name)
                changes?.add(FileChange(child, FileChangeKind.CREATED))
                if (entry.isDirectory && watchable(next, name)) pending.addLast(child)
            }
        }
    }

    /**
     * Lists the watched [path] again and reports the differences. A folder that reported itself ([renew]) was deleted
     * or lost events: its watch may have ended with the deleted folder even if one of the same name is back (Linux
     * reuses inode numbers, so nothing on disk tells), so it is watched again before the old watch is closed.
     */
    private fun rescan(
        path: Path,
        renew: Boolean,
        changes: MutableList<FileChange>,
    ) {
        val folder = folders[path] ?: return
        val now = list(path)
        if (now == null) {
            // Gone: its parent's listing reports the folder itself.
            removeTree(path, changes)
            return
        }
        if (renew) {
            val old = folder.watch
            folder.watch = watch(path)
            old.close()
        }
        val before = folder.entries
        folder.entries = now
        for ((name, entry) in now) {
            val child = path.resolve(name)
            val old = before[name]
            when {
                old == null -> {
                    changes += FileChange(child, FileChangeKind.CREATED)
                    if (entry.isDirectory && watchable(path, name)) addTree(child, changes)
                }

                old.isDirectory != entry.isDirectory -> {
                    if (old.isDirectory) removeTree(child, changes)
                    changes += FileChange(child, FileChangeKind.DELETED)
                    changes += FileChange(child, FileChangeKind.CREATED)
                    if (entry.isDirectory && watchable(path, name)) addTree(child, changes)
                }

                !entry.isDirectory && (old.size != entry.size || old.modified != entry.modified) -> {
                    changes += FileChange(child, FileChangeKind.CHANGED)
                }
            }
        }
        for ((name, old) in before) {
            if (name in now) continue
            val child = path.resolve(name)
            if (old.isDirectory) removeTree(child, changes)
            changes += FileChange(child, FileChangeKind.DELETED)
        }
    }

    /** Forgets the watched folder [path] and those below it; the entries known in them count as deleted. */
    private fun removeTree(
        path: Path,
        changes: MutableList<FileChange>,
    ) {
        val folder = folders.remove(path) ?: return
        folder.watch.close()
        for ((name, entry) in folder.entries) {
            val child = path.resolve(name)
            if (entry.isDirectory) removeTree(child, changes)
            changes += FileChange(child, FileChangeKind.DELETED)
        }
    }

    private fun watchable(
        parent: Path,
        name: String,
    ) = !name.startsWith(".") &&
        name !in options.excludedDirectories &&
        !(parent == root && name in options.excludedTopLevelDirectories)

    /**
     * The entries of [folder], or `null` if it is no folder (any more). Links are not followed, so that a link to a
     * folder above cannot make the watch loop; only the project folder itself may be opened through a link.
     */
    private fun list(folder: Path): Map<String, Entry>? {
        val isFolder =
            if (folder == root) Files.isDirectory(folder) else Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
        if (!isFolder) return null
        return try {
            Files.newDirectoryStream(folder).use { stream ->
                stream.mapNotNull { child -> entry(child)?.let { child.fileName.toString() to it } }.toMap()
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "lsp file watch: cannot list $folder: $e")
            null
        }
    }

    private fun entry(path: Path): Entry? =
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            Entry(attributes.isDirectory, attributes.size(), attributes.lastModifiedTime())
        } catch (_: IOException) {
            // Deleted meanwhile.
            null
        }
}
