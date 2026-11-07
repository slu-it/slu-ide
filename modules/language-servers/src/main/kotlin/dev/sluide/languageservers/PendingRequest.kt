package dev.sluide.languageservers

import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * A request sent through [LanguageServerSession.request]. [cancel] drops its result, also one already on its way to
 * the FX thread, and tells the server (`$/cancelRequest`) if it was sent.
 */
class PendingRequest internal constructor() {
    private var future: CompletableFuture<*>? = null
    private var cancelled = false

    /** Whether [cancel] was called. */
    val isCancelled: Boolean get() = synchronized(this) { cancelled }

    /** Drops the result; any thread. */
    fun cancel() {
        val sent =
            synchronized(this) {
                if (cancelled) return
                cancelled = true
                future
            }
        // lsp4j's request future sends $/cancelRequest when cancelled.
        sent?.cancel(true)
    }

    /** Keeps the future of the sent request; `false` (and cancels it) if this was cancelled before. */
    internal fun sent(sentFuture: CompletableFuture<*>): Boolean {
        synchronized(this) {
            if (!cancelled) {
                future = sentFuture
                return true
            }
        }
        sentFuture.cancel(true)
        return false
    }
}

/**
 * Latest wins, for requests a keystroke or a caret move starts (completion, highlights, signature help): sending
 * cancels the request sent before, so only the newest result arrives.
 */
class RequestSlot {
    private var current: PendingRequest? = null

    /** Cancels the previous request of this slot and sends a new one, as [LanguageServerSession.request]. */
    fun <T> send(
        session: LanguageServerSession,
        call: (LanguageServer) -> CompletableFuture<T>,
        onResult: (T) -> Unit,
        onError: (Throwable) -> Unit = session.logRequestError,
    ): PendingRequest {
        cancel()
        val request = session.request(call, onResult, onError)
        synchronized(this) { current = request }
        return request
    }

    /** Cancels the request of this slot, if any. */
    fun cancel() {
        val previous =
            synchronized(this) {
                val previous = current
                current = null
                previous
            }
        previous?.cancel()
    }
}
