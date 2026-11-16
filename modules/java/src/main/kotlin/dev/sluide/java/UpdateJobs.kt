package dev.sluide.java

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One `workspace/didChangeWorkspaceFolders` the session sent, with one folder: [root] added, removed, or both (a
 * refresh).
 */
internal data class FolderNotification(
    val root: Path,
    val adds: Boolean,
    val removes: Boolean,
)

/** The title of the `$/progress` token of jdtls's job for a folder notification. */
internal const val UPDATE_TITLE = "Updating workspace folders"

/**
 * A mirror of the jobs jdtls 1.63.0 runs for folder notifications (ADR 0179; its `ProjectsManager.updateWorkspaceFolders`
 * and traces of every case). For every notification, on its message thread and in order, jdtls sends a `language/status`
 * with the counts, cancels the jobs that have not started and whose added folders equal the notification's removed
 * ones (Eclipse's `Job.cancel()`: a waiting job never runs, a running one goes on), and schedules a job. The jobs hold
 * the workspace root and run one after another in that order; a job that runs sends one token with [UPDATE_TITLE],
 * its begin from inside the job and its end before it lets go of the root, so the next job's begin comes after it.
 *
 * Exact therefore: a status cancels every matching job behind the oldest one ([jobs]'s head), and never a head whose
 * token began. Not decidable on the wire: whether a head whose begin has not come yet had started when jdtls cancelled.
 * Measured with jdtls 1.63.0, both happen with the same input (getAll, then a removal and an add, five times; the removal job
 * ran four times and was cancelled once, with no token open either way; it waits behind a build, a project update or
 * a Gradle synchronization, whose tokens may stay open after they let go). The mirror keeps such a head: if it was
 * cancelled, each later begin binds one job early, so verdicts come late but never before the import ended, and the
 * last job waits for a begin that never comes. That job is settled after [settleAfter] in which no token of any title
 * was open and nothing came from jdtls; only while such a doubt is open, which ends when every job ended. This is
 * the one time-based rule, and the last resort. Two guards use the same time for what the traces never showed:
 * [JavaImports] drops a status that waits [settleAfter] for its notification as a mismatch ([orphaned]), and asks a
 * failing `getAll` once more after it.
 *
 * FX thread. [ended] is told when a job that adds a root ended or was settled, or an update token with no job ended:
 * a root with no import to come ([pending]) then gets its verdict.
 */
internal class UpdateJobs(
    private val clock: Clock,
    private val settleAfter: Duration,
    private val ended: () -> Unit,
    /** The notifications of the roots that still import, oldest first, for a resync. */
    private val importing: () -> List<FolderNotification>,
    private val mismatch: (added: Int?, removed: Int?) -> Unit,
) {
    /** A job of jdtls in the mirror; [begun] once its token began. */
    private class Job(
        val notification: FolderNotification,
    ) {
        var begun = false
    }

    private val unacknowledged = ArrayDeque<FolderNotification>()
    private val jobs = ArrayDeque<Job>()
    private val bound = HashMap<String, Job>()
    private val strays = HashSet<String>()
    private val open = HashSet<String>()
    private var heard: Instant = clock.instant()

    /** Whether a status kept a head it may have cancelled, since the mirror last had no job. */
    private var doubt = false

    /** The session sent [notification]. */
    fun sent(notification: FolderNotification) {
        unacknowledged.addLast(notification)
    }

    /**
     * jdtls's status for the oldest notification not acknowledged; false when there is none yet (its notification is
     * on the way, the caller holds the status). Counts that do not fit start the mirror over.
     */
    fun status(
        added: Int?,
        removed: Int?,
    ): Boolean {
        val notification = unacknowledged.removeFirstOrNull() ?: return false
        heard = clock.instant()
        if (added != notification.adds.count() || removed != notification.removes.count()) {
            resync(added, removed)
            return true
        }
        val head = jobs.firstOrNull()
        jobs.removeAll { it !== head && cancels(notification, it) }
        if (head != null && !head.begun && cancels(notification, head)) doubt = true
        jobs.addLast(Job(notification))
        return true
    }

    fun begin(
        token: String,
        title: String?,
    ) {
        // No `heard` here: the token either stays open, which keeps the mirror from settling, or ends, which sets it.
        open.add(token)
        if (title != UPDATE_TITLE) return
        val head = jobs.firstOrNull()
        if (head != null && !head.begun) {
            head.begun = true
            bound[token] = head
        } else {
            strays.add(token)
        }
    }

    fun end(token: String) {
        heard = clock.instant()
        open.remove(token)
        val job = bound.remove(token)
        if (job != null) {
            jobs.remove(job)
            if (jobs.isEmpty()) doubt = false
            if (job.notification.adds) ended()
        } else if (strays.remove(token)) {
            ended()
        }
    }

    /**
     * Whether a job for [root] is still to come or runs. Notifications and jobs go first in, first out, so one that
     * removes the root or belongs to an older announcement always comes before the newest one that adds it.
     */
    fun pending(root: Path): Boolean =
        unacknowledged.any { it.root == root } || jobs.any { it.notification.root == root }

    /** Settles the jobs that never began once the doubt is open and jdtls stayed silent; every second (the ticker). */
    fun settle() {
        // With no token open no job is begun: whatever is left never began.
        if (!doubt || unacknowledged.isNotEmpty() || open.isNotEmpty()) return
        if (clock.instant().isBefore(heard.plus(settleAfter))) return
        val dropped = jobs.toList()
        jobs.clear()
        doubt = false
        if (dropped.any { it.notification.adds }) ended()
    }

    /** A status no notification came for: the pairing is lost, as by counts that do not fit. */
    fun orphaned(
        added: Int?,
        removed: Int?,
    ) = resync(added, removed)

    fun clear() {
        unacknowledged.clear()
        jobs.clear()
        bound.clear()
        strays.clear()
        open.clear()
        doubt = false
    }

    /**
     * Starts the mirror over: the begun jobs keep their tokens, the others go, and every root that still imports and has
     * no begun job and no notification on the way is queued as acknowledged. Whether that matches jdtls's queue is in
     * doubt.
     */
    private fun resync(
        added: Int?,
        removed: Int?,
    ) {
        mismatch(added, removed)
        jobs.removeAll { !it.begun }
        importing()
            .filter { waiting ->
                unacknowledged.none { it.root == waiting.root } && jobs.none { it.notification.root == waiting.root }
            }.forEach { jobs.addLast(Job(it)) }
        doubt = true
    }

    /** Whether [by] cancels [job] if it waits: the job's added folders equal the notification's removed ones. */
    private fun cancels(
        by: FolderNotification,
        job: Job,
    ) = if (by.removes) job.notification.adds && job.notification.root == by.root else !job.notification.adds

    private fun Boolean.count() = if (this) 1 else 0
}
