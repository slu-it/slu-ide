package dev.sluide.java

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.SessionAccess
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Where a build root's import stands (ADR 0179). */
internal sealed interface ImportState {
    data class Importing(
        val since: Instant,
    ) : ImportState

    /** [projects] are the project folders `java.project.getAll` listed below the root. */
    data class Done(
        val compliance: String?,
        val projects: List<Path> = emptyList(),
    ) : ImportState

    /** [actions] are made once per failure, so that an unchanged failure compares equal. */
    data class Failed(
        val detail: String,
        val actions: List<ProblemAction>,
    ) : ImportState
}

private const val COMPLIANCE_KEY = "org.eclipse.jdt.core.compiler.compliance"
private const val JDTLS_GRADLE_JDK_EVENT = 300
private val SETTLE_AFTER: Duration = Duration.ofSeconds(10)
private val log = System.getLogger(JavaImports::class.java.name)

/** The `language/status` message jdtls 1.63.0 sends first for every `didChangeWorkspaceFolders` (seen on the wire). */
private val FOLDERS_STATUS = Regex("""Updating workspace folders: Adding (\d+) folder\(s\), removing (\d+) folders\.""")

/**
 * Tracks the import of each announced build root (ADR 0179): importing from its announcement (an add or a refresh)
 * until no job of jdtls that adds it is left ([UpdateJobs] mirrors them), then done if `java.project.getAll` lists a
 * project under it, else failed; event 300 and the kept diagnostics give the reason of a failure. A failed root whose
 * project a later `getAll` lists is done (an import the mirror settled too early, or one jdtls ran by itself); one
 * failed by event 300 stays failed.
 *
 * The session calls [onFolderAdded], [onFolderRemoved] and [onFolderRefreshed] once per notification, posted from its
 * outbound thread after the write; statuses, progress and events come from its reader thread. Both are posted to the
 * FX thread in their own order, and jdtls answers notifications in the order it read them, so the k-th folder status
 * answers the k-th notification; only a status may come before its notification. Such a status is held, and with it
 * everything the reader posted after it, until the notification comes: then the mirror sees them in jdtls's order.
 *
 * All calls and the command answers are on the FX thread; the wrapper file is read on [worker].
 *
 * @param states the state per root, read by the spec's banner and status line.
 * @param startTicker starts a task that runs every second on the FX thread; closing the result stops it.
 * @param jdkVersion the version of the JDK jdtls runs Gradle with, if resolved.
 * @param actionsFor the buttons of a failed root's banner.
 * @param settleAfter the silence after which a job the mirror is in doubt about is settled ([UpdateJobs]).
 * @param outputs the offer to ignore jdtls's compiled output in git: told of each root that is done, of a root that
 *   no longer is, and of the progress tokens, which tell when a build ended.
 */
