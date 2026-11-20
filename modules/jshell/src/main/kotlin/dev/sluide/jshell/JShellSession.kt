package dev.sluide.jshell

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.Jdk
import dev.sluide.jshell.engine.ProtocolReader
import dev.sluide.jshell.engine.StderrTail
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

private val log = System.getLogger(JShellSession::class.java.name)

/**
 * A live JShell on a chosen JDK (ADR 0191): the agent process runs JShell there, its snippets in an execution JVM of
 * its own. One thread reads the agent's messages and calls the listeners, in order; requests are written by the
 * calling thread, one at a time.
 */
class JShellSession private constructor(
    private val process: AgentProcess,
    private val onSessionEvent: (SessionEvent) -> Unit,
    private val closeGraceMillis: Long,
) : AutoCloseable {
    companion object {
        internal const val START_TIMEOUT_MILLIS = 30_000L
        internal const val CLOSE_GRACE_MILLIS = 2_000L

        /** How long the tail of standard error may take to arrive once the agent's output ended. */
        private const val STDERR_END_MILLIS = 2_000L

        /**
         * Starts the agent on [jdk] and waits for `ready` (timeout 30 s). Blocks: worker thread only.
         * [onSessionEvent] gets output that arrives outside an evaluation and the end of the session.
         *
         * @throws JShellStartException if the agent cannot start, dies before `ready` or does not answer in time
         */
        fun start(
            jdk: Jdk,
            onSessionEvent: (SessionEvent) -> Unit,
            launcher: AgentLauncher = AgentLauncher.default(),
        ): JShellSession = start(jdk, onSessionEvent, launcher, START_TIMEOUT_MILLIS, CLOSE_GRACE_MILLIS)

        internal fun start(
            jdk: Jdk,
            onSessionEvent: (SessionEvent) -> Unit,
            launcher: AgentLauncher,
            startTimeoutMillis: Long,
            closeGraceMillis: Long,
        ): JShellSession {
            val where = "JDK ${jdk.major} (${jdk.home})"
            val process =
                try {
                    launcher.launch(jdk)
                } catch (e: IOException) {
                    throw JShellStartException("JShell could not start on $where: ${e.message}", "")
                }
            val session = JShellSession(process, onSessionEvent, closeGraceMillis)
            try {
                session.ready.get(startTimeoutMillis, TimeUnit.MILLISECONDS)
                return session
            } catch (_: TimeoutException) {
                process.kill()
                session.stderr.awaitEnd(STDERR_END_MILLIS)
                val seconds = BigDecimal.valueOf(startTimeoutMillis, 3).stripTrailingZeros().toPlainString()
                throw JShellStartException("JShell did not start within $seconds s on $where", session.stderr.text())
            } catch (_: ExecutionException) {
                // the agent's output ended before `ready`
                process.kill()
                session.stderr.awaitEnd(STDERR_END_MILLIS)
                throw JShellStartException("JShell could not start on $where", session.stderr.text())
            } catch (_: InterruptedException) {
                process.kill()
                Thread.currentThread().interrupt()
                throw JShellStartException("JShell's start on $where was interrupted", session.stderr.text())
            }
        }
    }

    private class Evaluation(
        val id: Long,
        val listener: (EvalEvent) -> Unit,
    )

    private val mapper = JsonMapper.builder().build()
    private val writer = BufferedWriter(OutputStreamWriter(process.stdin, Charsets.UTF_8))
    private val stderr = StderrTail()
    private val ready = CompletableFuture<Unit>()
    private val ids = AtomicLong()
    private val lock = Any()

    // guarded by lock
    private var evaluation: Evaluation? = null

    // guarded by lock
    private var ended = false

    @Volatile private var closing = false
    private val completions = ConcurrentHashMap<Long, CompletableFuture<Completions>>()

    /** The JDK's `java.version`, from `ready`. */
    @Volatile var javaVersion: String = ""
        private set

    init {
        NamedThreads.single("jshell-stderr").apply {
            execute { stderr.drain(process.stderr) }
            shutdown()
        }
        NamedThreads.single("jshell-reader").apply {
            execute { read() }
            shutdown()
        }
    }

    /** False once the session ended or [close] was called. */
    val alive: Boolean get() = synchronized(lock) { !ended } && !closing

    /** Whether an evaluation is in flight. */
    val running: Boolean get() = synchronized(lock) { evaluation != null }

    /**
     * Evaluates the snippets of [text] selected by [start]..[end] (whole-document UTF-16 offsets; start == end is a
     * caret: the snippet containing it, else the last one ending before it, else the first). Non-blocking; events on
     * the session's reader thread, in order, ending with Finished (or the session ending: then Finished is not sent
     * and onSessionEvent gets Ended).
     *
     * @throws IllegalStateException when an evaluation is running or the session is dead
     */
    fun evaluate(
        text: String,
        start: Int,
        end: Int,
        listener: (EvalEvent) -> Unit,
    ) {
        val id = ids.incrementAndGet()
        synchronized(lock) {
            check(!ended && !closing) { "The JShell session has ended" }
            check(evaluation == null) { "A JShell evaluation is running" }
            evaluation = Evaluation(id, listener)
        }
        try {
            send(mapOf("id" to id, "op" to "evaluate", "text" to text, "rangeStart" to start, "rangeEnd" to end))
        } catch (e: IOException) {
            // ours: one evaluation at a time, set just above
            synchronized(lock) { evaluation = null }
            throw IllegalStateException("The JShell session has ended", e)
        }
    }

    /** Stops the running evaluation; a no-op when idle. */
    fun stop() {
        if (!running) return
        try {
            send(mapOf("op" to "stop"))
        } catch (_: IOException) {
            // the session is ending; the reader reports it
        }
    }

    /**
     * Completion at [caret] in the whole [text]; the anchor in document offsets. Answered also while a snippet runs.
     * Fails with [IllegalStateException] when the session is dead or ends before the answer, with an [IOException]
     * when the agent refuses the request.
     */
    fun complete(
        text: String,
        caret: Int,
    ): CompletableFuture<Completions> {
        val future = CompletableFuture<Completions>()
        val id = ids.incrementAndGet()
        completions[id] = future
        // after the registration: an end that comes from now on fails it too
        if (!alive) {
            failCompletion(id)
            return future
        }
        try {
            send(mapOf("id" to id, "op" to "complete", "text" to text, "caret" to caret))
        } catch (_: IOException) {
            failCompletion(id)
        }
        return future
    }

    private fun failCompletion(id: Long) {
        completions.remove(id)?.completeExceptionally(IllegalStateException("The JShell session has ended"))
    }

    /** Ends the agent: closes its input, waits up to 2 s for it to end, then kills it. Idempotent; blocks. */
    override fun close() {
        // a second close finds the input closed and the agent ended
        closing = true
        try {
            synchronized(writer) { writer.close() }
        } catch (_: IOException) {
            // the agent is gone already
        }
        if (!process.waitFor(closeGraceMillis)) process.kill()
    }

    private fun send(request: Map<String, Any>) {
        val line = mapper.writeValueAsString(request)
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    /** The reader thread: every message in order, then the end of the agent's output. */
    private fun read() {
        val reader = ProtocolReader(process.stdout)
        try {
            while (true) dispatch(reader.next() ?: break)
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "The JShell agent's output broke off", e)
        }
        if (!ready.isDone) {
            synchronized(lock) { ended = true }
            ready.completeExceptionally(IOException("The JShell agent ended before it was ready"))
            return
        }
        if (closing) {
            end(SessionEvent.Ended("closed", null, null))
        } else if (synchronized(lock) { !ended }) {
            process.kill()
            stderr.awaitEnd(STDERR_END_MILLIS)
            end(SessionEvent.Ended("crash", null, stderr.text()))
        }
    }

    private fun dispatch(message: ObjectNode) {
        // 0 when there is none (late output): request ids start at 1
        val id = message.path("id").asLong()
        when (message.path("event").asString()) {
            "ready" -> {
                javaVersion = message.path("javaVersion").asString()
                ready.complete(Unit)
            }

            "output" -> {
                val text = message.path("text").asString()
                val error = message.path("error").asBoolean()
                // nothing comes after Ended
                val over = synchronized(lock) { ended }
                if (!toEvaluation(id, EvalEvent.Output(text, error)) &&
                    !over
                ) {
                    notify(SessionEvent.LateOutput(text, error))
                }
            }

            "snippetStarted" -> {
                toEvaluation(
                    id,
                    EvalEvent.SnippetStarted(int(message, "start"), int(message, "end"), int(message, "line")),
                )
            }

            "value" -> {
                toEvaluation(id, EvalEvent.Value(message.path("feedback").asString()))
            }

            "diagnostic" -> {
                toEvaluation(
                    id,
                    EvalEvent.Diagnostic(
                        int(message, "start"),
                        int(message, "end"),
                        int(message, "line"),
                        message.path("message").asString(),
                        message.path("error").asBoolean(),
                    ),
                )
            }

            "thrown" -> {
                val line = message.path("line").takeIf(JsonNode::isNumber)?.asInt()
                toEvaluation(id, EvalEvent.Thrown(message.path("text").asString(), line))
            }

            "stopped" -> {
                toEvaluation(id, EvalEvent.Stopped)
            }

            "finished" -> {
                finish(id)?.let { deliver(it, EvalEvent.Finished) }
            }

            "completions" -> {
                val items =
                    message.path("items").values().map {
                        JShellCompletion(it.path("text").asString(), it.path("smart").asBoolean())
                    }
                completions.remove(id)?.complete(Completions(int(message, "anchor"), items))
            }

            "error" -> {
                refused(id, message.path("message").asString())
            }

            "ended" -> {
                val status = message.path("exitStatus").takeIf(JsonNode::isNumber)?.asInt()
                end(SessionEvent.Ended(message.path("reason").asString(), status, null))
            }

            else -> {
                log.log(System.Logger.Level.WARNING, "The JShell agent sent an unknown event: ${message.path("event")}")
            }
        }
    }

    private fun int(
        message: ObjectNode,
        field: String,
    ) = message.path(field).asInt()

    /** Gives [event] to the running evaluation if it is [id]'s; false if it is not. */
    private fun toEvaluation(
        id: Long,
        event: EvalEvent,
    ): Boolean {
        val current = synchronized(lock) { evaluation?.takeIf { it.id == id } } ?: return false
        deliver(current, event)
        return true
    }

    /** Ends the evaluation [id] if it runs: not running any more before its listener hears of the end. */
    private fun finish(id: Long): Evaluation? =
        synchronized(lock) { evaluation?.takeIf { it.id == id }?.also { evaluation = null } }

    /** An `error` ends the request it answers: a completion fails, an evaluation ends with the message as output. */
    private fun refused(
        id: Long,
        message: String,
    ) {
        val completion = completions.remove(id)
        if (completion != null) {
            completion.completeExceptionally(IOException(message))
            return
        }
        // the agent answers a request it cannot read without an id: the session writes none such
        val current = finish(id) ?: return
        deliver(current, EvalEvent.Output(message, true))
        deliver(current, EvalEvent.Finished)
    }

    /** The session is over: once only, no Finished for a running evaluation, pending completions fail. */
    private fun end(event: SessionEvent.Ended) {
        synchronized(lock) {
            if (ended) return
            ended = true
            evaluation = null
        }
        completions.keys.toList().forEach(::failCompletion)
        notify(event)
    }

    private fun deliver(
        evaluation: Evaluation,
        event: EvalEvent,
    ) {
        try {
            evaluation.listener(event)
        } catch (e: Throwable) {
            // also an Error: the reader thread must live on
            log.log(System.Logger.Level.ERROR, "A JShell listener failed", e)
        }
    }

    private fun notify(event: SessionEvent) {
        try {
            onSessionEvent(event)
        } catch (e: Throwable) {
            log.log(System.Logger.Level.ERROR, "A JShell session listener failed", e)
        }
    }
}
