package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GitCliTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a missing executable is not available and gives no status`() {
        val git = GitCli(folder.resolve("no-such-git").toString(), System.getenv())
        assertFalse(git.available)
        assertNull(git.status(folder))
    }

    @Test
    fun `status fails outside a repository`() {
        // Git must not find a repository above the temporary folder.
        val git = GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to folder.parent.toString()))
        assumeTrue(git.available, "git is not installed")
        assertNull(git.status(folder))
    }
}

/** The environment for git in tests: no global or system configuration, so the user's settings do not interfere. */
internal fun testGitEnvironment(): Map<String, String> =
    System.getenv() + mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
