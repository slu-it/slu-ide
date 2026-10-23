package dev.sluide.ide.github

import dev.sluide.git.RemoteRepository

/** GitHub's host, the last place every remote reference is looked for. */
internal const val GITHUB = "github.com"

private val HOSTNAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*")
private val PORT = Regex("[0-9]{1,5}")
private val USER = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

/** `[user@]host:path`, git's scp-like ssh form; no `/` before the colon. */
private val SCP = Regex("(?:([^@/:]+)@)?([^@/:]+):([^:].*)?")

/**
 * Where the project's origin is, as far as remote references need it (ADR 0076): its lower-case [hostname], for https
 * a [port] other than 443, and for ssh how to log in. Never its credentials or path.
 */
internal data class OriginHost(
    val hostname: String,
    val port: String? = null,
    val ssh: SshLogin? = null,
)

/** An ssh origin: its [user] and [port], and whether it is written the scp-like way ([scp], `git@host:path`). */
internal data class SshLogin(
    val user: String?,
    val port: String? = null,
    val scp: Boolean = false,
)

/**
 * The host of a remote URL as [OriginHost]: `https://` (the port kept, credentials dropped), `http://` (as https on
 * the default port), `ssh://[user@]host[:port]/…` and `[user@]host:path`; `null` for anything else (local paths,
 * `file://`, `git://`, `ext::`, an ssh user with a password) or a host or port that does not look like one. The URL
 * may hold credentials: only scheme, ssh user, host and port are taken.
 */
internal fun originHost(url: String): OriginHost? {
    val text = url.trim()
    val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme.isEmpty()) return scpHost(text)
    val authority =
        text
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    val user = authority.substringBeforeLast('@', missingDelimiterValue = "").takeIf { '@' in authority }
    val address = authority.substringAfterLast('@')
    val hostname = address.substringBefore(':')
    val port = address.substringAfter(':', missingDelimiterValue = "").takeIf { ':' in address }
    if (!HOSTNAME.matches(hostname) || (port != null && !PORT.matches(port))) return null
    return when (scheme) {
        "https" -> {
            OriginHost(hostname.lowercase(), port.takeUnless { it == "443" })
        }

        "http" -> {
            OriginHost(hostname.lowercase())
        }

        "ssh" -> {
            if (user != null && !USER.matches(user)) return null
            OriginHost(hostname.lowercase(), ssh = SshLogin(user, port))
        }

        else -> {
            null
        }
    }
}

private fun scpHost(text: String): OriginHost? {
    val match = SCP.matchEntire(text) ?: return null
    val user = match.groupValues[1].takeIf { it.isNotEmpty() }
    val hostname = match.groupValues[2]
    if (!HOSTNAME.matches(hostname) || (user != null && !USER.matches(user))) return null
    return OriginHost(hostname.lowercase(), ssh = SshLogin(user, scp = user != null))
}

/**
 * Where [owner]/[repo] is looked for, in order (ADR 0076): on the [origin]'s host if it is not github.com (GitHub
 * Enterprise Server, which may also serve github.com's actions through GitHub Connect), then on github.com. Each is
 * reached as the origin is, if it is that host (ssh with its user and port, or https with its port), else by https.
 * Owner and name are lower-cased, as GitHub ignores their case, so that each repository has one cache folder.
 */
internal fun remoteRepositories(
    origin: OriginHost?,
    owner: String,
    repo: String,
): List<RemoteRepository> {
    val own = origin?.takeIf { it.hostname != GITHUB }
    val github = origin?.takeIf { it.hostname == GITHUB } ?: OriginHost(GITHUB)
    return listOfNotNull(own, github).map { repository(it, owner.lowercase(), repo.lowercase()) }
}

private fun repository(
    host: OriginHost,
    owner: String,
    name: String,
): RemoteRepository {
    val ssh = host.ssh
    val path = "$owner/$name.git"
    return when {
        ssh == null -> {
            val address = host.hostname + host.port?.let { ":$it" }.orEmpty()
            RemoteRepository(address, owner, name, "https://$address/$path")
        }

        ssh.scp -> {
            RemoteRepository(host.hostname, owner, name, "${ssh.user}@${host.hostname}:$path")
        }

        else -> {
            val login = ssh.user?.let { "$it@" }.orEmpty()
            val port = ssh.port?.let { ":$it" }.orEmpty()
            RemoteRepository(host.hostname, owner, name, "ssh://$login${host.hostname}$port/$path")
        }
    }
}
