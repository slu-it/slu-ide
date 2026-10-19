package dev.sluide.projecttrust

import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private val log = System.getLogger(ProjectTrust::class.java.name)

/** How long a tool waits for the checks of an opened folder before it stays off. */
private const val CHECKS_TIMEOUT_SECONDS = 30L

/**
 * Whether the tools of the window's folder may run what the folder defines (ADR 0053). [open] runs every
 * [ProjectTrustCheck] (all beans of that interface) and combines what they found into one [state] and one fingerprint:
 * - nothing found: [ProjectTrustState.Trusted], without asking;
 * - a decision about exactly these findings, stored or made while the folder is open: that decision;
 * - otherwise [ProjectTrustState.Ask] until the user [decide]s.
 *
 * A tool asks [allows] (or [awaitAllows] from its own thread) before it runs anything: it may once the checks have run
 * and the folder is trusted or its own check found nothing.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class ProjectTrust(
    private val checks: List<ProjectTrustCheck>,
    private val store: TrustedProjects,
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor(checks: List<ProjectTrustCheck>, store: TrustedProjects) : this(checks, store, Platform::runLater)

    init {
        require(checks.map { it.id }.toSet().size == checks.size) { "Trust checks need distinct ids" }
    }

    /** The opened folder; a new object for every [open], so results for an older one are dropped. */
    private class Opened(
        val folder: Path,
    ) {
        /** Counted down once the checks have run, or the folder was replaced or closed before. */
        val checked = CountDownLatch(1)

        // Guarded by the lock.
        var results: Map<String, TrustCheckResult> = emptyMap()
        var current: ProjectTrustState = ProjectTrustState.Checking

        /** The user's decisions while the folder is open, also those not stored: by fingerprint, whether trusted. */
        val decided = mutableMapOf<String, Boolean>()
    }

    private val lock = Any()

    @Volatile private var opened: Opened? = null

    /** The checks of the opened folder while they run; cancelled when another folder is opened. */
    private var running: Future<*>? = null

    private val _state = ReadOnlyObjectWrapper<ProjectTrustState>(ProjectTrustState.Closed)

    /** Whether the folder's tools may run; changes on the JavaFX thread (through the dispatcher). */
    val state: ReadOnlyObjectProperty<ProjectTrustState> = _state.readOnlyProperty

    private val executor =
        NamedThreads.single("sluide-trust")

    /** Checks [folder] on a thread of its own; `null` closes the folder. For the JavaFX thread, as [close]. */
    fun open(folder: Path?) {
        running?.cancel(true)
        running = null
        opened?.checked?.countDown()
        val target = folder?.toAbsolutePath()?.normalize()?.let(::Opened)
        opened = target
        if (target == null) {
            dispatch(Runnable { if (opened == null) _state.set(ProjectTrustState.Closed) })
            return
        }
        publish(target, ProjectTrustState.Checking)
        running =
            try {
                executor.submit(
                    Runnable {
                        try {
                            val results = checks.associate { it.id to run(it, target.folder) }
                            synchronized(lock) {
                                if (opened !== target) return@Runnable
                                target.results = results
                                settle(target, rechecked = false)
                            }
                        } finally {
                            target.checked.countDown()
                        }
                    },
                )
            } catch (_: RejectedExecutionException) {
                // Closed: no tool runs.
                target.checked.countDown()
                null
            }
    }

    /**
     * Whether the tool of the check [checkId] may run now: the checks have run, and the folder is trusted or that check
     * found nothing (a tool without a check: always, once they have run). `false` while they run or no folder is open.
     */
    fun allows(checkId: String): Boolean {
        val target = opened ?: return false
        return synchronized(lock) { allowed(target, checkId) }
    }

    /**
     * Like [allows], but first waits for the checks of the opened folder, up to 30 s (`false` then); never call it on
     * the JavaFX thread.
     */
    fun awaitAllows(checkId: String): Boolean {
        checkNotFxThread("awaitAllows")
        val target = opened ?: return false
        if (!awaitChecked(target)) return false
        return synchronized(lock) { opened === target && allowed(target, checkId) }
    }

    /**
     * Runs the check [checkId] again on the calling thread, e.g. when what it checks may have changed, and returns
     * [allows] for it. New findings after the folder was settled make the state [ProjectTrustState.Ask] with `changed`.
     * Waits for the first checks like [awaitAllows]; never call it on the JavaFX thread.
     */
    fun recheck(checkId: String): Boolean {
        checkNotFxThread("recheck")
        val target = opened ?: return false
        if (!awaitChecked(target)) return false
        val check = checks.firstOrNull { it.id == checkId } ?: return allows(checkId)
        val result = run(check, target.folder)
        synchronized(lock) {
            if (opened !== target) return false
            target.results += checkId to result
            settle(target, rechecked = true)
            return allowed(target, checkId)
        }
    }

    /**
     * The user's answer about the findings of the [shown] state: [trusted] or not. Ignored if the state is another one
     * by now (a recheck found something else, another folder was opened): the user decides only about what was shown.
     * The answer is stored for the folder if [remember] and every check could check, otherwise kept while the folder
     * is open. Returns whether it was taken. For the JavaFX thread, which also makes the store's writes one at a time.
     */
    fun decide(
        shown: ProjectTrustState,
        trusted: Boolean,
        remember: Boolean = true,
    ): Boolean {
        val target = opened ?: return false
        synchronized(lock) {
            if (shown != target.current || target.current == ProjectTrustState.Checking) return false
            val results = ordered(target)
            if (results.all { it.clean }) return false
            val fingerprint = fingerprint(results)
            target.decided[fingerprint] = trusted
            if (remember && results.none { it.findings == null }) store.remember(target.folder, fingerprint, trusted)
            settle(target, rechecked = false)
            return true
        }
    }

    override fun close() {
        open(null)
        executor.shutdownNow()
    }

    /** By name rather than `Platform`, which would start the toolkit in unit tests (ADR 0060). */
    private fun checkNotFxThread(what: String) =
        check(Thread.currentThread().name != "JavaFX Application Thread") { "$what blocks; not on the FX thread" }

    /** Waits for the checks of [target]; `false` if interrupted or they take longer than the timeout. */
    private fun awaitChecked(target: Opened): Boolean =
        try {
            target.checked.await(CHECKS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun allowed(
        target: Opened,
        checkId: String,
    ): Boolean =
        target.current is ProjectTrustState.Trusted ||
            (target.current != ProjectTrustState.Checking && target.results[checkId]?.clean != false)

    private fun run(
        check: ProjectTrustCheck,
        folder: Path,
    ): TrustCheckResult {
        val findings =
            try {
                check.check(folder)
            } catch (e: Exception) {
                // Interrupted: another folder was opened, which drops the result anyway.
                if (!Thread.currentThread().isInterrupted && e !is InterruptedException) {
                    log.log(System.Logger.Level.WARNING, "The trust check ${check.id} failed for $folder", e)
                }
                null
            }
        return TrustCheckResult(check.id, check.name, findings)
    }

    private fun ordered(target: Opened) = checks.mapNotNull { target.results[it.id] }

    /** Decides the state from the results; with the lock held. */
    private fun settle(
        target: Opened,
        rechecked: Boolean,
    ) {
        val results = ordered(target)
        val previous = target.current
        val state =
            if (results.all { it.clean }) {
                ProjectTrustState.Trusted(results)
            } else {
                val fingerprint = fingerprint(results)
                when (target.decided[fingerprint] ?: store.decision(target.folder, fingerprint)) {
                    true -> {
                        ProjectTrustState.Trusted(results)
                    }

                    false -> {
                        ProjectTrustState.Untrusted(results)
                    }

                    null -> {
                        // New findings once the folder was settled are a change; while asking, the question stays one.
                        val changed =
                            when (previous) {
                                is ProjectTrustState.Ask -> previous.changed
                                is ProjectTrustState.Trusted, is ProjectTrustState.Untrusted -> rechecked
                                else -> false
                            }
                        ProjectTrustState.Ask(results, changed)
                    }
                }
            }
        if (state == previous) return
        target.current = state
        publish(target, state)
    }

    private fun publish(
        target: Opened,
        state: ProjectTrustState,
    ) = dispatch(Runnable { if (opened === target) _state.set(state) })
}

/**
 * What a decision is about: SHA-256 over the sorted SHA-256s of the findings, each with its check's id (hashed one by
 * one, so that no text of a finding can pass for two). A check that could not check counts as a finding of its own;
 * checks that found nothing add nothing, so a new tool that finds nothing keeps earlier decisions.
 */
internal fun fingerprint(results: List<TrustCheckResult>): String {
    val entries =
        results
            .flatMap { result ->
                result.findings?.map { "${result.id}\u0000${it.fingerprint}" }
                    ?: listOf("${result.id}\u0000\u0000unchecked")
            }.map(::sha256)
            .sorted()
    return sha256(entries.joinToString("\n"))
}

private fun sha256(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
