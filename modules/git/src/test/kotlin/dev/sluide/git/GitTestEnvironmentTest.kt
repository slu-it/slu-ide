package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitTestEnvironmentTest {
    @TempDir
    lateinit var dir: Path

    private val home get() = dir.resolve("home")
    private val repo get() = dir.resolve("repo")

    /** Runs git in [repo] with [environment]; its output, after `git init` and a `target/A.class` there. */
    private fun untracked(environment: Map<String, String>): String? {
        fun git(vararg arguments: String): String? =
            try {
                ProcessBuilder(listOf("git") + arguments)
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }.start()
                    .let { process ->
                        process.inputStream
                            .readAllBytes()
                            .decodeToString()
                            .also { process.waitFor() }
                    }
            } catch (_: java.io.IOException) {
                null
            }
        repo
            .resolve("target")
            .createDirectories()
            .resolve("A.class")
            .writeText("x")
        git("init", "-q") ?: return null
        return git("ls-files", "--others", "--exclude-standard")
    }

    // Git reads `core.excludesFile` from `$XDG_CONFIG_HOME/git/ignore` (else `~/.config/git/ignore`) even with
    // GIT_CONFIG_GLOBAL set; a developer's `*.class` there must not reach the tests' git.
    @Test
    fun `the tests' git does not read the global excludes of the user's home`() {
        home
            .resolve(".config/git")
            .createDirectories()
            .resolve("ignore")
            .writeText("*.class\n")
        val withoutXdg =
            testGitEnvironment().filterKeys { it != "XDG_CONFIG_HOME" } + mapOf("HOME" to home.toString())
        val hidden = untracked(withoutXdg)
        assumeTrue(hidden != null, "git is not installed")
        // The premise: with no XDG variable, git does read the fake home's list.
        assertEquals("", hidden?.trim(), "the fake home's excludes hide the file")
        repo.toFile().deleteRecursively()

        val listed = untracked(testGitEnvironment() + mapOf("HOME" to home.toString()))

        assertEquals("target/A.class", listed?.trim())
    }

    @Test
    fun `the tests' git reads the excludes of an empty config home`() {
        val configHome = assertNotNull(testGitEnvironment()["XDG_CONFIG_HOME"], "XDG_CONFIG_HOME is set")

        assertTrue(Files.isDirectory(Path.of(configHome)))
        assertTrue(Files.notExists(Path.of(configHome, "git", "ignore")))
    }
}
