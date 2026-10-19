package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.OAuth2TokenCache
import dev.sluide.httpclient.auth.RequestTokens
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.RequestBlock
import dev.sluide.httpclient.vars.DynamicVariables
import dev.sluide.httpclient.vars.VariableResolver
import java.nio.file.Path

/**
 * The one entry point for running a request of a `.http` file. [run] returns at once: resolving variables, token
 * requests, building and sending all happen on the [executor]'s threads, never on the caller's (the FX) thread, and
 * the result comes back through the executor's `dispatch`. The environment's `SSLConfiguration` (client certificate,
 * host verification) applies to the request and its token requests; [tokens] should use the same [sslContexts].
 */
internal class RequestRunner(
    private val executor: RequestExecutor,
    private val tokens: OAuth2TokenCache,
    private val sslContexts: SslContexts,
    private val dynamic: DynamicVariables = DynamicVariables(),
) {
    /**
     * Runs [request], a block of [fileText] (the current, possibly unsaved text of [httpFile]), with the variables
     * of [environment] (null for none) and of the file. Token requests show up in the result's notes; an unresolved
     * variable, a failed token request or an invalid request gives an [HttpFailure] without sending anything.
     */
    fun run(
        httpFile: Path,
        fileText: String,
        request: RequestBlock,
        environment: Environment?,
        onResult: (ExchangeResult) -> Unit,
    ): Cancellable = run(httpFile, fileText, request, { environment }, onResult)

    /** Like the other [run], with the [environment] read on the worker, e.g. from the environment files. */
    fun run(
        httpFile: Path,
        fileText: String,
        request: RequestBlock,
        environment: () -> Environment?,
        onResult: (ExchangeResult) -> Unit,
    ): Cancellable =
        executor.run(request.tags, onResult, request.name) { notes ->
            val environment = environment()
            val variables = HttpFileParser.parse(fileText).variables
            val auth = RequestTokens(tokens, environment, notes::add)
            val resolver = VariableResolver(environment, variables, dynamic, auth::token)
            val ssl = environment?.ssl?.let(sslContexts::context)
            RequestBuilder(httpFile, resolver).build(request).withSslContext(ssl).withSecrets(auth.taken)
        }
}
