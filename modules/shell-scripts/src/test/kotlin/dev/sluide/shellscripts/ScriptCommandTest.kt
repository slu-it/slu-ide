package dev.sluide.shellscripts

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The command line of a run ([scriptCommand]), making a script executable ([addOwnerExecute]) and the job's texts. */
class ScriptCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the script runs by its absolute path in its folder, plain words unquoted`() {
        assertEquals(
            "unset -f cd 2>/dev/null; \\cd /work/scripts && /work/scripts/deploy.sh --dry-run 'two words'",
            scriptCommand(Path.of("/work/scripts/../scripts/deploy.sh"), listOf("--dry-run", "two words")),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["two words", "it's", "\$HOME", "~", "~/x", "a\nb", "-n", "--", "*", "", "a;b", "\"q\""])
    fun `every argument reaches the script as it was, in the script's folder, whatever its folder is called`(
        argument: String,
    ) {
        assertEquals("${folder().toAbsolutePath().normalize()}\n[$argument]\n[last]\n", run("/bin/bash", argument))
    }

    @ParameterizedTest
    @ValueSource(strings = ["bash", "zsh", "dash"])
    fun `the command runs the script in its folder in every POSIX login shell, also with a cd function`(shell: String) {
        val program = listOf("/bin", "/usr/bin").map { Path.of(it, shell) }.firstOrNull(Files::isExecutable)
        assumeTrue(program != null, "$shell is not installed")

        assertEquals(
            "${folder().toAbsolutePath().normalize()}\n[it's \$x]\n[last]\n",
            run(program.toString(), "it's \$x"),
        )
    }

    /** The folder of the script, named to need quoting. */
    private fun folder() = dir.resolve("my scripts/it's \$x").createDirectories()

    /**
     * What a script printing its folder and arguments prints when [shell] runs its command line with [argument] and
     * `last`, after defining a function named `cd` as startup files may (zoxide), which must not change where it runs.
     */
    private fun run(
        shell: String,
        argument: String,
    ): String {
        val script =
            folder().resolve("print args.sh").apply {
                writeText("#!/bin/sh\npwd\nfor a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\n")
                toFile().setExecutable(true)
            }
        val hijack = "cd() { echo hijacked; }; "
        val process =
            ProcessBuilder(shell, "-c", hijack + scriptCommand(script, listOf(argument, "last")))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(10, TimeUnit.SECONDS)
        return output
    }

    @Test
    fun `making a script executable adds only the owner's execute permission`() {
        for (before in listOf("rw-r-----", "r--rw-rw-", "rw-r-xr-x")) {
            val file = dir.resolve("$before.sh").apply { writeText("echo\n") }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(before))

            addOwnerExecute(file)

            val expected = before.replaceRange(2, 3, "x")
            assertEquals(expected, PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), before)
        }
    }

    @Test
    fun `a run is named by the script and its arguments`() {
        val job = ScriptJob("deploy.sh", listOf("--dry-run", "two words"))

        assertEquals("▶ deploy.sh --dry-run two words — running 0:02", job.runningText(2.seconds))
        assertEquals("Script: deploy.sh --dry-run two words", job.backgroundName)
        assertEquals("deploy.sh is still running.", job.closeWarning("deploy.sh"))
        assertEquals("Script: deploy.sh", ScriptJob("deploy.sh", emptyList()).backgroundName)
    }
}
