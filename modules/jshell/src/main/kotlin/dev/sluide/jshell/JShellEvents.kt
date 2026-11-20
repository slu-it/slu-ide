package dev.sluide.jshell

/**
 * What an evaluation reports, in order, on the session's reader thread (ADR 0191). Offsets are UTF-16 offsets of the
 * whole document and lines 0-based, both as of the evaluation: nothing is mapped again after edits.
 */
sealed interface EvalEvent {
    /** A snippet starts: its code from [start] (after leading comments) to [end], starting on [line]. */
    data class SnippetStarted(
        val start: Int,
        val end: Int,
        val line: Int,
    ) : EvalEvent

    /** Printed text of the running snippet; consecutive pieces of one stream come joined, up to 65,536 UTF-16 characters each. */
    data class Output(
        val text: String,
        val error: Boolean,
    ) : EvalEvent

    /** JShell's feedback: "x ==> 5", "created method f(int)". */
    data class Value(
        val feedback: String,
    ) : EvalEvent

    data class Diagnostic(
        val start: Int,
        val end: Int,
        val line: Int,
        val message: String,
        val error: Boolean,
    ) : EvalEvent

    /**
     * An exception: its class, message and frames, frames of the session's snippets as "line N" (1-based); [line] is
     * the document line (0-based) of the innermost such frame, null if none.
     */
    data class Thrown(
        val text: String,
        val line: Int?,
    ) : EvalEvent

    /** The running snippet was stopped; the snippets after it do not run. */
    data object Stopped : EvalEvent

    /** The evaluation is over. Not sent when the session ends first. */
    data object Finished : EvalEvent
}

/** What the session reports outside an evaluation, on its reader thread. */
sealed interface SessionEvent {
    /** Output with no running evaluation, e.g. from a thread a snippet started. */
    data class LateOutput(
        val text: String,
        val error: Boolean,
    ) : SessionEvent

    /**
     * The session is over: [reason] "exit" (`System.exit` in a snippet, [exitStatus] from JDK 21 on), "closed" (after
     * [JShellSession.close]) or "crash" (the agent died; [detail] is the tail of its standard error).
     */
    data class Ended(
        val reason: String,
        val exitStatus: Int?,
        val detail: String?,
    ) : SessionEvent
}

/** Completions at a caret: each item's [JShellCompletion.text] replaces the document from [anchor] to the caret. */
data class Completions(
    val anchor: Int,
    val items: List<JShellCompletion>,
)

/** One completion; [smart] when its type fits where the caret is. */
data class JShellCompletion(
    val text: String,
    val smart: Boolean,
)

/** The agent did not get ready: it could not start, died before `ready` or did not answer in time. */
class JShellStartException(
    message: String,
    val stderrTail: String,
) : Exception(message)
