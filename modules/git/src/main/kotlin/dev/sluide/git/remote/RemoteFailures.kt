package dev.sluide.git.remote

/*
 * Short reasons for the user from what git says on standard error when it talks to a server (git runs with
 * `LC_ALL=C`, so in English). Only git's and ssh's own messages count, by their fixed start: not what the server says
 * (`remote:` lines), and not URLs or names within a message, which may contain any of the words. The full message goes
 * to the log only (ADR 0060).
 */

internal const val CANCELLED = "cancelled"
internal const val TIMED_OUT = "timed out"
internal const val NO_ACCESS = "repository not found or no access"
internal const val NETWORK = "network error"
internal const val REF_NOT_FOUND = "ref not found"

/** Git asked for a ref or commit the server does not have. */
private val MISSING_REF =
    listOf(
        Regex("fatal: couldn't find remote ref .*"),
        Regex("error: Server does not allow request for unadvertised object .*"),
        Regex("fatal: remote error: upload-pack: not our ref .*"),
    )

/** The server could not be reached; git's https message ends with curl's error after the quoted URL. */
private val NETWORK_ERRORS =
    listOf(
        Regex("fatal: unable to access '.*': (?!The requested URL returned error: 4\\d\\d( |$)).*"),
        Regex("ssh: .*"),
        Regex("kex_exchange_identification: .*"),
        Regex("Connection (closed|reset) by .*"),
    )

/** The server was reached, but the repository is not there or not for this user. */
private val ACCESS_ERRORS =
    listOf(
        Regex("fatal: repository '.*' not found"),
        // Older curl adds the reason phrase: "403 Forbidden".
        Regex("fatal: unable to access '.*': The requested URL returned error: 4\\d\\d( .*)?"),
        Regex("fatal: Authentication failed for '.*'"),
        Regex("fatal: could not read (Username|Password) for '.*'.*"),
        Regex("fatal: '.*' does not appear to be a git repository"),
        Regex("fatal: Could not read from remote repository\\."),
        Regex("[^ ]+: Permission denied \\(.*\\)\\.?"),
        Regex("Host key verification failed\\."),
    )

/** The reason a fetch from a server failed with [errors] on standard error; [fallback] if git said nothing known. */
internal fun remoteFailure(
    errors: String,
    fallback: String,
): String {
    val lines = errors.lines().map(String::trim).filterNot { it.startsWith("remote:") }

    fun said(patterns: List<Regex>) = lines.any { line -> patterns.any { it.matches(line) } }

    return when {
        said(MISSING_REF) -> REF_NOT_FOUND
        said(NETWORK_ERRORS) -> NETWORK
        said(ACCESS_ERRORS) -> NO_ACCESS
        else -> fallback
    }
}
