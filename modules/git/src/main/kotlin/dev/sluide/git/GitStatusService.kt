package dev.sluide.git

import dev.sluide.filewatcher.FileWatcher
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The git status of the open folder (ADR 0013). It is only followed if git is available and the folder itself is a
 * repository root (has `.git`); otherwise [snapshot] stays [GitStatusSnapshot.EMPTY].
 *
 * `git status` runs on a background thread after [open], after every [refresh] and whenever the `.git` folder changes
 * (commits, staging, checkouts). [open], [refresh] and listeners of [snapshot] use the JavaFX thread, or whatever
 * [dispatch] stands for in tests.
 */
@Component
class GitStatusService(
    private val git: GitCli,
    private val fileWatcher: FileWatcher,
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor(git: GitCli, fileWatcher: FileWatcher) : this(git, fileWatcher, Platform::runLater)

    private class Target(
        val root: Path,
    )

    private val _snapshot = ReadOnlyObjectWrapper(GitStatusSnapshot.EMPTY)

    /** The latest status of the open repository; replaced only when it changed. */
    val snapshot: ReadOnlyObjectProperty<GitStatusSnapshot> = _snapshot.readOnlyProperty

    private val executor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git").apply { isDaemon = true } }

    /** The repository being followed; a new object for every [open], so results for an older one are dropped. */
    @Volatile private var target: Target? = null
    private val scheduled = AtomicBoolean()
    private var gitWatch: AutoCloseable? = null

    /** Whether the open folder is a git repository that is followed. */
    val active: Boolean get() = target != null

    /** Follows the repository at [folder], if git is available and it has `.git`; `null` follows nothing. */
    fun open(folder: Path?) {
        gitWatch?.close()
        gitWatch = null
        val root = folder?.toAbsolutePath()?.normalize()?.takeIf { git.available && Files.exists(it.resolve(".git")) }
        target = root?.let(::Target)
        _snapshot.set(GitStatusSnapshot.EMPTY)
        if (root == null) return
        watchGitFolder(root)
        refresh()
    }

    /** Runs `git status` again soon; calls during a run are combined into one more run. */
    fun refresh() {
        if (target == null || !scheduled.compareAndSet(false, true)) return
        executor.execute {
            scheduled.set(false)
            val current = target ?: return@execute
            val output = git.status(current.root) ?: return@execute
            val snapshot = GitStatusSnapshot.parse(current.root, output)
            dispatch(
                Runnable {
                    if (target === current && snapshot != _snapshot.get()) _snapshot.set(snapshot)
                },
            )
        }
    }

    private fun watchGitFolder(root: Path) {
        val gitFolder = root.resolve(".git")
        // A .git file (worktrees, submodules) cannot be watched; the handle then does nothing.
        gitWatch =
            fileWatcher.watch(gitFolder) { changed ->
                if (target?.root != root) return@watch
                // Reported itself when events were lost or it was deleted: watch it anew if it is there.
                if (gitFolder in changed && Files.isDirectory(gitFolder)) {
                    gitWatch?.close()
                    watchGitFolder(root)
                }
                refresh()
            }
    }

    override fun close() {
        gitWatch?.close()
        target = null
        executor.shutdownNow()
    }
}
