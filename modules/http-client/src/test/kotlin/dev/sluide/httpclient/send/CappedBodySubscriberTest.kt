package dev.sluide.httpclient.send

import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CappedBodySubscriberTest {
    private class Recorder : Flow.Subscription {
        var requested = 0L
        var cancelled = false

        override fun request(n: Long) {
            requested += n
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private fun buffers(vararg parts: String) = parts.map { ByteBuffer.wrap(it.toByteArray()) }

    private fun subscriber(limit: Int): Pair<CappedBodySubscriber, Recorder> {
        val subscriber = CappedBodySubscriber(limit)
        val recorder = Recorder()
        subscriber.onSubscribe(recorder)
        return subscriber to recorder
    }

    @Test
    fun `asks for everything and keeps a body that stays within the limit`() {
        val (subscriber, recorder) = subscriber(10)

        subscriber.onNext(buffers("abc", "de"))
        subscriber.onNext(buffers("fghij"))
        subscriber.onComplete()

        val body = subscriber.body.toCompletableFuture().getNow(null)
        assertEquals(Long.MAX_VALUE, recorder.requested)
        assertEquals("abcdefghij", String(body.bytes))
        assertFalse(body.truncated)
        assertFalse(recorder.cancelled)
    }

    @Test
    fun `cuts the body at the limit, marks it truncated and cancels the exchange`() {
        val (subscriber, recorder) = subscriber(4)

        subscriber.onNext(buffers("ab", "cdef", "gh"))

        val body = subscriber.body.toCompletableFuture().getNow(null)
        assertEquals("abcd", String(body.bytes))
        assertTrue(body.truncated)
        assertTrue(recorder.cancelled)
    }

    @Test
    fun `keeps a body of exactly the limit and cuts one that has a byte more`() {
        val (exact, exactRecorder) = subscriber(3)
        exact.onNext(buffers("abc"))
        assertFalse(exact.body.toCompletableFuture().isDone)
        assertFalse(exactRecorder.cancelled)
        exact.onComplete()
        assertFalse(
            exact.body
                .toCompletableFuture()
                .getNow(null)
                .truncated,
        )

        val (more, moreRecorder) = subscriber(3)
        more.onNext(buffers("abcd"))
        assertEquals(
            "abc",
            String(
                more.body
                    .toCompletableFuture()
                    .getNow(null)
                    .bytes,
            ),
        )
        assertTrue(moreRecorder.cancelled)
    }

    @Test
    fun `keeps the first result when more data or the end arrives after the cut`() {
        val (subscriber, _) = subscriber(2)

        subscriber.onNext(buffers("abc"))
        subscriber.onNext(buffers("zzz"))
        subscriber.onComplete()

        val body = subscriber.body.toCompletableFuture().getNow(null)
        assertEquals("ab", String(body.bytes))
        assertTrue(body.truncated)
    }

    @Test
    fun `passes on an error of the exchange`() {
        val (subscriber, _) = subscriber(2)
        val failure = IllegalStateException("broken")

        subscriber.onError(failure)

        val thrown = assertFailsWith<ExecutionException> { subscriber.body.toCompletableFuture().get() }
        assertSame(failure, thrown.cause)
    }

    @Test
    fun `an empty body is an empty body that is not truncated`() {
        val (subscriber, _) = subscriber(0)

        subscriber.onComplete()

        val body = subscriber.body.toCompletableFuture().getNow(null)
        assertEquals(0, body.bytes.size)
        assertFalse(body.truncated)
    }
}
