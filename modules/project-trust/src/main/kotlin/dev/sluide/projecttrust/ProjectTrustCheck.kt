package dev.sluide.projecttrust

import java.nio.file.Path

/**
 * A check of what a tool would run on its own in a project folder because the folder says so, e.g. hooks or filters
 * named by a repository's git configuration (ADR 0053). Every tool that runs commands without being asked implements it
 * as a Spring bean; [ProjectTrust] runs all of them when a folder is opened and asks the user once about everything they
 * found. Until then, and while the user does not trust the folder, the tool asks [ProjectTrust.allows] and stays off.
 */
interface ProjectTrustCheck {
    /** Identifies the check in stored decisions and in [ProjectTrust.allows]; never changes, e.g. `git`. */
    val id: String

    /** The tool's name, shown above its findings and among the tools that stay off, e.g. `Git`. */
    val name: String

    /**
     * What the tool would run in [folder] beyond its defaults: none if nothing, `null` if it could not be checked.
     * Blocking, never called on the JavaFX thread; must not run anything the folder defines, and must end in a few
     * seconds (tools wait for it) or when its thread is interrupted.
     */
    fun check(folder: Path): List<TrustFinding>?
}

/**
 * One thing a check found: [text] says what it is (`core.fsmonitor = ./watch.sh (.git/config)`), [note] when it runs
 * (`runs on every git status`), if known. [fingerprint] identifies it in the stored decision and changes when what would
 * run changes (e.g. a hook's content).
 */
data class TrustFinding(
    val text: String,
    val note: String? = null,
    val fingerprint: String = text,
)

/** What the check [id] of the tool [name] found; `null` [findings] if it could not check. */
data class TrustCheckResult(
    val id: String,
    val name: String,
    val findings: List<TrustFinding>?,
) {
    /** Whether the check found nothing: the tool runs whatever the user decides. */
    val clean: Boolean get() = findings?.isEmpty() == true
}

/** Whether the tools of the opened folder may run what it defines. */
sealed interface ProjectTrustState {
    /** The results of all checks, in the order of the checks; empty while none have run. */
    val results: List<TrustCheckResult> get() = emptyList()

    /** The results of the checks that found something or could not check. */
    val flagged: List<TrustCheckResult> get() = results.filterNot { it.clean }

    /** No folder is open. */
    data object Closed : ProjectTrustState

    /** The checks of the opened folder are running; no checked tool runs yet. */
    data object Checking : ProjectTrustState

    /** Every tool runs: the checks found nothing, or the user trusts what they found. */
    data class Trusted(
        override val results: List<TrustCheckResult>,
    ) : ProjectTrustState

    /**
     * The checks found something the user has not decided about; tools with findings stay off. [changed] if a check run
     * again found it once the folder was settled (trusted, also because nothing was found, or not), rather than when the
     * folder was opened.
     */
    data class Ask(
        override val results: List<TrustCheckResult>,
        val changed: Boolean,
    ) : ProjectTrustState

    /** The user does not trust what the checks found; tools with findings stay off. */
    data class Untrusted(
        override val results: List<TrustCheckResult>,
    ) : ProjectTrustState
}
