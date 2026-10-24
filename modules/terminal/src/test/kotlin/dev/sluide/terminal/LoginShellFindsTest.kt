package dev.sluide.terminal

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `sometool` is looked up by a login shell whose `.profile`, in a test home folder, sets the `PATH`. */
class LoginShellFindsTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("home").createDirectories() }
    private val bin by lazy { root.resolve("bin").createDirectories() }
    private val environment by lazy {
        System.getenv() + mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh")
    }

    @BeforeEach
    fun onlyThePathOfTheProfile() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        home.resolve(".profile").writeText("PATH=\"$bin\"\nexport PATH\n")
    }

    private fun stubTool() {
        bin.resolve("sometool").apply {
            writeText("#!/bin/sh\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `sometool on the PATH of the login shell is found`() {
        stubTool()

        assertTrue(loginShellFinds("sometool", environment))
    }

    @Test
    fun `sometool on a PATH set only for interactive shells is found, as in a terminal tab`() {
        // sh reads the file named by ENV only when interactive, as zsh reads .zshrc (where SDKMAN sets things up).
        val interactive = home.resolve(".shrc")
        home.resolve(".profile").writeText("ENV=\"$interactive\"\nexport ENV\n")
        interactive.writeText("PATH=\"$bin\"\nexport PATH\n")
        stubTool()

        assertTrue(loginShellFinds("sometool", environment))
    }

    @Test
    fun `the interactive shell runs in a session of its own, so it cannot take the IDE's terminal`() {
        assumeTrue(System.getProperty("os.name") == "Linux", "ps -o sid is Linux only")
        assumeTrue(Files.isExecutable(Path.of("/bin/ps")), "no /bin/ps")
        val session = root.resolve("session")
        home
            .resolve(
                ".profile",
            ).writeText("PATH=\"$bin\"\nexport PATH\necho \"$$ \$(/bin/ps -o sid= -p $$)\" > \"$session\"\n")
        stubTool()

        assertTrue(loginShellFinds("sometool", environment))
        val (shell, sessionId) = session.readText().trim().split(Regex("""\s+"""))
        assertEquals(shell, sessionId, "the shell leads its own session")
    }

    @Test
    fun `sometool missing from the PATH of the login shell is not found`() {
        assertFalse(loginShellFinds("sometool", environment))
    }

    @Test
    fun `a shell that cannot be started finds nothing`() {
        stubTool()
        assertFalse(loginShellFinds("sometool", environment + ("SHELL" to root.resolve("no-such-shell").toString())))
    }

    @Test
    fun `a login profile that hangs is stopped with what it started`() {
        val pidFile = root.resolve("pid")
        // /bin/sleep: the profile's PATH holds only the stub folder.
        home
            .resolve(
                ".profile",
            ).writeText("PATH=\"$bin\"\nexport PATH\n/bin/sleep 30 &\necho \$! > \"$pidFile\"\nwait\n")
        val timeout = 2.seconds
        val check = Executors.newSingleThreadExecutor()
        try {
            val started = System.nanoTime()
            val result = check.submit<Boolean> { loginShellFinds("sometool", environment, timeout) }
            val deadline = System.nanoTime() + 5_000_000_000
            while (!(pidFile.exists() && pidFile.readText().isNotBlank()) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            val pid = pidFile.readText().trim().toLong()
            assertTrue(
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
                "the profile's sleep runs during the check",
            )
            assertFalse(result.get(10, TimeUnit.SECONDS))
            val elapsed = System.nanoTime() - started
            assertTrue(elapsed >= timeout.inWholeNanoseconds, "it waited for the timeout: $elapsed ns")
            assertTrue(elapsed < 5_000_000_000, "returns soon after the timeout")

            // Killing is asynchronous: give the sleep a moment to be gone.
            ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the sleep of the profile is gone")
        } finally {
            // Whatever failed above, no sleep may outlive the test.
            check.shutdownNow()
            if (pidFile.exists()) {
                pidFile.readText().trim().toLongOrNull()?.let { pid ->
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                }
            }
        }
    }
}
