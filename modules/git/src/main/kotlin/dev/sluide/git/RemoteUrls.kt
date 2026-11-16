package dev.sluide.git

private const val MASK = "****"
private const val UNKNOWN = "Unknown location"
private const val LOCAL = "Local repository"
private val MASKED_SCHEMES = setOf("http", "https", "ftp", "ftps")
private val SSH_SCHEMES = setOf("ssh", "git+ssh", "ssh+git")
private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
private val TRANSPORT = Regex("^[A-Za-z0-9+.-]+::")
private val BRACKET_SCP = Regex("^(?:([^/@\\[]*)@)?\\[([^\\]]+)]:")
private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/]")
private val URL_IN_TEXT = Regex("([A-Za-z][A-Za-z0-9+.-]{0,30}://)[^/?#\\s\"]+@")
private val BRACKET_IN_TEXT = Regex("\\[([^\\]\\s]+)@([^\\]\\s@]*)]:")

/** A URL with a scheme, cut into what comes before the userinfo, the userinfo and the rest (from the host on). */
internal class UrlParts(
    val scheme: String,
    val head: String,
    val userinfo: String?,
    val rest: String,
)

/**
 * Cuts a `<scheme>://` URL. The authority ends at the first `/`, `?` or `#`; its userinfo is everything before the
 * last `@` in it (a password may hold an unescaped `@`), so nothing of it stays in the rest. `null` for anything else;
 * an empty userinfo counts as none.
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

/**
 * The userinfo of a URL: the text [before] it, [after] it, and a [key] naming where it is valid (scheme, host and
 * port, lower case), so that a userinfo is only put back to the same place.
 */
private class Located(
    val before: String,
    val userinfo: String,
    val after: String,
    val key: String,
    val scheme: String?,
) {
    /** Whether [maskedUrl] hides it: a token can be the user name of an http-like URL; a `:` means a password. */
    val secret get() = scheme in MASKED_SCHEMES || ':' in userinfo
}

private fun authorityOf(rest: String) = rest.substringBefore('/').substringBefore('?').substringBefore('#')

/**
 * Finds the userinfo of a `<scheme>://` URL, also after a `<transport>::` prefix, and of a bracketed scp-like
 * address (`[user:secret@host:22]:path`, `user:secret@[host]:path`); `null` if there is none.
 */
private fun locate(url: String): Located? {
    TRANSPORT.find(url)?.let { transport ->
        val inner = locate(url.substring(transport.value.length)) ?: return null
        val prefix = transport.value
        return Located(prefix + inner.before, inner.userinfo, inner.after, prefix.lowercase() + inner.key, inner.scheme)
    }
    urlParts(url)?.let { parts ->
        val userinfo = parts.userinfo ?: return null
        val scheme = parts.scheme.lowercase()
        val key = scheme + "|" + authorityOf(parts.rest).lowercase()
        return Located(parts.head, userinfo, "@" + parts.rest, key, scheme)
    }
    val match = BRACKET_SCP.find(url) ?: return null
    val outer = match.groups[1]
    val inside = match.groups[2] ?: return null
    val key = "[" + inside.value.substringAfterLast('@').lowercase()
    if (outer != null) return Located("", outer.value, url.substring(outer.range.last + 1), key, null)
    if ('@' !in inside.value) return null
    val userinfo = inside.value.substringBeforeLast('@')
    val before = url.substring(0, inside.range.first)
    return Located(before, userinfo, url.substring(inside.range.first + userinfo.length), key, null)
}

/**
 * [argument] with the userinfo of a scheme URL (also after a `<transport>::`) replaced by [replacement], trimmed; an
 * scp-like address only where [maskedUrl] hides it; anything else only trimmed.
 */
internal fun withoutUserinfo(
    argument: String,
    replacement: String,
): String {
    val text = argument.trim()
    val located = locate(text) ?: return text
    // Like maskedUrl, an scp-like address keeps a plain user name (git@host).
    if (located.scheme == null && !located.secret) return text
    return located.before + replacement + located.after
}

/**
 * [text], git's message or any other, with the userinfo of every `<scheme>://<userinfo>@` in it replaced by
 * [replacement]; the userinfo runs to the last `@` before the authority ends (a `/`, `?`, `#`, `"` or white space; an
 * apostrophe is legal in userinfo and git prints URLs inside its own). Also the userinfo with a `:` of a bracketed
 * scp-like address (`[user:secret@host:22]:path`).
 */
internal fun withoutUserinfoIn(
    text: String,
    replacement: String,
): String =
    BRACKET_IN_TEXT.replace(URL_IN_TEXT.replace(text) { it.groupValues[1] + replacement + "@" }) {
        if (':' in it.groupValues[1]) "[$replacement@${it.groupValues[2]}]:" else it.value
    }

/**
 * Where the remote at [url] is, for a tooltip: scheme and host only (`https://github.com`, `ssh github.com` for
 * `ssh://` and scp-like `git@github.com:x/y`; an IPv6 host keeps its brackets), never the user, password, port, path
 * or query.
 * A local path or `file://` is "Local repository"; `ext::…` and anything else unparseable is "Unknown location",
 * never the raw text.
 */
fun remoteLocation(url: String): String {
    val text = url.trim()
    if (text.isEmpty() || TRANSPORT.containsMatchIn(text)) return UNKNOWN
    urlParts(text)?.let { parts ->
        val scheme = parts.scheme.lowercase()
        if (scheme == "file") return LOCAL
        val host = hostOf(authorityOf(parts.rest), allowColon = true) ?: return UNKNOWN
        return if (scheme in SSH_SCHEMES) "ssh $host" else "$scheme://$host"
    }
    if (WINDOWS_PATH.containsMatchIn(text)) return LOCAL
    // scp-like `[user@]host:path`; git also takes `[user@host:port]:path`, and an IPv6 host holds colons itself.
    BRACKET_SCP.find(text)?.let { match ->
        val inside = match.groupValues[2].substringAfterLast('@')
        val host = if (inside.count { it == ':' } > 1) "[$inside]" else inside.substringBefore(':')
        return if (host.isEmpty()) UNKNOWN else "ssh $host"
    }
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
 * [url], trimmed, with the userinfo shown as `****` when it is of a http, https, ftp or ftps URL (a token can be the
 * user name) or has a `:` (a password); also behind a `<transport>::` and in the bracketed scp-like form
 * `[user:secret@host:22]:path`. `ssh://git@host/x` and scp-like `git@host:x` stay as they are.
 */
fun maskedUrl(url: String): String {
    val text = url.trim()
    val located = locate(text) ?: return text
    return if (located.secret) located.before + MASK + located.after else text
}

/**
 * [edited], a [maskedUrl] the user changed: where its userinfo is still exactly the mask and [original] had a masked
 * one, the userinfo of [original] is put back, but only if scheme, host and port are unchanged; with a changed one
 * the credentials do not belong there and the result is `null` (ask for them again). Without the mask, or with an
 * [original] that had none, [edited] as written (trimmed).
 */
fun unmaskedUrl(
    edited: String,
    original: String,
): String? {
    val text = edited.trim()
    val now = locate(text)
    if (now == null || now.userinfo != MASK) return text
    val before = locate(original.trim())
    if (before == null || !before.secret) return text
    if (now.key != before.key) return null
    return now.before + before.userinfo + now.after
}
