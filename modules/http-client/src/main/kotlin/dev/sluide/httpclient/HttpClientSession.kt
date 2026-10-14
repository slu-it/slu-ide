package dev.sluide.httpclient

import dev.sluide.httpclient.auth.OAuth2Client
import dev.sluide.httpclient.auth.OAuth2TokenCache
import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.RequestExecutor
import dev.sluide.httpclient.send.RequestRunner
import dev.sluide.httpclient.send.SslContexts
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What the HTTP client keeps per IDE window (ADR 0026): the HTTP clients with their cookie jar, the OAuth2 tokens,
 * the client certificates with their passphrases and the environment chosen for each `.http` file. Views of `.http`
 * files find it through `FileViewHost.windowService`. Everything is in memory only and ends with the window
 * ([close]).
 *
 * Results of requests reach the views through [dispatch] (`Platform.runLater`, replaced in tests).
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class HttpClientSession internal constructor(
    dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    @Autowired
    constructor() : this(Platform::runLater)

    internal val clients = HttpClients()
    internal val executor = RequestExecutor(clients, dispatch = dispatch)

    /**
     * Asks for the passphrase of a client certificate's key; `null` if the user cancelled. Called on a worker thread,
     * which waits for the answer.
     */
    internal var passphrasePrompt: (SslConfig) -> CharArray? = { null }

    internal val sslContexts = SslContexts(passphrase = { passphrasePrompt(it) }, discarded = clients::evict)
    internal val tokens = OAuth2TokenCache(OAuth2Client(clients), sslContexts)
    internal val runner = RequestRunner(executor, tokens, sslContexts)

    private val environments = ConcurrentHashMap<Path, String>()

    /** The environment chosen for [httpFile], `null` for none. */
    internal fun environment(httpFile: Path): String? = environments[key(httpFile)]

    /** Remembers [name] (`null`: no environment) as the environment of [httpFile] for this window. */
    internal fun chooseEnvironment(
        httpFile: Path,
        name: String?,
    ) {
        if (name == null) environments.remove(key(httpFile)) else environments[key(httpFile)] = name
    }

    /** Keeps the environment chosen for [from] for the file at [to], where it was moved (e.g. renamed). */
    internal fun moveEnvironment(
        from: Path,
        to: Path,
    ) {
        chooseEnvironment(to, environments.remove(key(from)))
    }

    private fun key(file: Path) = file.toAbsolutePath().normalize()

    /** Aborts running requests and forgets cookies, tokens and passphrases. */
    override fun close() {
        executor.close()
        clients.close()
        tokens.clearAll()
        sslContexts.clear()
    }
}
