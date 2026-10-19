package dev.sluide.git

import dev.sluide.backgroundtasks.BackgroundTasks
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

/** The name of the task of a `git status` (ADR 0061). */
private const val REFRESHING = "Refreshing Git status"

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
 * in what the status, the log or the refs read (commits, staging, checkouts). [open], [refresh] and listeners of
 * [snapshot] use the JavaFX thread, or whatever [dispatch] stands for in tests.
 *
 * Git runs only while [trust] allows it (ADR 0053): first after the folder's checks, and after a change in `.git` of
 * what git's trust check reads (its [trustInputs]) once the check ran again, since a checkout or a command in a
 * terminal may have changed what git would run. Otherwise the snapshot is [GitStatusSnapshot.EMPTY]; once the folder is
 * trusted, the window calls [refresh].
 *
 * A batch of changes in `.git` first compares two stamps of the git folder with those of the last cycle (ADR 0062):
 * the trust check runs again only if the stamp of its inputs changed, [gitFolderChanged] counts only a change of the
 * stamp of what the status, the log and the refs read, and a batch that changed neither runs nothing at all (changes of
 * objects, reflogs, lock files). [gitChanged] counts as a change of both.
 *
 * Each `git status` is a task of [tasks], "Refreshing Git status" (ADR 0061); the trust checks are tasks of their own.
 * A failed one finishes it with "git status failed" at INFO, which ends its cycle in the log.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GitStatusService(
    private val git: GitCli,
    private val fileWatcher: FileWatcher,
    private val trust: ProjectTrust,
    private val tasks: BackgroundTasks,
    /** What git's trust check read for a folder ([GitTrustCheck.inputs]); without them, every change rechecks. */
    private val trustInputs: (Path) -> GitTrustInputs? = { null },
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor(
        git: GitCli,
        fileWatcher: FileWatcher,
        trust: ProjectTrust,
        tasks: BackgroundTasks,
        trustCheck: GitTrustCheck,
    ) : this(git, fileWatcher, trust, tasks, trustCheck::inputs, Platform::runLater)

    /** The repository followed; what its cycles found last is kept here and used on the thread `sluide-git` only. */
    private class Target(
        val root: Path,
        /** The counts of [gitChanges] and [forcedChanges] the last cycle took. */
        var handledChanges: Long,
        var handledForced: Long,
    ) {
        /** Whether a cycle ran for it; the first takes the stamp of the state without counting a change. */
        var cycled = false

        /** The stamp of the trust check's inputs taken before it last ran again; `null`: run it on the next change. */
        var trustStamp: String? = null

        /** The stamp of the state taken before [gitFolderChanged] last counted (or the first cycle); `null`: changed. */
        var stateStamp: String? = null
    }

    private val _snapshot = ReadOnlyObjectWrapper(GitStatusSnapshot.EMPTY)

    /** The latest status of the open repository; replaced only when it changed. */
    val snapshot: ReadOnlyObjectProperty<GitStatusSnapshot> = _snapshot.readOnlyProperty

    private val executor =
        NamedThreads.single("sluide-git")

    /** Runs the diffs of [diffAsync], so that they never wait behind a slow status run or trust check (ADR 0062). */
    private val diffExecutor = NamedThreads.single("sluide-git-diff")

    /** The newest request of [diffAsync] by file, which alone is answered; used on the thread [dispatch] runs on. */
    private val diffRequests = HashMap<Path, GitCancellation>()

    /** The repository being followed; a new object for every [open], so results for an older one are dropped. */
    @Volatile private var target: Target? = null
    private val scheduled = AtomicBoolean()

    /** Whether a `git status` was asked for ([refresh]) since the last cycle took it. */
    private val statusWanted = AtomicBoolean()

    /** Counts the changes of `.git` noticed by the watch or told by [gitChanged]. */
    private val gitChanges = AtomicLong()

    /** Counts the changes told by [gitChanged], which count whatever the stamps say. */
    private val forcedChanges = AtomicLong()

    /**
     * The count of [gitChanges] that a cycle has taken while git was allowed: git's trust check ran again for them, or
     * the stamp of its inputs said it need not. While it is behind, git's configuration may have changed, so
     * [diffAsync] runs no git (ADR 0053).
     */
    @Volatile private var confirmedChanges = 0L
    private var gitWatch: AutoCloseable? = null

    /** Whether the open folder is a git repository that is followed. */
    val active: Boolean get() = target != null

    /** The root of the repository that is followed (the opened folder, absolute and normalized); `null` if none. */
    val root: Path? get() = target?.root

    private val _gitFolderChanged = ReadOnlyLongWrapper()

    /**
     * Counts the changes of git's state in the followed repository, for listeners that want to know when it may have
     * changed (e.g. the git log, ADR 0035). Changes on the JavaFX thread, once git's trust check has run again and
     * allows git (ADR 0053), so that listeners never run git with what a change made it run.
     *
     * Counted are batches of changes in `.git` that changed its stamp of the state (ADR 0062): `HEAD`, the index, the
     * refs (loose and packed), `FETCH_HEAD`, `ORIG_HEAD`, a merge, cherry-pick, revert or rebase, the configuration, so
     * commits, checkouts, staging, fetches, new branches and tags; and every [gitChanged]. The watch only sees entries
     * directly in `.git`, so a change only below it (e.g. `git branch x`) counts with the next batch. Nothing is
     * noticed in a repository with a `.git` file (worktrees, submodules), which is not watched.
     */
    val gitFolderChanged: ReadOnlyLongProperty = _gitFolderChanged.readOnlyProperty

    /** Follows the repository at [folder], if git is available and it has `.git`; `null` follows nothing. */
    fun open(folder: Path?) {
        gitWatch?.close()
        gitWatch = null
        val root = folder?.toAbsolutePath()?.normalize()?.takeIf { git.available && Files.exists(it.resolve(".git")) }
        target = root?.let { Target(it, gitChanges.get(), forcedChanges.get()) }
        confirmedChanges = gitChanges.get()
        _snapshot.set(GitStatusSnapshot.EMPTY)
        if (root == null) return
        watchGitFolder(root)
        refresh()
    }

    /** Runs `git status` again soon; calls during a run are combined into one more run. */
    fun refresh() {
        statusWanted.set(true)
        schedule()
    }

    /**
     * Tells that git's state changed through the window itself, e.g. a write of the git log, of which the watch of
     * `.git` may miss a part (new branches): whatever the stamps say, git's trust check runs again, then
     * [gitFolderChanged] counts it if git is still allowed. The stamps taken then make the batch of the watch for the
     * same change run nothing (ADR 0062).
     */
    fun gitChanged() {
        forcedChanges.incrementAndGet()
        gitChanges.incrementAndGet()
        schedule()
    }

    /** Runs a cycle soon; requests while one is waiting are combined into it. */
    private fun schedule() {
        if (target == null || !scheduled.compareAndSet(false, true)) return
        try {
            executor.execute(::cycle)
        } catch (_: RejectedExecutionException) {
            // Closed.
            scheduled.set(false)
        }
    }

    /** What a cycle found out about `.git` before it runs anything: what changed since the last one. */
    private class Changes(
        val changes: Long,
        val trustChanged: Boolean,
        val stateChanged: Boolean,
        val stateStamp: String?,
    )

    /** One cycle, on `sluide-git`: the stamps, then git's trust check or waiting for it, then `git status`. */
    private fun cycle() {
        scheduled.set(false)
        val current = target ?: return
        val wanted = statusWanted.getAndSet(false)
        val cycleStart = System.nanoTime()
        val found = changes(current)
        if (!wanted && !found.trustChanged && !found.stateChanged) {
            // Only objects, reflogs, lock files…: what anything reads is as it was.
            confirmedChanges = found.changes
            GitStatusLog.info("Git status cycle for ${current.root}: .git unchanged, nothing runs")
            return
        }
        GitStatusLog.info("Git status cycle for ${current.root} started")
        val allowed =
            if (found.trustChanged) trust.recheck(GitTrustCheck.ID) else trust.awaitAllows(GitTrustCheck.ID)
        val trustMillis = millisSince(cycleStart)
        val trustText = if (found.trustChanged) "trust recheck $trustMillis ms" else "trust awaited $trustMillis ms"
        if (!allowed) {
            GitStatusLog.info("Git status cycle for ${current.root}: $trustText, git not allowed")
            dispatch(Runnable { if (target === current) _snapshot.set(GitStatusSnapshot.EMPTY) })
            return
        }
        confirmedChanges = found.changes
        if (found.stateChanged) {
            current.stateStamp = found.stateStamp
            val counted = Runnable { if (target === current) _gitFolderChanged.set(_gitFolderChanged.get() + 1) }
            dispatch(counted)
        }
        status(current, trustText)
    }

    /**
     * Takes the changes of `.git` since the last cycle of [current] and, if there are any, the stamps of the git folder,
     * before anything runs: a change while the cycle runs then differs from them next time.
     */
    private fun changes(current: Target): Changes {
        // In this order: gitChanged() counts its forced change first, so a forced change is never missed.
        val changes = gitChanges.get()
        val forcedCount = forcedChanges.get()
        val forced = forcedCount != current.handledForced
        val batch = changes != current.handledChanges
        current.handledChanges = changes
        current.handledForced = forcedCount
        val first = !current.cycled
        current.cycled = true
        val stateStamp = if (batch || forced || first) GitFolderStamps.state(current.root) else null
        if (first && !batch) current.stateStamp = stateStamp
        if (!batch && !forced) return Changes(changes, trustChanged = false, stateChanged = false, stateStamp)
        val trustStamp = GitFolderStamps.trust(trustInputs(current.root))
        val trustChanged = forced || trustStamp == null || trustStamp != current.trustStamp
        if (trustChanged) current.trustStamp = trustStamp
        val stateChanged = forced || stateStamp == null || stateStamp != current.stateStamp
        return Changes(changes, trustChanged, stateChanged, stateStamp)
    }

    /** Runs `git status` for [current] and publishes its snapshot if it changed. */
    private fun status(
        current: Target,
        trustText: String,
    ) {
        val statusStart = System.nanoTime()
        val task = tasks.start(REFRESHING)
        val (snapshot, statusMillis) =
            try {
                val output = git.status(current.root)
                val statusMillis = millisSince(statusStart)
                if (output == null) {
                    // At INFO, the cycle's end: the git#n line has the details, and a broken repository fails on every
                    // refresh.
                    task.close("git status failed")
                    return
                }
                GitStatusSnapshot.parse(current.root, output) to statusMillis
            } catch (t: Throwable) {
                task.failed(t)
                throw t
            }
        task.close()
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

    /**
     * Finds how [file] differs from its version at `HEAD` (ADR 0015) on a thread of its own, which does not wait for
     * status runs (ADR 0062), and gives it to [done] through [dispatch]: `null` if no repository is followed, the
     * folder's trust keeps git off (also while it is checked again after a change of `.git`), the file is not in
     * `HEAD`, or git fails. Only the newest request for a file is answered: an older one does not start, its running
     * git is stopped and its result dropped ([cancelDiff] too). A result for a repository no longer followed (after
     * another [open]) is dropped as well. Call it on the thread [dispatch] runs on.
     */
    fun diffAsync(
        file: Path,
        done: (GitDiff?) -> Unit,
    ) {
        val path = file.toAbsolutePath().normalize()
        val request = GitCancellation()
        diffRequests.put(path, request)?.cancel()
        val current = target
        try {
            diffExecutor.execute {
                if (request.cancelled) return@execute
                val diff = diff(current, path, request)
                dispatch(
                    Runnable {
                        if (diffRequests[path] !== request) return@Runnable
                        diffRequests.remove(path)
                        // Not for a repository no longer followed: [open] emptied the snapshot, which asks anew.
                        if (target === current) done(diff)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
            diffRequests.remove(path, request)
        }
    }

    /** Drops the request for the diff of [file], if any, e.g. once it has no diff anyway; its git is stopped. */
    fun cancelDiff(file: Path) {
        diffRequests.remove(file.toAbsolutePath().normalize())?.cancel()
    }

    /** The diff of [diffAsync], on its thread: git runs only while [current] is followed and allowed to run. */
    private fun diff(
        current: Target?,
        path: Path,
        cancellation: GitCancellation,
    ): GitDiff? {
        if (current == null || target !== current) return null
        if (gitChanges.get() != confirmedChanges || !trust.allows(GitTrustCheck.ID)) return null
        val original = git.headContent(current.root, path, cancellation) ?: return null
        val output = git.diffToHead(current.root, path, cancellation) ?: return null
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
                schedule()
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
        diffExecutor.shutdownNow()
    }

    private companion object {
        const val MAX_LOGGED_PATHS = 5
    }
}
