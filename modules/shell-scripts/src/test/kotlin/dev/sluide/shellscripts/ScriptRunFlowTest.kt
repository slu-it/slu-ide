package dev.sluide.shellscripts

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a run of a script asks and does before it starts, in which order ([ScriptRunFlow]). */
class ScriptRunFlowTest {
    /** Records every call as a line; answers as set. */
    private class FakeHost : ScriptRunHost {
        val calls = mutableListOf<String>()
        override val file: Path = Path.of("/work/scripts/deploy.sh")
        override var isDirty = false
        var unsaved = UnsavedChoice.CANCEL
        var saves = true
        var executable = true
        var makeExecutableAnswer = false
        var makes: () -> Boolean = { true }
        var replaces = true

        override fun askUnsaved(question: String) = unsaved.also { calls += "askUnsaved: $question" }

        override fun save() = saves.also { calls += "save" }

        override fun isExecutable() = executable

        override fun askMakeExecutable(question: String) =
            makeExecutableAnswer.also { calls += "askMakeExecutable: $question" }

        override fun makeExecutable() {
            calls += "makeExecutable"
            executable = makes()
        }

        override fun replaceRunning(job: ScriptJob) = replaces.also { calls += "replaceRunning: ${job.label}" }

        override fun start(
            job: ScriptJob,
            command: String,
            arguments: String,
        ) {
            calls += "start: ${job.label} | $command | $arguments"
        }

        override fun showProblem(text: String) {
            calls += "problem: $text"
        }
    }

    private val host = FakeHost()

    private fun run(arguments: String = "--dry-run 'two words'") = ScriptRunFlow(host).run(arguments)

    private val start =
        "start: deploy.sh --dry-run two words | " +
            "unset -f cd 2>/dev/null; \\cd /work/scripts && /work/scripts/deploy.sh --dry-run 'two words' | " +
            "--dry-run 'two words'"

    @Test
    fun `a saved executable script just starts, after making way for it`() {
        run()

        assertEquals(listOf("replaceRunning: deploy.sh --dry-run two words", start), host.calls)
    }

    @Test
    fun `an unclosed quote is shown and nothing runs`() {
        run("--name 'oops")

        assertEquals(listOf("problem: ✖ Unbalanced ' quote in the arguments"), host.calls)
    }

    @Test
    fun `keeping the running script stops before anything is asked or saved`() {
        host.replaces = false
        host.isDirty = true

        run()

        assertEquals(listOf("replaceRunning: deploy.sh --dry-run two words"), host.calls)
    }

    @Test
    fun `unsaved changes are asked about after the stop question, Save and Run saves then runs`() {
        host.isDirty = true
        host.unsaved = UnsavedChoice.SAVE_AND_RUN

        run()

        assertEquals(
            listOf(
                "replaceRunning: deploy.sh --dry-run two words",
                "askUnsaved: deploy.sh has unsaved changes.",
                "save",
                start,
            ),
            host.calls,
        )
    }

    @Test
    fun `a failed save runs nothing`() {
        host.isDirty = true
        host.unsaved = UnsavedChoice.SAVE_AND_RUN
        host.saves = false

        run()

        assertEquals("save", host.calls.last())
    }

    @Test
    fun `Run Saved Version runs without saving, Cancel runs nothing`() {
        host.isDirty = true
        host.unsaved = UnsavedChoice.RUN_SAVED
        run()
        assertEquals(start, host.calls.last())
        assertEquals(0, host.calls.count { it == "save" })

        host.calls.clear()
        host.unsaved = UnsavedChoice.CANCEL
        run()
        assertEquals("askUnsaved: deploy.sh has unsaved changes.", host.calls.last())
    }

    @Test
    fun `a script that is not executable is made executable when the user agrees, then runs`() {
        host.executable = false
        host.makeExecutableAnswer = true

        run()

        assertEquals(
            listOf(
                "replaceRunning: deploy.sh --dry-run two words",
                "askMakeExecutable: deploy.sh is not executable. Make it executable and run?",
                "makeExecutable",
                start,
            ),
            host.calls,
        )
    }

    @Test
    fun `a script that is not executable runs nothing when the user declines`() {
        host.executable = false

        run()

        assertEquals(
            "askMakeExecutable: deploy.sh is not executable. Make it executable and run?",
            host.calls.last(),
        )
    }

    @Test
    fun `a script still not executable afterwards is reported`() {
        host.executable = false
        host.makeExecutableAnswer = true
        host.makes = { false }

        run()

        assertEquals(
            "problem: ✖ deploy.sh is still not executable (e.g. on a noexec mount or a file system without " +
                "permissions)",
            host.calls.last(),
        )
    }

    @Test
    fun `a failure to make it executable is reported`() {
        host.executable = false
        host.makeExecutableAnswer = true
        val path = host.file.toString()
        val reasons =
            listOf(
                FileSystemException(path, null, "Operation not permitted") to "Operation not permitted",
                AccessDeniedException(path) to "access denied",
                NoSuchFileException(path) to "file not found",
                IOException("Disk quota exceeded") to "Disk quota exceeded",
                UnsupportedOperationException() to "the file system has no permissions",
            )
        for ((failure, reason) in reasons) {
            host.makes = { throw failure }
            run()
            assertEquals("problem: ✖ Could not make deploy.sh executable: $reason", host.calls.last())
        }
    }
}
