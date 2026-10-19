package dev.sluide.git

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.diffToHead
import dev.sluide.git.cli.headContent
import dev.sluide.git.cli.status
import dev.sluide.git.parse.GitDiffParser
import dev.sluide.projecttrust.ProjectTrust
import javafx.application.Platform
import javafx.beans.property.ReadOnlyLongProperty
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = System.getLogger(GitStatusService::class.java.name)

/** Where the lines of [GitStatusService] go; replaced in tests. */
internal object GitStatusLog {
    @Volatile
    var sink: (String) -> Unit = { log.log(System.Logger.Level.INFO, it) }

    /** Logging must never break the status: whatever the sink throws is dropped. */
    fun info(message: String) {
        try {
            sink(message)
        } catch (_: RuntimeException) {
            // Nothing sensible left to do for a failing log.
        }
    }
}

/**
 * The git status of the open folder (ADR 0013). It is only followed if git is available and the folder itself is a
 * repository root (has `.git`); otherwise [snapshot] stays [GitStatusSnapshot.EMPTY].
 *
 * `git status` runs on a background thread after [open], after every [refresh] and whenever the `.git` folder changes
 * (commits, staging, checkouts). [open], [refresh] and listeners of [snapshot] use the JavaFX thread, or whatever
 * [dispatch] stands for in tests.
 *
 * Git runs only while [trust] allows it (ADR 0053): first after the folder's checks, and on every change in `.git`
 * after git's check ran again, since a checkout or a command in a terminal may have changed what git would run.
 * Otherwise the snapshot is [GitStatusSnapshot.EMPTY]; once the folder is trusted, the window calls [refresh].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GitStatusService(
    private val git: GitCli,
    private val fileWatcher: FileWatcher,
    private val trust: ProjectTrust,
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor(git: GitCli, fileWatcher: FileWatcher, trust: ProjectTrust) :
        this(git, fileWatcher, trust, Platform::runLater)

    private class Target(
        val root: Path,
    )

    private val _snapshot = ReadOnlyObjectWrapper(GitStatusSnapshot.EMPTY)

    /** The latest status of the open repository; replaced only when it changed. */
    val snapshot: ReadOnlyObjectProperty<GitStatusSnapshot> = _snapshot.readOnlyProperty

    private val executor =
        NamedThreads.single("sluide-git")

    /** The repository being followed; a new object for every [open], so results for an older one are dropped. */
    @Volatile private var target: Target? = null
    private val scheduled = AtomicBoolean()

    /** Counts the changes of `.git` noticed by the watch or told by [gitChanged]. */
    private val gitChanges = AtomicLong()

    /**
     * The count of [gitChanges] that git's trust check ran again for and allowed git after; while it is behind, git's
     * configuration may have changed, so [diff] runs no git and the next run checks first (ADR 0053).
     */
    @Volatile private var confirmedChanges = 0L

    /** The count of [gitChanges] git's trust check last ran again for, allowing git or not: not again until more. */
    @Volatile private var checkedChanges = 0L
    private var gitWatch: AutoCloseable? = null

    /** Whether the open folder is a git repository that is followed. */
    val active: Boolean get() = target != null

    /** The root of the repository that is followed (the opened folder, absolute and normalized); `null` if none. */
    val root: Path? get() = target?.root

    private val _gitFolderChanged = ReadOnlyLongWrapper()

    /**
     * Counts the changes noticed in the `.git` folder of the followed repository, for listeners that want to know when
     * git's state may have changed (e.g. the git log, ADR 0035). Changes on the JavaFX thread, once git's trust check
     * has run again and allows git (ADR 0053), so that listeners never run git with what a change made it run.
     *
     * Noticed are changes of entries directly in `.git`: `HEAD`, `index`, `packed-refs`, `FETCH_HEAD`, `ORIG_HEAD`, …,
     * so commits, checkouts, staging, fetches, merges and rebases. Not noticed are changes only in its subfolders, e.g.
     * a new branch or tag from `git branch x` or `git tag x` (`.git/refs/`), nor anything in a repository with a `.git`
     * file (worktrees, submodules), which is not watched.
     */
    val gitFolderChanged: ReadOnlyLongProperty = _gitFolderChanged.readOnlyProperty

    /** Follows the repository at [folder], if git is available and it has `.git`; `null` follows nothing. */
    fun open(folder: Path?) {
        gitWatch?.close()
        gitWatch = null
        val root = folder?.toAbsolutePath()?.normalize()?.takeIf { git.available && Files.exists(it.resolve(".git")) }
        target = root?.let(::Target)
        confirmedChanges = gitChanges.get()
        checkedChanges = confirmedChanges
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
            val cycleStart = System.nanoTime()
            GitStatusLog.info("Git status cycle for ${current.root} started")
            val changes = gitChanges.get()
            val changed = changes != checkedChanges
            val allowed = if (changed) trust.recheck(GitTrustCheck.ID) else trust.awaitAllows(GitTrustCheck.ID)
            val trustMillis = millisSince(cycleStart)
            val trustText = if (changed) "trust recheck $trustMillis ms" else "trust awaited $trustMillis ms"
            if (changed) checkedChanges = changes
            if (!allowed) {
                GitStatusLog.info("Git status cycle for ${current.root}: $trustText, git not allowed")
                dispatch(Runnable { if (target === current) _snapshot.set(GitStatusSnapshot.EMPTY) })
                return@execute
            }
            if (changes != confirmedChanges) {
                confirmedChanges = changes
                val counted = Runnable { if (target === current) _gitFolderChanged.set(_gitFolderChanged.get() + 1) }
                dispatch(counted)
            }
            val statusStart = System.nanoTime()
            val output = git.status(current.root)
            val statusMillis = millisSince(statusStart)
            if (output == null) {
                GitStatusLog.info(
                    "Git status cycle for ${current.root}: $trustText, status failed after $statusMillis ms",
                )
                return@execute
            }
            val snapshot = GitStatusSnapshot.parse(current.root, output)
            val cycle = "Git status cycle for ${current.root}: $trustText, status $statusMillis ms, "
            dispatch(
                Runnable {
                    // The snapshot property is read on the JavaFX thread only.
                    if (target !== current) {
                        GitStatusLog.info(cycle + "dropped, another folder is followed")
                        return@Runnable
                    }
                    val different = snapshot != _snapshot.get()
                    GitStatusLog.info(cycle + if (different) "changed" else "unchanged")
                    if (different) _snapshot.set(snapshot)
                },
            )
        }
    }

    /**
     * Tells that git's state changed through the window itself, e.g. a write of the git log, of which the watch of
     * `.git` may miss a part (new branches): like a change noticed in `.git`, git's trust check runs again, then
     * [gitFolderChanged] counts it if git is still allowed.
     */
    fun gitChanged() {
        gitChanges.incrementAndGet()
        refresh()
    }

    /**
     * How [file] differs from its version at `HEAD` (ADR 0015), or `null` if no repository is followed, the folder's
     * trust keeps git off (also while it is checked again after a change of `.git`), the file is not in `HEAD`, or git
     * fails. Runs git on the calling thread (two quick calls for one file).
     */
    fun diff(file: Path): GitDiff? {
        val root = target?.root ?: return null
        if (gitChanges.get() != confirmedChanges || !trust.allows(GitTrustCheck.ID)) return null
        val path = file.toAbsolutePath().normalize()
        val original = git.headContent(root, path) ?: return null
        val output = git.diffToHead(root, path) ?: return null
        return GitDiff(original, GitDiffParser.parse(output))
    }

    private fun watchGitFolder(root: Path) {
        val gitFolder = root.resolve(".git")
        // A .git file (worktrees, submodules) cannot be watched; the handle then does nothing.
        gitWatch =
            fileWatcher.watch(gitFolder) { changed ->
                if (target?.root != root) return@watch
                logChanges(root, gitFolder, changed)
                // Reported itself when events were lost or it was deleted: watch it anew if it is there.
                if (gitFolder in changed && Files.isDirectory(gitFolder)) {
                    gitWatch?.close()
                    watchGitFolder(root)
                }
                gitChanges.incrementAndGet()
                refresh()
            }
    }

    /** Logs a batch of changes in `.git` on the executor: the listener runs on the JavaFX thread. */
    private fun logChanges(
        root: Path,
        gitFolder: Path,
        changed: List<Path>,
    ) {
        try {
            executor.execute {
                try {
                    val shown = changed.take(MAX_LOGGED_PATHS).joinToString(", ") { relativeToGit(gitFolder, it) }
                    val more =
                        if (changed.size > MAX_LOGGED_PATHS) ", … (+${changed.size - MAX_LOGGED_PATHS} more)" else ""
                    GitStatusLog.info("Changes in .git of $root: $shown$more")
                } catch (_: RuntimeException) {
                    // Logging must not break the status.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private fun relativeToGit(
        gitFolder: Path,
        path: Path,
    ): String =
        if (path.startsWith(gitFolder)) gitFolder.relativize(path).toString().ifEmpty { "." } else path.toString()

    private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    override fun close() {
        gitWatch?.close()
        target = null
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_LOGGED_PATHS = 5
    }
}
