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
 * the result comes back through the executor's `dispatch`.
 */
internal class RequestRunner(
    private val executor: RequestExecutor,
    private val tokens: OAuth2TokenCache,
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
    ): Cancellable =
        executor.run(request.tags, onResult) { notes ->
            val variables = HttpFileParser.parse(fileText).variables
            val auth = RequestTokens(tokens, environment, notes::add)
            val resolver = VariableResolver(environment, variables, dynamic, auth::token)
            RequestBuilder(httpFile, resolver).build(request)
        }
}
