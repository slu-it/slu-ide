package dev.sluide.httpclient.auth

import dev.sluide.httpclient.env.Environment

/**
 * The `$auth.token("id")` and `$auth.idToken("id")` values of one request, for `VariableResolver.auth`. Each is taken
 * from [cache] when the resolver first meets it (also behind variables), blocking during token requests, and then
 * stays the same for the request. Failures throw [OAuth2Exception] out of the resolver.
 */
internal class RequestTokens(
    private val cache: OAuth2TokenCache,
    private val environment: Environment?,
    private val note: (String) -> Unit = {},
) {
    private val values = HashMap<Pair<String, Boolean>, String>()

    fun token(
        id: String,
        idToken: Boolean,
    ): String = values.getOrPut(id to idToken) { cache.token(environment, id, idToken, note) }

    /** The tokens taken so far. */
    val taken: List<String> get() = values.values.toList()
}
