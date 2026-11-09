package dev.sluide.languageservers.files

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Records watches; [report] calls a folder's listener as `FileWatcher` would. Any thread. */
internal class FakeDirectoryWatcher : DirectoryWatcher {
    /** The live watch of each folder; watching it again replaces it, closing an older watch keeps the newer one. */
    val listeners: MutableMap<Path, (List<Path>) -> Unit> = ConcurrentHashMap()

    /** Every folder watched, in order, also again. */
    val watches = CopyOnWriteArrayList<Path>()

    override fun watch(
        directory: Path,
        listener: (List<Path>) -> Unit,
    ): AutoCloseable {
        listeners[directory] = listener
        watches.add(directory)
        return AutoCloseable { listeners.remove(directory, listener) }
    }

    fun report(
        directory: Path,
        vararg paths: Path,
    ) = listeners.getValue(directory)(paths.toList())
}
