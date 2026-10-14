package dev.sluide.httpclient.auth

import java.util.Base64

/** Basic auth as written in `.http` files. */
internal object BasicAuth {
    private val BASIC = Regex("""Basic\s+(\S+)(?:\s+(.*))?""", RegexOption.IGNORE_CASE)

    /**
     * An `Authorization` value with `Basic user password` or `Basic user:password` turned into
     * `Basic base64(user:password)`. A single token without colon is taken as already encoded; other values are kept.
     */
    fun normalize(value: String): String {
        val match = BASIC.matchEntire(value.trim()) ?: return value
        val (first, password) = match.destructured
        val credentials =
            when {
                password.isNotEmpty() -> "$first:$password"
                ':' in first -> first
                else -> return value
            }
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    }
}
