package dev.sluide.codeintelligence

import dev.sluide.languageservers.LanguageServerSession
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How long a barrier or a request future of these tests waits before it fails. */
internal const val BARRIER_TIMEOUT_SECONDS = 10L

/**
 * Client to server barrier: waits until the server handled everything the client sent before. One more request,
 * answered in order on the same stream, then the FX thread ([onFx], which runs its argument there) so that the
 * answers sent before have run. For checks that nothing was sent and for answers that must have arrived.
 */
internal fun awaitServerHandled(
    session: LanguageServerSession,
    onFx: (() -> Unit) -> Unit,
) {
    val answered = CountDownLatch(1)
    // A method lsp4j does not know: the fake records it in customMessages and answers what its customAnswer
    // returns (null by default). A test whose customAnswer catches every method answers only its own.
    onFx { session.requestJson("test/idle", null, { answered.countDown() }, { answered.countDown() }) }
    check(answered.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "client-to-server barrier: the server did not answer test/idle"
    }
    onFx {}
}

/**
 * Server to client barrier: waits until the client handled everything the server sent before (published
 * diagnostics, answers). The server's own request is answered after the client processed the messages before it on
 * the same stream; then the FX thread ([onFx]) runs what that handling handed to it.
 */
internal fun awaitClientHandled(
    client: LanguageClient,
    onFx: (() -> Unit) -> Unit,
) {
    client.refreshDiagnostics().awaitReply("server-to-client barrier (refreshDiagnostics)")
    onFx {}
}

/** The result of this request future, failing with [what] when it does not arrive in time. */
internal fun <T> CompletableFuture<T>.awaitReply(what: String = "the reply"): T =
    try {
        get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        throw IllegalStateException("timed out waiting for $what", e)
    }
