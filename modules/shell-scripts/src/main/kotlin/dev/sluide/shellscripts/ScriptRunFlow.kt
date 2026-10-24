package dev.sluide.shellscripts

import dev.sluide.commandruns.ShellWords
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The answer to the question about unsaved changes before a run. */
internal enum class UnsavedChoice { SAVE_AND_RUN, RUN_SAVED, CANCEL }

/** What [ScriptRunFlow] needs of the view of a script; the view's dialogs and runner, a fake in tests. FX thread. */
internal interface ScriptRunHost {
    /** The script. */
    val file: Path

    /** Whether the editor has unsaved changes. */
    val isDirty: Boolean

    /** Asks [question] about the unsaved changes. */
    fun askUnsaved(question: String): UnsavedChoice

    /** Saves the editor's text as File → Save does (the host shows errors); `false` if it could not. */
    fun save(): Boolean

    fun isExecutable(): Boolean

    /** Asks [question]: whether to make the script executable and run it. */
    fun askMakeExecutable(question: String): Boolean

    /** Lets the owner execute the script. */
    @Throws(IOException::class, UnsupportedOperationException::class)
    fun makeExecutable()

    /** Makes way for [job]: asks before stopping a running one; `false` if it keeps running. */
    fun replaceRunning(job: ScriptJob): Boolean

    /** Starts [job] with the command line [command]. */
    fun start(
        job: ScriptJob,
        command: String,
    )

    /** Shows [text] as a failure where the run's status shows. */
    fun showProblem(text: String)
}

/**
 * What a run of a script does before it starts (ADR 0089), in this order: split the [arguments][run] (an unclosed
 * quote is shown, nothing runs); ask before stopping a running one, before saving, since the shell reads a script
 * while it runs and saving over it would change the running script; ask about unsaved changes (save and run, run the
 * saved version, or cancel); ask to make a script executable that is not (and say so if it still is not); then start.
 */
internal class ScriptRunFlow(
    private val host: ScriptRunHost,
) {
    /** Runs the script with [arguments], as typed: words split as a shell does, each passed as one word. */
    fun run(arguments: String) {
        val words =
            try {
                ShellWords.tokenize(arguments)
            } catch (e: IllegalArgumentException) {
                return host.showProblem("✖ ${e.message}")
            }
        val name = host.file.fileName.toString()
        val job = ScriptJob(name, words)
        if (!host.replaceRunning(job)) return
        if (host.isDirty) {
            when (host.askUnsaved(unsavedQuestion(name))) {
                UnsavedChoice.SAVE_AND_RUN -> {
                    if (!host.save()) return
                }

                UnsavedChoice.RUN_SAVED -> {}

                UnsavedChoice.CANCEL -> {
                    return
                }
            }
        }
        if (!host.isExecutable()) {
            if (!host.askMakeExecutable(makeExecutableQuestion(name))) return
            try {
                host.makeExecutable()
            } catch (e: IOException) {
                return host.showProblem(notMadeExecutable(name, reasonOf(e)))
            } catch (_: UnsupportedOperationException) {
                return host.showProblem(notMadeExecutable(name, "the file system has no permissions"))
            }
            if (!host.isExecutable()) return host.showProblem(stillNotExecutable(name))
        }
        host.start(job, scriptCommand(host.file, words))
    }
}

/** Asked before a run while the editor of [name] has unsaved changes. */
internal fun unsavedQuestion(name: String) = "$name has unsaved changes."

/** Asked before a run of [name] when it is not executable. */
internal fun makeExecutableQuestion(name: String) = "$name is not executable. Make it executable and run?"

private fun notMadeExecutable(
    name: String,
    reason: String?,
) = "✖ Could not make $name executable" + (reason?.let { ": $it" } ?: "")

/** Why [e] happened, without the path that the message of a [FileSystemException] repeats. */
private fun reasonOf(e: IOException): String? =
    (e as? FileSystemException)?.reason
        ?: when (e) {
            is AccessDeniedException -> "access denied"
            is NoSuchFileException -> "file not found"
            else -> e.message
        }

private fun stillNotExecutable(name: String) =
    "✖ $name is still not executable (e.g. on a noexec mount or a file system without permissions)"
