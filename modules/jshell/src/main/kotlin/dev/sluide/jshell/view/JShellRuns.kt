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
 * The runs of one JShell view, without JavaFX (ADR 0191): the session and its life (started on the first run or
 * completion, ended by Restart, a JDK change, `System.exit`, a crash or [dispose]), the evaluation in flight, and the
 * notes in the transcript; a session's start, and a restart for a JDK change, are noted by the first run on it. A
 * session no run has used yet is unseen: Run All runs on it, and its end is not noted. The methods are called on the
 * FX thread; blocking work (starting and closing a session) goes to [worker] (one thread, so that a session is closed
 * before the next one starts), and what the session reports comes back through [toFx]. [onChange] is called after
 * every change of [canRun], [canStop] or [running].
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

    /** A JDK change ended the session: the next run says so, as Run All does. */
    private var noteRestart = false

    /** The note of a session started for completion, written at its first run. */
    private var startNote: String? = null

    /** Identifies the session in use: what an older session reports later is dropped. */
    private var generation = 0

    /** A snippet is being evaluated (also while its session starts): the tab shows activity. */
    val running: Boolean get() = evaluating

    /** Whether Run and Run All do something: a JDK is there and nothing runs; a run waits for a start in flight. */
    val canRun: Boolean get() = !disposed && jdk()?.jdk != null && !evaluating

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

    /**
     * Ends the session if there is one, starts a new one and evaluates the whole [text]; a session no run has used is
     * as fresh as a new one and evaluates it.
     */
    fun runAll(text: String) {
        if (!canRun) return
        val major = jdk()?.jdk?.major
        if (session != null && startNote == null) {
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

    /** Ends the session; the next run starts a new one. Nothing without a session; no note for an unseen one. */
    fun restart() {
        if (session == null) return
        val seen = startNote == null
        closeSession()
        if (seen) output.note("Session restarted")
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

    /** Closes the session for a JDK change; the next run notes the restart if a run used it, or one before it. */
    private fun closeForJdk() {
        val restarted = startNote == null || noteRestart
        closeSession()
        noteRestart = restarted
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
        if (noteRestart) {
            noteRestart = false
            output.note("Session restarted on JDK ${jdk()?.jdk?.major}")
        }
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
        startNote?.let(output::note)
        startNote = null
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
        // written by the run that waits for this start, else by the first run on the session
        startNote = "JShell ${started.javaVersion} ($source)"
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

    /** The session ended on its own; a crash of one no run used is not noted (an exit comes from a run). */
    private fun ended(event: SessionEvent.Ended) {
        val seen = startNote == null
        when (event.reason) {
            "exit" -> {
                forgetSession()
                output.note(event.exitStatus?.let { "Session ended (exit $it)" } ?: "Session ended")
            }

            "crash" -> {
                forgetSession()
                if (!seen) return
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
        startNote = null
        sessionHome = null
        closeAfterRun = false
        evaluating = false
        onChange()
    }

    private fun closeSession() {
        val old = session
        session = null
        startNote = null
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
