package dev.sluide.httpclient

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.httpclient.auth.OAuth2Client
import dev.sluide.httpclient.auth.OAuth2TokenCache
import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.send.HttpClients
import dev.sluide.httpclient.send.RequestExecutor
import dev.sluide.httpclient.send.RequestRunner
import dev.sluide.httpclient.send.SslContexts
import dev.sluide.httpclient.ui.PassphrasePrompt
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val log = System.getLogger(HttpClientSession::class.java.name)

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
    tasks: BackgroundTasks,
    private val dispatch: (Runnable) -> Unit,
) : AutoCloseable {
    /** Requests and token requests are tasks of the window's [tasks] (ADR 0061). */
    @Autowired
    constructor(tasks: BackgroundTasks) : this(tasks, Platform::runLater)

    internal val clients = HttpClients()
    internal val executor = RequestExecutor(clients, tasks, dispatch = dispatch)

    /**
     * Asks for the passphrase of a client certificate's key; `null` if the user cancelled. Called on a worker thread,
     * which waits for the answer.
     */
    internal var passphrasePrompt: (SslConfig) -> CharArray? = PassphrasePrompt()::ask

    internal val sslContexts = SslContexts(passphrase = { passphrasePrompt(it) }, discarded = clients::evict)
    internal val tokens = OAuth2TokenCache(OAuth2Client(clients), sslContexts, tasks)
    internal val runner = RequestRunner(executor, tokens, sslContexts)

    /**
     * Whether git could commit a file (it is in the repository and not ignored), e.g. a private environment file with
     * secrets; set by the window. Asked on the FX thread.
     */
    var wouldCommit: (Path) -> Boolean = { false }

    private val gitListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Tells the views that the window's git status changed, so they ask [wouldCommit] again. Call on the FX thread. */
    fun gitStatusChanged() = gitListeners.forEach { it() }

    /** Calls [listener] on each [gitStatusChanged] until the returned handle is closed. */
    internal fun onGitStatusChanged(listener: () -> Unit): AutoCloseable {
        gitListeners += listener
        return AutoCloseable { gitListeners -= listener }
    }

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

    /**
     * Runs [task] on a virtual thread, e.g. formatting a response or a token request, and hands its outcome to [onDone]
     * through [dispatch]; returns the thread, which an interrupt stops. Whatever [task] throws is part of the outcome
     * and left to [onDone] to log, so that [onDone] is always called; an `Error` is also logged here (ADR 0060).
     */
    internal fun <T> inBackground(
        task: () -> T,
        onDone: (Result<T>) -> Unit,
    ): Thread =
        NamedThreads.virtual("sluide-http") {
            val result =
                try {
                    Result.success(task())
                } catch (e: Throwable) {
                    if (e !is Exception) log.log(System.Logger.Level.ERROR, "A background task failed with an error", e)
                    Result.failure(e)
                }
            dispatch { onDone(result) }
        }

    /** Aborts running requests and forgets cookies, tokens and passphrases. */
    override fun close() {
        executor.close()
        clients.close()
        tokens.clearAll()
        sslContexts.clear()
    }
}
