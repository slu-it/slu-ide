package dev.sluide.git.remote

import dev.sluide.git.RemoteRepository
import dev.sluide.git.cli.canPassArgument
import dev.sluide.git.cli.refusal

/*
 * What the remote source cache checks before git runs (in the spirit of ADR 0052): the parts of a repository name a
 * folder, the URL decides where git connects to, the ref, commit and path become arguments. Each function says why a
 * value is refused, short and for the user, or `null` if it can be used.
 */

private val HOST =
    Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*(:[0-9]{1,5})?")
private val SEGMENT = Regex("[A-Za-z0-9_.-]+")
private val REF = Regex("[A-Za-z0-9._/-]+")
private val COMMIT = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

/** The characters of a URL (RFC 3986), without spaces, quotes or backslashes. */
private val URL_CHARACTERS = Regex("[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
private const val USER = "[A-Za-z0-9_][A-Za-z0-9._-]*"
private const val URL_HOST = "[A-Za-z0-9][A-Za-z0-9.-]*"
private const val PORT = "(:[0-9]{1,5})?"
private const val PATH = "[A-Za-z0-9_~./-]+"

/** No user or password in the host part (they would end up in the log), no query. */
private val HTTPS_URL = Regex("https://(?<host>$URL_HOST$PORT)(?<path>/$PATH)")
private val SSH_URL = Regex("ssh://($USER@)?(?<host>$URL_HOST)$PORT(?<path>/$PATH)")
private val SCP_URL = Regex("$USER@(?<host>$URL_HOST):(?<path>[A-Za-z0-9_~/]$PATH)")
private val FILE_URL = Regex("file://(?<path>/$PATH)")

/** Why [repository] cannot name a cache folder: its [host][RemoteRepository.host], owner or name. */
internal fun locationRefusal(repository: RemoteRepository): String? =
    when {
        !HOST.matches(repository.host) -> "invalid host '${repository.host}'"
        segmentRefusal(repository.owner) -> "invalid owner '${repository.owner}'"
        segmentRefusal(repository.name) -> "invalid repository name '${repository.name}'"
        else -> null
    }

/**
 * Why git must not fetch from [repository]: [locationRefusal]; a URL that is neither https (without credentials, which
 * would end up in the log, and without a query) nor ssh (`ssh://` or `user@host:path`), `file://` only if
 * [allowFileUrls] (tests); or a URL of another repository than the one its folder is named after, so that a folder
 * never holds another repository's commits. Refused are, among others, other transports (`ext::`, `git://`,
 * `http://`), local paths and option-like values.
 *
 * The URL matches if its host is the [host][RemoteRepository.host] ignoring case (https: with the port; ssh: without
 * user and port, which differ from the web host's) and its path is `owner/name`, optionally with `.git` and a trailing
 * `/`, ignoring case as GitHub does; a `file://` path ends with it. Hosts with deeper paths (GitLab subgroups) are not
 * supported.
 */
internal fun repositoryRefusal(
    repository: RemoteRepository,
    allowFileUrls: Boolean,
): String? {
    locationRefusal(repository)?.let { return it }
    val url = repository.url
    if (refusal(url) != null || !URL_CHARACTERS.matches(url)) return UNSUPPORTED_URL
    val shape =
        when {
            url.startsWith("https://") -> HTTPS_URL
            url.startsWith("ssh://") -> SSH_URL
            url.startsWith("file://") -> FILE_URL.takeIf { allowFileUrls }
            else -> SCP_URL
        }
    val match = shape?.matchEntire(url) ?: return UNSUPPORTED_URL
    val host = if (shape == HTTPS_URL) repository.host else repository.host.substringBefore(':')
    if (shape != FILE_URL && match.group("host") != host.lowercase()) return URL_MISMATCH
    val path = match.group("path")?.removeSuffix("/") ?: return URL_MISMATCH
    val names = listOf("${repository.owner}/${repository.name}", "${repository.owner}/${repository.name}.git")
    val matches =
        if (shape == FILE_URL) {
            names.any { path.endsWith("/$it", ignoreCase = true) }
        } else {
            names.any { path.removePrefix("/").equals(it, ignoreCase = true) }
        }
    return if (matches) null else URL_MISMATCH
}

private fun MatchResult.group(name: String): String? = groups[name]?.value?.lowercase()

private const val UNSUPPORTED_URL = "unsupported repository URL"
private const val URL_MISMATCH = "repository URL does not match"

private fun segmentRefusal(segment: String) =
    !SEGMENT.matches(segment) || segment == "." || segment == ".." || segment.startsWith('-')

/**
 * Why [ref] cannot be fetched: a branch, tag or commit as `[A-Za-z0-9._/-]+`, without `..`, a leading `-` or `/`, a
 * trailing `/` or `.lock`, empty components or components starting with `.`.
 */
internal fun refRefusal(ref: String): String? {
    val components = ref.split('/')
    val usable =
        refusal(ref) == null &&
            REF.matches(ref) &&
            ".." !in ref &&
            !ref.endsWith(".lock") &&
            components.none { it.isEmpty() || it.startsWith('.') }
    return if (usable) null else "invalid ref '$ref'"
}

/** Why [commit] is no full commit hash (40 or 64 hexadecimal digits). */
internal fun commitRefusal(commit: String): String? = if (isFullCommit(commit)) null else "invalid commit '$commit'"

internal fun isFullCommit(ref: String): Boolean = COMMIT.matches(ref)

/**
 * Why [path] cannot be read from a commit: it must be relative and `/`-separated, without empty, `.` or `..`
 * components, backslashes or control characters.
 */
internal fun pathRefusal(path: String): String? {
    val usable =
        path.isNotEmpty() &&
            canPassArgument(path) &&
            path.none { it == '\\' || it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    return if (usable) null else "invalid path '$path'"
}