internal class JavaImports(
    private val access: SessionAccess,
    private val states: MutableMap<Path, ImportState>,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val clock: Clock,
    private val startTicker: (Runnable) -> AutoCloseable,
    private val jdkVersion: () -> String?,
    private val actionsFor: (Path) -> List<ProblemAction>,
    private val settleAfter: Duration = SETTLE_AFTER,
    private val warn: (String) -> Unit = { log.log(System.Logger.Level.WARNING, it) },
    private val outputs: OutputIgnoreOffers? = null,
) {
    private val announcements = HashMap<Path, Int>()

    /** When each root was last announced, counting all announcements: the order a resync queues the roots in. */
    private val order = HashMap<Path, Int>()
    private var announced = 0
    private val eventFailed = HashSet<Path>()

    /** What the reader posted from a status on that waits for its notification; each returns whether it was taken. */
    private val held = ArrayDeque<() -> Boolean>()
    private var heldSince: Instant? = null
    private var heldCounts: Pair<Int?, Int?> = null to null

    /** When a `getAll` that failed is asked again. */
    private var askAgainAt: Instant? = null

    /** Counts the servers, so that an answer of a server that stopped is dropped. */
    private var generation = 0
    private var warned = false
    private var ticker: AutoCloseable? = null
    private var closed = false

    private val jobs =
        UpdateJobs(clock, settleAfter, ::verify, ::importingNotifications) { added, removed ->
            if (!warned) {
                warned = true
                warn(
                    "jdtls's workspace folder status (adding $added, removing $removed) does not fit the " +
                        "notifications sent; the import tracking starts over",
                )
            }
        }

    fun onFolderAdded(root: Path) = announce(root, removes = false)

    fun onFolderRefreshed(root: Path) = announce(root, removes = true)

    fun onFolderRemoved(root: Path) {
        if (closed) return
        jobs.sent(FolderNotification(root, adds = false, removes = true))
        eventFailed.remove(root)
        val removed = states.remove(root) != null
        outputs?.forget(root)
        drain()
        if (removed) changed()
    }

    private fun announce(
        root: Path,
        removes: Boolean,
    ) {
        if (closed) return
        announcements[root] = (announcements[root] ?: 0) + 1
        order[root] = ++announced
        jobs.sent(FolderNotification(root, adds = true, removes = removes))
        eventFailed.remove(root)
        states[root] = ImportState.Importing(clock.instant())
        outputs?.forget(root)
        drain()
        changed()
    }

    fun onProgress(
        token: String,
        value: JsonElement,
    ) {
        if (closed) return
        val progress = value as? JsonObject ?: return
        when (progress.text("kind")) {
            "begin" -> {
                receive {
                    true.also {
                        jobs.begin(token, progress.text("title"))
                        outputs?.progressBegan(token, progress.text("title"))
                    }
                }
            }

            "end" -> {
                receive {
                    true.also {
                        jobs.end(token)
                        outputs?.progressEnded(token)
                    }
                }
            }
        }
    }

    /** A `language/status` notification; only jdtls's "Updating workspace folders" message counts. */
    fun onStatus(params: JsonElement?) {
        if (closed) return
        val status = params as? JsonObject ?: return
        if (status.text("type") != "Message") return
        val match = FOLDERS_STATUS.matchEntire(status.text("message") ?: return) ?: return
        val added = match.groupValues[1].toIntOrNull()
        val removed = match.groupValues[2].toIntOrNull()
        receive { jobs.status(added, removed).also { taken -> if (!taken) heldCounts = added to removed } }
    }

    fun onEvent(params: JsonElement?) {
        if (closed) return
        receive { true.also { event(params) } }
    }

    /** The roots whose import failed. */
    fun failedRoots(): List<Path> = states.entries.filter { it.value is ImportState.Failed }.map { it.key }

    /** A server connected: statuses count from its first notification on, as the session extension's contract says. */
    fun onConnected() {
        held.clear()
        heldSince = null
    }

    fun onStopped() {
        if (closed) return
        states.clear()
        held.clear()
        heldSince = null
        askAgainAt = null
        jobs.clear()
        outputs?.clear()
        generation++
        // The announcement numbers go on counting, so an answer of the old server never matches a new announcement.
        changed()
    }

    fun close() {
        closed = true
        stopTicker()
    }

    /** Takes [input] now, or holds it behind a status that waits for its notification. */
    private fun receive(input: () -> Boolean) {
        held.addLast(input)
        drain()
    }

    private fun drain() {
        while (held.isNotEmpty()) {
            if (!held.first()()) {
                if (heldSince == null) heldSince = clock.instant()
                return
            }
            held.removeFirst()
            heldSince = null
        }
    }

    private fun event(params: JsonElement?) {
        val event = params as? JsonObject ?: return
        if (event.get("eventType")?.takeIf { it.isJsonPrimitive }?.asInt != JDTLS_GRADLE_JDK_EVENT) return
        val data = event.get("data") as? JsonObject ?: return
        val project =
            data
                .get("projectUri")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.let(LspUris::toPath) ?: return
        val root = states.keys.filter { project.startsWith(it) }.maxByOrNull { it.nameCount } ?: return
        eventFailed.add(root)
        val number = announcements[root]
        val newest = data.text("highestJavaVersion")
        val recommended = data.text("recommendedGradleVersion")
        val jdk = jdkVersion()
        try {
            worker.execute {
                val detail = ImportFailures.eventDetail(ImportFailures.gradleVersion(root), jdk, newest, recommended)
                dispatch(
                    Runnable {
                        if (!closed && announcements[root] == number && root in eventFailed) {
                            fail(root, detail)
                        }
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            // The session closed.
        }
    }

    /** The notifications of the roots that import, in the order they were announced. */
    private fun importingNotifications(): List<FolderNotification> =
        states.entries
            .filter { it.value is ImportState.Importing }
            .map { it.key }
            .sortedBy { order[it] }
            .map { FolderNotification(it, adds = true, removes = false) }

    /**
     * A job that adds a root ended, was settled, or an update token without a job ended: asks `getAll` and gives every
     * root with no import to come its verdict, unless no root could change. A `getAll` that fails is asked [again]
     * once [settleAfter] passed; when that fails too, the roots that wait for their verdict fail with its message.
     */
    private fun verify(again: Boolean = false) {
        if (states.keys.none { root -> awaitsVerdict(root) || upgradable(root) }) return
        val asked = generation
        access.executeCommand("java.project.getAll", emptyList()).whenComplete { result, error ->
            if (closed || asked != generation) return@whenComplete
            if (error != null) {
                if (again) unlisted(error) else askAgainAt = clock.instant().plus(settleAfter)
                return@whenComplete
            }
            val projects = (result as? List<*>).orEmpty().filterIsInstance<String>()
            var changed = false
            for (root in states.keys.toList()) {
                val waited = awaitsVerdict(root)
                if (!waited && !upgradable(root)) continue
                val first = projects.firstOrNull { uri -> LspUris.toPath(uri)?.startsWith(root) == true }
                if (first != null) {
                    val below = projects.mapNotNull(LspUris::toPath).filter { it.startsWith(root) }
                    states[root] = ImportState.Done(null, below)
                    outputs?.rootDone(root, below)
                    askCompliance(root, first)
                    changed = true
                } else if (waited) {
                    states[root] =
                        ImportState.Failed(
                            ImportFailures.diagnosticDetail(root, access::keptDiagnostics),
                            actionsFor(root),
                        )
                    changed = true
                }
            }
            if (changed) changed()
        }
    }

    /** The second `getAll` failed too: the roots that wait for their verdict fail with its message. */
    private fun unlisted(error: Throwable) {
        val cause = (error as? CompletionException)?.cause ?: error
        val roots = states.keys.filter(::awaitsVerdict)
        roots.forEach { root ->
            states[root] =
                ImportState.Failed(
                    "jdtls did not list its projects: ${cause.message ?: cause.javaClass.simpleName}",
                    actionsFor(root),
                )
        }
        if (roots.isNotEmpty()) changed()
    }

    /** Whether [root] imports, no job adds it any more and no event 300 failed it. */
    private fun awaitsVerdict(root: Path) =
        states[root] is ImportState.Importing &&
            root !in eventFailed &&
            !jobs.pending(root)

    /** Whether [root] failed because no project was found: a later project makes it done. */
    private fun upgradable(root: Path) = states[root] is ImportState.Failed && root !in eventFailed

    private fun askCompliance(
        root: Path,
        projectUri: String,
    ) {
        val number = announcements[root]
        access
            .executeCommand("java.project.getSettings", listOf(projectUri, listOf(COMPLIANCE_KEY)))
            .whenComplete { result, _ ->
                if (closed || announcements[root] != number) return@whenComplete
                val compliance = (result as? Map<*, *>)?.get(COMPLIANCE_KEY) as? String ?: return@whenComplete
                val done = states[root] as? ImportState.Done ?: return@whenComplete
                states[root] = done.copy(compliance = compliance)
                changed()
            }
    }

    private fun fail(
        root: Path,
        detail: String,
    ) {
        states[root] = ImportState.Failed(detail, actionsFor(root))
        outputs?.forget(root)
        changed()
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun importing() = states.values.any { it is ImportState.Importing }

    private fun changed() {
        access.workspaceChanged()
        if (importing()) {
            if (ticker == null) ticker = startTicker(::tick)
        } else {
            stopTicker()
        }
    }

    private fun tick() {
        if (closed) return
        val since = heldSince
        if (since != null && !clock.instant().isBefore(since.plus(settleAfter))) {
            // The status's notification never came: it is dropped as a mismatch, what it held goes on.
            held.removeFirst()
            heldSince = null
            jobs.orphaned(heldCounts.first, heldCounts.second)
            drain()
        }
        val again = askAgainAt
        if (again != null && !clock.instant().isBefore(again)) {
            askAgainAt = null
            verify(again = true)
        }
        jobs.settle()
        if (importing()) access.workspaceChanged() else stopTicker()
    }

    private fun stopTicker() {
        ticker?.close()
        ticker = null
    }
}
