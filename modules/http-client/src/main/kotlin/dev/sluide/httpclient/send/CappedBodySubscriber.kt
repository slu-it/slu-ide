package dev.sluide.httpclient.send

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** A response body of at most the limit of its [CappedBodySubscriber]; [truncated] if more was sent. */
internal class CappedBody(
    val bytes: ByteArray,
    val truncated: Boolean,
)

/** Collects up to [limit] bytes, then stops reading (which ends the exchange) and marks the body truncated. */
internal class CappedBodySubscriber(
    private val limit: Int,
) : HttpResponse.BodySubscriber<CappedBody> {
    private val body = CompletableFuture<CappedBody>()
    private val out = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<CappedBody> = body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (body.isDone) return
        for (buffer in item) {
            val count = minOf(limit - out.size(), buffer.remaining())
            val bytes = ByteArray(count)
            buffer.get(bytes)
            out.write(bytes)
            if (buffer.hasRemaining()) {
                body.complete(CappedBody(out.toByteArray(), truncated = true))
                subscription?.cancel()
                return
            }
        }
    }

    override fun onError(throwable: Throwable) {
        body.completeExceptionally(throwable)
    }

    override fun onComplete() {
        body.complete(CappedBody(out.toByteArray(), truncated = false))
    }
}
