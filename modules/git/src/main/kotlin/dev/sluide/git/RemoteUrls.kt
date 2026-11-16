package dev.sluide.git

private const val MASK = "****"
private const val UNKNOWN = "Unknown location"
private const val LOCAL = "Local repository"
private val MASKED_SCHEMES = setOf("http", "https", "ftp", "ftps")
private val SSH_SCHEMES = setOf("ssh", "git+ssh", "ssh+git")
private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
private val TRANSPORT = Regex("^[A-Za-z0-9+.-]+::")
private val BRACKET_SCP = Regex("^(?:[^/@\\[]*@)?(\\[[^\\]]+\\]):")
private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/]")

/** A URL with a scheme, cut into what comes before the userinfo, the userinfo and the rest (from the host on). */
internal class UrlParts(
    val scheme: String,
    val head: String,
    val userinfo: String?,
    val rest: String,
)

/**
 * Cuts a `<scheme>://` URL. The authority ends at the first `/`; its userinfo is everything before the last `@` in it
 * (a password may hold an unescaped `@`), so nothing of it stays in the rest. `null` for anything else; an empty
 * userinfo counts as none.
 */
internal fun urlParts(url: String): UrlParts? {
    val scheme = SCHEME.find(url) ?: return null
    val start = scheme.value.length
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', authorityEnd - 1)
    val head = url.substring(0, start)
    return if (at < start) {
        UrlParts(scheme.groupValues[1], head, null, url.substring(start))
    } else {
        UrlParts(scheme.groupValues[1], head, url.substring(start, at).ifEmpty { null }, url.substring(at + 1))
    }
}

/** [argument] with the userinfo of a scheme URL replaced by [replacement]; anything else unchanged. */
internal fun withoutUserinfo(
    argument: String,
    replacement: String,
): String =
    urlParts(argument)?.takeIf { it.userinfo != null }?.let { it.head + replacement + "@" + it.rest } ?: argument

/**
 * Where the remote at [url] is, for a tooltip: scheme and host only (`https://github.com`, `ssh github.com` for `ssh://`
 * and scp-like `git@github.com:x/y`; an IPv6 host keeps its brackets), never the user, password, port, path or query.
 * A local path or `file://` is "Local repository"; `ext::…` and anything else unparseable is "Unknown location",
 * never the raw text.
 */
fun remoteLocation(url: String): String {
    val text = url.trim()
    if (text.isEmpty() || TRANSPORT.containsMatchIn(text)) return UNKNOWN
    urlParts(text)?.let { parts ->
        val scheme = parts.scheme.lowercase()
        if (scheme == "file") return LOCAL
        val authority =
            parts.rest
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        val host = hostOf(authority, allowColon = true) ?: return UNKNOWN
        return if (scheme in SSH_SCHEMES) "ssh $host" else "$scheme://$host"
    }
    if (WINDOWS_PATH.containsMatchIn(text)) return LOCAL
    // scp-like `[user@]host:path`; a bracketed IPv6 host holds colons itself.
    BRACKET_SCP.find(text)?.let { return "ssh ${it.groupValues[1]}" }
    val colon = text.indexOf(':')
    if (colon < 0 || '/' in text.substring(0, colon)) return LOCAL
    val host = hostOf(text.substring(0, colon), allowColon = false) ?: return UNKNOWN
    return "ssh $host"
}

/** The host of [authority] without userinfo and port; `null` if empty or not a plausible host. */
private fun hostOf(
    authority: String,
    allowColon: Boolean,
): String? {
    val noUser = authority.substringAfterLast('@')
    val host =
        when {
            noUser.startsWith('[') -> noUser.substringBefore(']', "").let { if (it.isEmpty()) "" else "$it]" }
            allowColon -> noUser.substringBefore(':')
            else -> noUser
        }
    return host.takeIf { it.isNotEmpty() && host.none { c -> c.isWhitespace() || c.isISOControl() } }
}

/**
 * [url] with the userinfo of a scheme URL shown as `****` when the scheme is http, https, ftp or ftps (a token can be
 * the user name) or the userinfo has a `:` (a password); `ssh://git@host/x` and scp-like `git@host:x` stay as they are.
 */
fun maskedUrl(url: String): String {
    val parts = urlParts(url) ?: return url
    val userinfo = parts.userinfo ?: return url
    val masked = parts.scheme.lowercase() in MASKED_SCHEMES || ':' in userinfo
    return if (masked) parts.head + MASK + "@" + parts.rest else url
}

/**
 * [edited], a [maskedUrl] the user changed: where its userinfo is still exactly the mask and [original] had a masked
 * one, the userinfo of [original] is put back; otherwise [edited] as written.
 */
fun unmaskedUrl(
    edited: String,
    original: String,
): String {
    val parts = urlParts(edited) ?: return edited
    if (parts.userinfo != MASK) return edited
    val before = urlParts(original) ?: return edited
    val userinfo = before.userinfo ?: return edited
    if (maskedUrl(original) == original) return edited
    return parts.head + userinfo + "@" + parts.rest
}
