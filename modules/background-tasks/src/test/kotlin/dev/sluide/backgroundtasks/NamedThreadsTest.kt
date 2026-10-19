package dev.sluide.backgroundtasks

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NamedThreadsTest {
    private val reports = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private val original = NamedThreads.report

    private fun ExecutorService.finish() {
        shutdown()
        assertTrue(awaitTermination(5, TimeUnit.SECONDS))
    }

    @BeforeTest
    fun capture() {
        NamedThreads.report = { name, error -> reports += name to error }
    }

    @AfterTest
    fun restore() {
        NamedThreads.report = original
    }

    @Test
    fun `a throwing execute task is reported once and the thread runs the next task`() {
        val executor = NamedThreads.single("sluide-test")
        try {
            val boom = IllegalStateException("boom")
            executor.execute { throw boom }
            val next = executor.submit<String> { Thread.currentThread().name }
            assertEquals("sluide-test", next.get(5, TimeUnit.SECONDS))
            assertEquals(1, reports.size)
            assertEquals("sluide-test", reports[0].first)
            assertSame(boom, reports[0].second)
        } finally {
            executor.finish()
        }
    }

    @Test
    fun `a throwing submit is reported once and get throws ExecutionException`() {
        val executor = NamedThreads.single("sluide-test")
        try {
            val boom = IllegalStateException("boom")
            val future = executor.submit { throw boom }
            val e = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
            assertSame(boom, e.cause)
            executor.submit {}.get(5, TimeUnit.SECONDS)
        } finally {
            executor.finish()
        }
        assertEquals(1, reports.size)
    }

    @Test
    fun `a cancelled submit is not reported`() {
        val executor = NamedThreads.single("sluide-test")
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.execute {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            executor.submit {}.cancel(false)
            release.countDown()
        } finally {
            executor.finish()
        }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a throwing schedule is reported`() {
        val executor = NamedThreads.scheduled("sluide-sched")
        try {
            val boom = IllegalStateException("boom")
            val future = executor.schedule({ throw boom }, 0, TimeUnit.MILLISECONDS)
            assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.finish()
        }
        assertEquals(1, reports.size)
        assertEquals("sluide-sched", reports[0].first)
    }

    @Test
    fun `a throwing periodic task is reported once and stops`() {
        val executor = NamedThreads.scheduled("sluide-sched")
        val runs = AtomicInteger()
        try {
            val future =
                executor.scheduleAtFixedRate({
                    runs.incrementAndGet()
                    error("boom")
                }, 0, 10, TimeUnit.MILLISECONDS)
            assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }
            Thread.sleep(100)
        } finally {
            executor.finish()
        }
        assertEquals(1, runs.get())
        assertEquals(1, reports.size)
    }

    @Test
    fun `a cancelled periodic task is not reported`() {
        val executor = NamedThreads.scheduled("sluide-sched")
        try {
            val ran = CountDownLatch(1)
            val future = executor.scheduleAtFixedRate({ ran.countDown() }, 0, 10, TimeUnit.MILLISECONDS)
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            future.cancel(false)
            Thread.sleep(50)
        } finally {
            executor.finish()
        }
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `execute on the scheduled executor reports a throwing task once`() {
        val executor = NamedThreads.scheduled("sluide-sched")
        try {
            executor.execute { error("boom") }
            executor.submit {}.get(5, TimeUnit.SECONDS)
        } finally {
            executor.finish()
        }
        assertEquals(1, reports.size)
    }

    @Test
    fun `an idle single executor ends its thread after the keep-alive`() {
        val executor = NamedThreads.single("sluide-idle", 50)
        try {
            var thread: Thread? = null
            executor.submit { thread = Thread.currentThread() }.get(5, TimeUnit.SECONDS)
            val worker = checkNotNull(thread)
            worker.join(5000)
            assertTrue(!worker.isAlive)
            assertEquals(
                "sluide-idle",
                executor.submit<String> { Thread.currentThread().name }.get(5, TimeUnit.SECONDS),
            )
        } finally {
            executor.finish()
        }
    }

    @Test
    fun `virtual reports a failure and the thread has the name`() {
        val boom = IllegalStateException("boom")
        val thread = NamedThreads.virtual("sluide-virtual") { throw boom }
        thread.join(5000)
        assertEquals("sluide-virtual", thread.name)
        assertEquals(1, reports.size)
        assertEquals("sluide-virtual", reports[0].first)
        assertSame(boom, reports[0].second)
    }

    @Test
    fun `the threads are daemon and named`() {
        val single = NamedThreads.single("sluide-a")
        val scheduled = NamedThreads.scheduled("sluide-b")
        try {
            val a = single.submit<Pair<String, Boolean>> { Thread.currentThread().let { it.name to it.isDaemon } }
            val b = scheduled.submit<Pair<String, Boolean>> { Thread.currentThread().let { it.name to it.isDaemon } }
            assertEquals("sluide-a" to true, a.get(5, TimeUnit.SECONDS))
            assertEquals("sluide-b" to true, b.get(5, TimeUnit.SECONDS))
        } finally {
            single.finish()
            scheduled.finish()
        }
    }
}
