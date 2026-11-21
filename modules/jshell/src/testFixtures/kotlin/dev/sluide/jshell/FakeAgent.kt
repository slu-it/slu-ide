package dev.sluide.jshell

import dev.sluide.jshell.agent.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An agent process that replays a recording (README in `src/testFixtures/resources/jshell/`): it sends the recording's `out`
 * lines up to its first `in` at once, and for each request the session sends, which must match the next `in` line
 * (all but its `id`), the `out` lines up to the following `in`, with the session's ids in place of the recorded ones.
 * As the real agent does, it sends `ended closed` only after its input ended, and the end of its input answers like
 * `close` (stdin-eof.jsonl). Once the recording is used up its output ends, as the agent's does when it exits
 * (system-exit.jsonl) or dies (killed.jsonl). Requests that do not match go to [problems].
 *
 * [exits] false: the recording used up, the fake still runs (an agent that never gets ready or hangs).
 * [endsWithInput] false: it ignores the end of its input, so only [kill] ends it.
 * [lateStderr] arrives on standard error 200 ms after the agent ended, as the tail of a pipe can.
 */
class FakeAgent(
    private val lines: List<RecordedLine>,
    private val stderrText: String = "",
    private val exits: Boolean = true,
    private val endsWithInput: Boolean = true,
    private val lateStderr: String = "",
) : AgentProcess {
    companion object {
        /** The recording [name], only the lines [only] (1-based numbers) if given. */
        fun of(
            name: String,
            only: Collection<Int>? = null,
            stderrText: String = "",
            endsWithInput: Boolean = true,
            lateStderr: String = "",
        ) = FakeAgent(
            Recordings.load(name).filter { only == null || it.number in only },
            stderrText,
            endsWithInput = endsWithInput,
            lateStderr = lateStderr,
        )

        fun stderrOf(name: String): String =
            String(FakeAgent::class.java.getResourceAsStream("/jshell/$name")!!.readAllBytes(), Charsets.UTF_8)
    }

    private val output = ChunkedInput()
    private val errors = ChunkedInput()
    private val ended = CountDownLatch(1)
    private var position = 0
    private var inputEnded = false
    private val ids = HashMap<Any?, Any?>()

    val problems = CopyOnWriteArrayList<String>()

    @Volatile var killed = false
        private set

    /** Whether every request before the final `close` came and was answered: the next recorded line is `close`. */
    @Synchronized
    fun awaitsClose(): Boolean = lines.getOrNull(position)?.let { it.incoming && it.message["op"] == "close" } == true

    /** Writes to the agent's input fail from now on, as to a pipe whose reader is gone. */
    @Volatile var inputBroken = false

    private var holding = false
    private val held = StringBuilder()
    private var exitHeld = false

    /** Keeps what the agent sends (and its end) back until [release]. */
    @Synchronized
    fun hold() {
        holding = true
    }

    @Synchronized
    fun release() {
        holding = false
        if (held.isNotEmpty()) output.feed(held.toString())
        held.clear()
        if (exitHeld) exit()
    }

    /** Makes the next read of the agent's output fail. */
    fun breakOutput() = output.breakOff()

    override val stdin: OutputStream = Requests()
    override val stdout: InputStream = output
    override val stderr: InputStream = errors

    init {
        if (stderrText.isNotEmpty()) errors.feed(stderrText)
        pump()
    }

    override fun waitFor(millis: Long): Boolean = ended.await(millis, TimeUnit.MILLISECONDS)

    override fun kill() {
        killed = true
        exit()
    }

    @Synchronized
    private fun request(line: String) {
        val request = Json.parse(line)
        val expected = lines.getOrNull(position)
        if (expected == null || !expected.incoming || expected.message - "id" != request - "id") {
            problems += "unexpected request $line, expected ${expected?.message}"
            return
        }
        ids[expected.message["id"]] = request["id"]
        position++
        pump()
    }

    @Synchronized
    private fun inputEnd() {
        inputEnded = true
        if (!endsWithInput) return
        if (lines.getOrNull(position)?.let { it.incoming && it.message["op"] == "close" } == true) position++
        pump()
        exit()
    }

    /** Sends the `out` lines up to the next `in` line, or up to `ended closed` while the input is open. */
    @Synchronized
    private fun pump() {
        val batch = StringBuilder()
        while (position < lines.size && !lines[position].incoming) {
            val message = lines[position].message
            if (message["event"] == "ended" && message["reason"] == "closed" && !inputEnded) break
            val id = message["id"]
            val answer = if ("id" in message && id != null) message + ("id" to ids.getOrDefault(id, id)) else message
            batch.append(Json.write(answer)).append('\n')
            position++
        }
        if (holding) {
            held.append(batch)
        } else if (batch.isNotEmpty()) {
            output.feed(batch.toString())
        }
        if (position >= lines.size && exits) exit()
    }

    @Synchronized
    private fun exit() {
        if (ended.count == 0L) return
        if (holding) {
            exitHeld = true
            return
        }
        output.end()
        if (lateStderr.isEmpty()) {
            errors.end()
        } else {
            Thread {
                // the delay is the point: the tail comes after the output ended
                Thread.sleep(200)
                errors.feed(lateStderr)
                errors.end()
            }.start()
        }
        ended.countDown()
    }

    private inner class Requests : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (inputBroken) throw IOException("Broken pipe")
            if (b == '\n'.code) {
                request(line.toString(Charsets.UTF_8))
                line.reset()
            } else {
                line.write(b)
            }
        }

        override fun close() = inputEnd()
    }
}
