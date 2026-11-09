package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.files.DirectoryWatcher
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The watches of the folders the spec names per workspace folder (`LanguageServerSpec.watchedFolders`), one
 * non-recursive [DirectoryWatcher] watch each: any report there calls [onChange] with the workspace folder. Any thread.
 */
internal class FolderWatches(
    private val watcher: DirectoryWatcher,
    private val onChange: (workspaceFolder: Path) -> Unit,
) {
    /** The folders watched for one workspace folder, as the spec named them, and their watches. */
    private class Watched(
        val folders: List<Path>,
        val watches: List<AutoCloseable>,
    )

    private val lock = Any()
    private val watched = HashMap<Path, Watched>()
    private var closed = false

    /**
     * Watches [folders] for [workspaceFolder] instead of what it watched before, if [wanted] still holds (checked with
     * the watches, so that a reset or removal in between wins). Every folder is watched again, so that one that did
     * not exist before (a venv that appeared) or whose watch ended with it gets a live watch; the old watches close
     * afterwards.
     */
    fun update(
        workspaceFolder: Path,
        folders: List<Path>,
        wanted: () -> Boolean,
    ) {
        val old =
            synchronized(lock) {
                if (closed || !wanted()) return
                val new = folders.map { folder -> watcher.watch(folder) { onChange(workspaceFolder) } }
                watched.put(workspaceFolder, Watched(folders, new))
            }
        old?.watches?.forEach(AutoCloseable::close)
    }

    /** The folders watched for [workspaceFolder] as last named, or `null` if it was not watched (yet). */
    fun paths(workspaceFolder: Path): List<Path>? = synchronized(lock) { watched[workspaceFolder]?.folders }

    /** Ends the watches of [workspaceFolder]. */
    fun remove(workspaceFolder: Path) {
        val old = synchronized(lock) { watched.remove(workspaceFolder) }
        old?.watches?.forEach(AutoCloseable::close)
    }

    /** Ends every watch; with [forGood], later updates watch nothing. */
    fun closeAll(forGood: Boolean = false) {
        val old =
            synchronized(lock) {
                if (forGood) closed = true
                watched.values.flatMap { it.watches }.also { watched.clear() }
            }
        old.forEach(AutoCloseable::close)
    }
}

/**
 * Runs an action once [delay] passed without another [trigger] of the same key: a burst of changes (`uv sync`) gives
 * one refresh. Any thread; the action runs on the [scheduler]'s thread.
 */
internal class Debouncer<K : Any>(
    private val scheduler: Scheduler,
    private val delay: Duration,
) {
    private val lock = Any()
    private val pending = HashMap<K, Scheduled>()
    private var closed = false

    /** (Re)starts the wait of [key]; [action] replaces the one triggered before. */
    fun trigger(
        key: K,
        action: () -> Unit,
    ) {
        val token = Any()
        val previous =
            synchronized(lock) {
                if (closed) return
                val task = scheduler.schedule(delay) { if (due(key, token)) action() }
                pending.put(key, Scheduled(token, task))
            }
        previous?.close()
    }

    /** Cancels everything waiting; later triggers do nothing. */
    fun close() {
        val waiting =
            synchronized(lock) {
                closed = true
                pending.values.toList().also { pending.clear() }
            }
        waiting.forEach(AutoCloseable::close)
    }

    private fun due(
        key: K,
        token: Any,
    ): Boolean =
        synchronized(lock) {
            if (closed || pending[key]?.token !== token) return false
            pending.remove(key)
            true
        }

    private class Scheduled(
        val token: Any,
        private val task: AutoCloseable,
    ) : AutoCloseable by task
}
