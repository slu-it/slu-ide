package dev.sluide.jshell.agent

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * An [AgentServer] in the test JVM (its snippets run in a real execution JVM, as in the agent process): requests go in
 * as protocol lines, the messages it sends come out parsed again, and its exit calls are only recorded.
 */
internal class AgentHarness(
    stopRetryMillis: Long = 100,
    /** The first message it matches is not sent: the send throws, as a broken output would. */
    failing: (Map<String, Any?>) -> Boolean = { false },
) : AutoCloseable {
    private var failed = false
    private val messages = LinkedBlockingQueue<Map<String, Any?>>()
    private val input = LinkedBlockingQueue<String>()
    val exits = LinkedBlockingQueue<Int>()
    private val server =
        AgentServer(
            send = {
                if (!failed && failing(it)) {
                    failed = true
                    throw IllegalStateException("send failed")
                }
                messages.put(Json.parse(Json.write(it)))
            },
            exit = { exits.put(it) },
            stopRetryMillis,
        )
    private val serving =
        Thread({
            server.start()
            server.serve { input.take().takeIf { it !== END } }
        }, "agent-harness").apply {
            isDaemon = true
            start()
        }

    fun send(request: Map<String, Any?>) = input.put(Json.write(request))

    fun sendLine(line: String) = input.put(line)

    fun endInput() = input.put(END)

    /** The next message; fails when none came within 30 s or the server stopped serving without one. */
    fun next(): Map<String, Any?> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            messages.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
            if (!serving.isAlive && messages.isEmpty()) fail("the agent stopped serving without another message")
            if (System.nanoTime() > deadline) fail("no message from the agent within 30 s")
        }
    }

    /** Messages sent so far and not taken. */
    fun left(): List<Map<String, Any?>> = generateSequence { messages.poll() }.toList()

    /** Ends the input and waits until the server stopped serving (it closed its JShell then). */
    override fun close() {
        endInput()
        serving.join(30_000)
    }

    private companion object {
        val END = String(charArrayOf('\u0000'))
    }
}
