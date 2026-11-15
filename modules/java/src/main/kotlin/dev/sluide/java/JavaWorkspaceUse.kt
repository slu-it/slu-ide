package dev.sluide.java

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The jdtls workspace folders a window's Java session uses right now (ADR 0179): the session acquires its folder while
 * it lives, and the settings page does not delete a workspace in [inUse]. [acquire] and its close may run on any
 * thread; [inUse] changes on the FX thread ([dispatch] runs a change there).
 */
@Component
class JavaWorkspaceUse internal constructor(
    private val dispatch: (Runnable) -> Unit,
) {
    @Autowired
    constructor() : this({ if (Platform.isFxApplicationThread()) it.run() else Platform.runLater(it) })

    private val folders: ObservableSet<Path> = FXCollections.observableSet()
    private val counts = HashMap<Path, Int>()

    /** The folders in use; FX thread. */
    val inUse: ObservableSet<Path> = FXCollections.unmodifiableObservableSet(folders)

    /** Marks [workspaceFolder] as in use until the result is closed; counted, so two acquires need two closes. */
    fun acquire(workspaceFolder: Path): AutoCloseable {
        val folder = workspaceFolder.toAbsolutePath().normalize()
        change(folder, 1)
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) change(folder, -1) }
    }

    /** Whether [workspaceFolder] is held right now; any thread (the set [inUse] is the FX thread's view). */
    fun isInUse(workspaceFolder: Path): Boolean =
        synchronized(counts) { workspaceFolder.toAbsolutePath().normalize() in counts }

    private fun change(
        folder: Path,
        by: Int,
    ) {
        synchronized(counts) { counts.merge(folder, by) { a, b -> (a + b).takeIf { it > 0 } } }
        // The set follows the count as it is when the change runs, so changes dispatched in any order end right.
        dispatch(
            Runnable {
                val used = synchronized(counts) { folder in counts }
                if (used) folders.add(folder) else folders.remove(folder)
            },
        )
    }
}
