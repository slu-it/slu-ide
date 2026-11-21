package dev.sluide.jshell.view

import dev.sluide.java.Jdk
import dev.sluide.jshell.Completions
import dev.sluide.jshell.EvalEvent
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellSession
import dev.sluide.jshell.JShellStartException
import dev.sluide.jshell.SessionEvent
import dev.sluide.jshell.ui.RunOutput
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * The runs of one JShell view, without JavaFX (ADR 0191): the session and its life (started on the first run, ended by
 * Restart, a JDK change, `System.exit`, a crash or [dispose]), the evaluation in flight, and the notes in the
 * transcript. The methods are called on the FX thread; blocking work (starting and closing a session) goes to [worker]
 * (one thread, so that a session is closed before the next one starts), and what the session reports comes back through
 * [toFx]. [onChange] is called after every change of [canRun], [canStop] or [running].
 */
internal class JShellRuns(
    private val output: RunOutput,
    private val jdk: () -> JShellJdk?,
    private val startSession: (Jdk, (SessionEvent) -> Unit) -> JShellSession,
    private val worker: Executor,
    private val toFx: (() -> Unit) -> Unit,
    private val onChange: () -> Unit = {},
) {
    private var session: JShellSession? = null
    private var sessionHome: java.nio.file.Path? = null
    private var starting: CompletableFuture<JShellSession>? = null
    private var startingHome: java.nio.file.Path? = null
    private var evaluating = false
    private var disposed = false

    /** The session's JDK changed while it ran: it closes when the snippet ends, unless the JDK is its again. */
    private var closeAfterRun = false

    /** A JDK change ended the session: the next start says so, as Run All does. */
    private var noteRestart = false

    /** Identifies the session in use: what an older session reports later is dropped. */
    private var generation = 0

    /** A snippet is being evaluated (also while its session starts): the tab shows activity. */
    val running: Boolean get() = evaluating

    /** Whether Run and Run All do something: a JDK is there and nothing is starting or running. */
    val canRun: Boolean get() = !disposed && jdk()?.jdk != null && starting == null && !evaluating

    /** Whether Stop does something: a snippet is running in a started session. */
    val canStop: Boolean get() = evaluating && starting == null

    /** Evaluates [start]..[end] of [text] (whole-document offsets; equal: the snippet at the caret). */
    fun run(
        text: String,
        start: Int,
        end: Int,
    ) {
        if (!canRun) return
        evaluate(text, start, end)
    }

    /** Ends the session if there is one, starts a new one and evaluates the whole [text]. */
    fun runAll(text: String) {
        if (!canRun) return
        val major = jdk()?.jdk?.major
        if (session != null) {
            closeSession()
            output.note("Session restarted on JDK $major")
        }
        evaluate(text, 0, text.length)
    }

    /** Stops the running snippet; the snippets after it do not run. */
    fun stop() {
        val current = session ?: return
        if (!canStop) return
        worker.execute { current.stop() }
    }

    /** Ends the session; the next run starts a new one. Does nothing without a session. */
    fun restart() {
        if (session == null) return
        closeSession()
        output.note("Session restarted")
    }

    /**
     * The chosen JDK may have changed: a session on another JDK ends (after the snippet that runs, if one does), the
     * next run starts one on the new JDK and says so. A session still starting on another JDK is dropped.
     */
    fun jdkChanged() {
        val current = jdk()?.jdk?.home
        if (starting == null && sessionHome == null) return
        if (starting != null) {
            if (current != startingHome) generation++
        } else {
            if (current != sessionHome) {
                if (evaluating) closeAfterRun = true else closeForJdk()
            } else {
                closeAfterRun = false
            }
        }
        onChange()
    }

    private fun closeForJdk() {
        closeSession()
        noteRestart = true
    }

    /** Completion at [caret] in [text], starting the session if there is none. */
    fun complete(
        text: String,
        caret: Int,
    ): CompletableFuture<Completions> = ensureSession().thenCompose { it.complete(text, caret) }

    /** Ends the session without a note; nothing is reported to the output afterwards. */
    fun dispose() {
        disposed = true
        closeSession()
    }

    private fun evaluate(
        text: String,
        start: Int,
        end: Int,
    ) {
        evaluating = true
        onChange()
        ensureSession().whenComplete { current, error ->
            if (error != null) {
                evaluating = false
                onChange()
            } else {
                send(current, text, start, end)
            }
        }
    }

    private fun send(
        current: JShellSession,
        text: String,
        start: Int,
        end: Int,
    ) {
        val mine = generation
        output.startEvaluation(text)
        try {
            current.evaluate(text, start, end) { event -> toFx { evalEvent(mine, event) } }
        } catch (_: IllegalStateException) {
            // the session ended in between; its Ended event tells the output
            evaluating = false
            onChange()
        }
    }

    private fun evalEvent(
        mine: Int,
        event: EvalEvent,
    ) {
        if (mine != generation) return
        output.accept(event)
        if (event == EvalEvent.Finished) {
            evaluating = false
            if (closeAfterRun) {
                closeAfterRun = false
                if (jdk()?.jdk?.home != sessionHome) closeForJdk()
            }
            onChange()
        }
    }

    private fun ensureSession(): CompletableFuture<JShellSession> {
        if (disposed) return CompletableFuture.failedFuture(IllegalStateException("The view is closed"))
        session?.let { return CompletableFuture.completedFuture(it) }
        starting?.let { return it }
        val resolved = jdk()
        val target = resolved?.jdk ?: return CompletableFuture.failedFuture(IllegalStateException("No JDK with JShell"))
        val future = CompletableFuture<JShellSession>()
        starting = future
        if (noteRestart) {
            noteRestart = false
            output.note("Session restarted on JDK ${target.major}")
        }
        launch(future, target, resolved.source)
        onChange()
        return future
    }

    /** Starts a session on [target] on the worker; [future] gets it, or fails or is cancelled. */
    private fun launch(
        future: CompletableFuture<JShellSession>,
        target: Jdk,
        source: String,
    ) {
        val mine = ++generation
        startingHome = target.home
        worker.execute {
            try {
                val started = startSession(target) { event -> toFx { sessionEvent(mine, event) } }
                toFx { started(mine, started, target, source, future) }
            } catch (e: Exception) {
                toFx { startFailed(mine, e, future) }
            }
        }
    }

    /**
     * A start that the JDK changed under: the run asked for is not lost, a start on the current JDK takes its place.
     * False if there is none to start on (no JDK now, or the view is closed): the runs waiting for it are cancelled.
     */
    private fun replaceStale(future: CompletableFuture<JShellSession>): Boolean {
        val resolved = jdk()
        val target = resolved?.jdk
        if (disposed || target == null) return false
        starting = future
        launch(future, target, resolved.source)
        return true
    }

    private fun started(
        mine: Int,
        started: JShellSession,
        target: Jdk,
        source: String,
        future: CompletableFuture<JShellSession>,
    ) {
        starting = null
        if (mine != generation) {
            worker.execute { started.close() }
            if (!replaceStale(future)) future.cancel(false)
            onChange()
            return
        }
        session = started
        sessionHome = target.home
        output.note("JShell ${started.javaVersion} ($source)")
        future.complete(started)
        onChange()
    }

    private fun startFailed(
        mine: Int,
        error: Exception,
        future: CompletableFuture<JShellSession>,
    ) {
        starting = null
        if (mine != generation && replaceStale(future)) {
            onChange()
            return
        }
        if (mine == generation) {
            // the session's own messages already say "JShell could not start on JDK ..." or the like
            val (first, stderr) =
                if (error is JShellStartException) {
                    error.message.orEmpty() to error.stderrTail
                } else {
                    "JShell could not start: ${error.message}" to ""
                }
            val tail =
                stderr
                    .trimEnd()
                    .lines()
                    .takeLast(TAIL_LINES)
                    .filter { it.isNotEmpty() }
            output.note((listOf(first) + tail).joinToString("\n"))
        }
        future.completeExceptionally(error)
        onChange()
    }

    private fun sessionEvent(
        mine: Int,
        event: SessionEvent,
    ) {
        if (mine != generation) return
        when (event) {
            is SessionEvent.LateOutput -> output.lateOutput(event.text, event.error)
            is SessionEvent.Ended -> ended(event)
        }
    }

    private fun ended(event: SessionEvent.Ended) {
        when (event.reason) {
            "exit" -> {
                forgetSession()
                output.note(event.exitStatus?.let { "Session ended (exit $it)" } ?: "Session ended")
            }

            "crash" -> {
                forgetSession()
                val tail =
                    event.detail
                        .orEmpty()
                        .trimEnd()
                        .lines()
                        .takeLast(TAIL_LINES)
                        .filter { it.isNotEmpty() }
                output.note((listOf("JShell stopped unexpectedly") + tail).joinToString("\n"))
            }
        }
    }

    /** The session is gone on its own: nothing to close. */
    private fun forgetSession() {
        session = null
        sessionHome = null
        closeAfterRun = false
        evaluating = false
        onChange()
    }

    private fun closeSession() {
        val old = session
        session = null
        sessionHome = null
        evaluating = false
        closeAfterRun = false
        noteRestart = false
        generation++
        if (old != null) worker.execute { old.close() }
        onChange()
    }

    private companion object {
        const val TAIL_LINES = 10
    }
}
