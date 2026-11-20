package dev.sluide.jshell.agent

import jdk.jshell.JShell
import java.io.ByteArrayInputStream
import java.io.PrintStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The agent's protocol over a JShell of its own (ADR 0191): requests come in as lines, every message goes out through
 * [send] (one object per message, sent by one thread at a time). Evaluations run one at a time on `agent-eval`,
 * completions on `agent-complete`, also while a snippet runs; `stop` is handled at once on the reading thread.
 * When the execution JVM is gone (`System.exit` in a snippet), the running evaluation's `finished` comes first, then
 * `ended` with the exit status, then [exit] ends the agent.
 */
internal class AgentServer(
    private val send: (Map<String, Any?>) -> Unit,
    private val exit: (Int) -> Unit,
    private val stopRetryMillis: Long = 100,
) {
    private lateinit var shell: JShell
    private val lock = Any()

    // guarded by lock
    private var evaluating = false

    @Volatile private var executionGone = false

    @Volatile private var closing = false

    @Volatile private var currentId: Any? = null

    @Volatile private var stopRequested = false

    @Volatile private var exitStatus: Int? = null
    private val generation = AtomicLong()

    /** The document line (0-based) of each snippet's first code character when it was evaluated, for stack frames. */
    private val snippetLines = ConcurrentHashMap<String, Int>()

    private val evaluations = Executors.newSingleThreadExecutor(daemon("agent-eval"))
    private val completions = Executors.newSingleThreadExecutor(daemon("agent-complete"))
    private val stopper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("agent-stop"))

    /** Starts the execution JVM, runs the hidden start snippet and sends `ready`. */
    fun start() {
        shell =
            JShell
                .builder()
                .out(PrintStream(OutputEvents { output(it, false) }, true, "UTF-8"))
                .err(PrintStream(OutputEvents { output(it, true) }, true, "UTF-8"))
                .`in`(ByteArrayInputStream(ByteArray(0)))
                .remoteVMOptions(*UTF8_OUTPUT)
                .build()
        shell.eval(START_SNIPPET)
        shell.onShutdown { executionEnded() }
        send(
            mapOf(
                "event" to "ready",
                "javaVersion" to System.getProperty("java.version"),
                "javaHome" to System.getProperty("java.home"),
            ),
        )
    }

    /** Serves the requests [nextLine] returns until a `close` or the end of the input (null), then closes the JShell. */
    fun serve(nextLine: () -> String?) {
        while (true) {
            val line = nextLine() ?: break
            if (line.isBlank()) continue
            val request =
                try {
                    Json.parse(line)
                } catch (e: IllegalArgumentException) {
                    send(mapOf("event" to "error", "message" to "bad request: ${e.message}"))
                    continue
                }
            if (request["op"] == "close") break
            handle(request)
        }
        closing = true
        shell.close()
        evaluations.shutdown()
        completions.shutdown()
        stopper.shutdown()
        if (!executionGone) send(mapOf("event" to "ended", "reason" to "closed"))
    }

    private fun handle(request: Map<String, Any?>) {
        val id = request["id"]
        when (val op = request["op"]) {
            "evaluate" -> {
                val text = request["text"] as? String
                val start = (request["rangeStart"] as? Number)?.toInt()
                val end = (request["rangeEnd"] as? Number)?.toInt()
                if (text == null || start == null || end == null) {
                    error(id, "bad request: evaluate needs text, rangeStart and rangeEnd")
                    return
                }
                val refused =
                    synchronized(lock) {
                        when {
                            executionGone -> {
                                "ended"
                            }

                            evaluating -> {
                                "busy"
                            }

                            else -> {
                                // here, on the reading thread, so that a stop right after this request finds it
                                evaluating = true
                                generation.incrementAndGet()
                                stopRequested = false
                                currentId = id
                                null
                            }
                        }
                    }
                if (refused != null) error(id, refused) else evaluations.execute { runEvaluation(id, text, start, end) }
            }

            "complete" -> {
                val text = request["text"] as? String
                val caret = (request["caret"] as? Number)?.toInt()
                if (text == null || caret == null || caret < 0 || caret > text.length) {
                    error(id, "bad request: complete needs text and a caret within it")
                    return
                }
                completions.execute {
                    try {
                        complete(id, text, caret)
                    } catch (e: Exception) {
                        error(id, e.toString())
                    }
                }
            }

            "stop" -> {
                stop()
            }

            else -> {
                error(id, "unknown op $op")
            }
        }
    }

    private fun error(
        id: Any?,
        message: String,
    ) = send(mapOf("id" to id, "event" to "error", "message" to message))

    private fun output(
        text: String,
        error: Boolean,
    ) {
        var shown = text
        if (error) {
            val (status, rest) = exitReport(text)
            if (status != null) exitStatus = status
            shown = rest
        }
        if (shown.isNotEmpty()) send(mapOf("id" to currentId, "event" to "output", "text" to shown, "error" to error))
    }

    /**
     * Stops the running evaluation. JShell drops a stop that comes while the snippet still compiles, so it is sent
     * again every [stopRetryMillis] until the evaluation ends.
     */
    private fun stop() {
        val running = generation.get()
        // while idle this only marks a stop that the next evaluation clears; stopAgain sends nothing then
        stopRequested = true
        stopAgain(running)
    }

    private fun stopAgain(running: Long) {
        if (generation.get() != running || !synchronized(lock) { evaluating }) return
        shell.stop()
        stopper.schedule({ stopAgain(running) }, stopRetryMillis, TimeUnit.MILLISECONDS)
    }

    private fun runEvaluation(
        id: Any?,
        text: String,
        start: Int,
        end: Int,
    ) {
        val failure =
            try {
                evaluate(id, text, start, end)
                null
            } catch (e: Throwable) {
                e
            }
        synchronized(lock) {
            // Not busy any more before `finished` goes out: a client may send the next evaluation as soon as it reads it.
            evaluating = false
            if (failure == null) send(mapOf("id" to id, "event" to "finished")) else error(id, failure.toString())
            currentId = null
            if (executionGone) announceExit()
        }
    }

    private fun evaluate(
        id: Any?,
        text: String,
        rangeStart: Int,
        rangeEnd: Int,
    ) {
        val analysis = shell.sourceCodeAnalysis()
        for (piece in select(split(analysis, text), rangeStart, rangeEnd)) {
            val line = lineOf(text, piece.codeStart)
            // JShell counts a snippet's lines from its source, which starts with the comments before its code
            val sourceLine = lineOf(text, piece.start)
            send(
                mapOf(
                    "id" to id,
                    "event" to "snippetStarted",
                    "start" to piece.codeStart,
                    "end" to piece.end,
                    "line" to line,
                ),
            )
            for (event in shell.eval(piece.source)) {
                // the events of other snippets this one changed (their status) are not shown
                if (event.causeSnippet() != null) continue
                val snippet = event.snippet()
                snippetLines[snippet.id()] = sourceLine
                shell.diagnostics(snippet).forEach { diagnostic ->
                    val (start, end) = diagnosticRange(piece, diagnostic.startPosition, diagnostic.endPosition)
                    send(
                        mapOf(
                            "id" to id,
                            "event" to "diagnostic",
                            "start" to start,
                            "end" to end,
                            "line" to lineOf(text, start),
                            "message" to diagnostic.getMessage(Locale.ROOT),
                            "error" to diagnostic.isError,
                        ),
                    )
                }
                val exception = event.exception()
                if (exception != null) {
                    val thrown = describe(exception, snippetLines)
                    send(
                        mapOf(
                            "id" to id,
                            "event" to "thrown",
                            "text" to thrown,
                            "line" to throwingLine(exception, snippetLines),
                        ),
                    )
                } else {
                    feedback(shell, event)?.let { send(mapOf("id" to id, "event" to "value", "feedback" to it)) }
                }
            }
            if (stopRequested) {
                send(mapOf("id" to id, "event" to "stopped"))
                break
            }
            if (executionGone) break
        }
    }

    private fun complete(
        id: Any?,
        text: String,
        caret: Int,
    ) {
        // the whole text before the caret: JShell's analysis finds the snippet at its end itself, and the anchor is
        // then a document offset
        val anchor = IntArray(1)
        val suggestions = shell.sourceCodeAnalysis().completionSuggestions(text.substring(0, caret), caret, anchor)
        send(
            mapOf(
                "id" to id,
                "event" to "completions",
                "anchor" to anchor[0],
                "items" to suggestions.map { mapOf("text" to it.continuation(), "smart" to it.matchesType()) },
            ),
        )
    }

    /** The execution JVM is gone: `System.exit` in a snippet, or it died. Not when the agent closes JShell itself. */
    private fun executionEnded() {
        if (closing) return
        synchronized(lock) {
            executionGone = true
            if (!evaluating) announceExit()
        }
    }

    private fun announceExit() {
        send(mapOf("event" to "ended", "reason" to "exit", "exitStatus" to exitStatus))
        // not on JShell's thread, which may be the one System.exit would wait for
        Thread({ exit(0) }, "agent-ended").start()
    }

    private fun daemon(name: String) = ThreadFactory { Thread(it, name).apply { isDaemon = true } }

    private companion object {
        /**
         * The execution JVM writes System.out and System.err as UTF-8, which [OutputEvents] decodes: the JDK 18+ names
         * and the older ones. Without them it took the encoding of the JVM that started the agent's JShell (a test JVM
         * in a POSIX locale printed `ü€😀` as `???`).
         */
        val UTF8_OUTPUT =
            arrayOf(
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
            )

        /**
         * Runs in the execution JVM: it halts when the agent (its parent) is gone, also after a kill -9, so no execution
         * JVM is left behind; and it reports System.exit's status (JDK 21+ logs Runtime.exit to the logger
         * "java.lang.Runtime" at FINE). The watchdog's closure keeps the logger reachable, so its level and handler
         * survive garbage collection.
         */
        val START_SNIPPET =
            """
            {
              java.util.logging.Logger exitLog = java.util.logging.Logger.getLogger("java.lang.Runtime");
              exitLog.setLevel(java.util.logging.Level.FINE);
              exitLog.setUseParentHandlers(false);
              exitLog.addHandler(new java.util.logging.Handler() {
                public void publish(java.util.logging.LogRecord r) {
                  System.err.print("${EXIT_MARK.replace(
                "\u0000",
                "\\u0000",
            )}" + new java.util.logging.SimpleFormatter().formatMessage(r) + "\n");
                  System.err.flush();
                }
                public void flush() {}
                public void close() {}
              });
              ProcessHandle.current().parent().ifPresent(p -> p.onExit().thenRun(() -> {
                exitLog.getName();
                Runtime.getRuntime().halt(137);
              }));
            }
            """.trimIndent()
    }
}
