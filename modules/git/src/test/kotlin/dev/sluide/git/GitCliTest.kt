package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    @Test
    fun `an interrupted run stops git, what it started, and keeps the interrupt`() {
        val pidFile = folder.resolve("pid")
        val childFile = folder.resolve("child")
        // Answers --version; for anything else it starts a sleeping child, writes both process ids and waits.
        val executable =
            folder.resolve("slow-git").apply {
                writeText(
                    FAKE_GIT_START +
                        "sleep 10 &\necho $! > '$childFile'\necho $$ > '$pidFile'\nwait\n",
                )
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")

        var result: String? = "not finished"
        var interrupted = false
        val caller =
            Thread.ofPlatform().start {
                result = git.status(folder)
                interrupted = Thread.currentThread().isInterrupted
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pidFile.readTextOrNull().isNullOrBlank()) {
            if (System.nanoTime() > deadline) fail("git did not start")
            Thread.sleep(20)
        }
        val process = ProcessHandle.of(pidFile.readText().trim().toLong())
        val child = ProcessHandle.of(childFile.readText().trim().toLong())
        assertTrue(process.isPresent, "git runs")
        assertTrue(child.isPresent, "the child of git runs")

        caller.interrupt()
        caller.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(caller.isAlive)
        assertNull(result)
        assertTrue(interrupted, "the interrupt is kept")
        process.get().onExit().get(10, TimeUnit.SECONDS)
        assertFalse(process.get().isAlive, "git was stopped")
        // Well before the child would end by itself.
        child.get().onExit().get(5, TimeUnit.SECONDS)
        assertFalse(child.get().isAlive, "the child of git was stopped")
    }

    /** Runs the real git with [arguments] in [directory], without the user's configuration. */
    private fun git(
        directory: Path,
        vararg arguments: String,
    ) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
    }

    /** A new repository in [name] below the temporary folder. */
    private fun repository(name: String): Path {
        val repo = folder.resolve(name).createDirectories()
        git(repo, "init", "-q")
        return repo
    }

    @Test
    fun `git runs without the variables that would point it at another repository`() {
        val repo = repository("repo")
        val elsewhere = repository("elsewhere")
        repo.resolve("mine.txt").writeText("mine\n")
        val pointing =
            mapOf(
                "GIT_DIR" to elsewhere.resolve(".git").toString(),
                "GIT_WORK_TREE" to elsewhere.toString(),
                "GIT_INDEX_FILE" to elsewhere.resolve(".git/other-index").toString(),
            )
        // The user's configuration given in the environment stays, as git keeps it for submodules.
        val configuration = mapOf("GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "a.b", "GIT_CONFIG_VALUE_0" to "c")
        val git = GitCli("git", testGitEnvironment() + pointing + configuration)
        assumeTrue(git.available, "git is not installed")

        assertTrue(pointing.keys.none { it in git.environment })
        assertEquals("/dev/null", git.environment["GIT_CONFIG_GLOBAL"], "other variables stay")
        assertEquals(configuration, git.environment.filterKeys { it in configuration })
        assertTrue(git.status(repo).orEmpty().contains("? mine.txt"), "the status of the opened repository")
    }

    @Test
    fun `which variables point git at a repository is asked of git only when one is set`() {
        val calls = folder.resolve("calls")
        // Lists a variable git 2.53 does not know, as a newer git might.
        val newer =
            folder.resolve("newer-git").apply {
                writeText(
                    "#!/bin/sh\necho \"$*\" >> '$calls'\nprintf 'GIT_DIR\\nGIT_NEWER_THING\\nGIT_CONFIG_COUNT\\n'\n",
                )
                toFile().setExecutable(true)
            }
        val plain = mapOf("PATH" to "/usr/bin", "GIT_TERMINAL_PROMPT" to "0")
        assertEquals(emptySet(), repositoryVariables(newer.toString(), plain))
        assertFalse(calls.exists(), "git is not asked")

        val set =
            plain +
                mapOf("GIT_DIR" to "/x", "GIT_NEWER_THING" to "1", "GIT_INDEX_FILE" to "/i", "GIT_CONFIG_COUNT" to "0")
        assertEquals(setOf("GIT_DIR", "GIT_NEWER_THING", "GIT_INDEX_FILE"), repositoryVariables(newer.toString(), set))
        assertEquals("rev-parse --local-env-vars", calls.readText().trim())
        assertEquals(
            setOf("GIT_DIR", "GIT_INDEX_FILE"),
            repositoryVariables(folder.resolve("no-such-git").toString(), set),
            "the known ones if git cannot be asked",
        )
    }
}

private fun Path.readTextOrNull(): String? = if (exists()) readText() else null

/**
 * The start of a script that stands in for git: it answers `--version`, and fails what `GitCli` asks on its own (the
 * variables of `git rev-parse --local-env-vars`, ADR 0052) as a git without them would, so that what follows sees only
 * the commands under test.
 */
internal const val FAKE_GIT_START =
    "#!/bin/sh\n[ \"\$1\" = --version ] && exit 0\ncase \" \$* \" in *' --local-env-vars '*) exit 1;; esac\n"

/**
 * The environment for git in tests: no global or system configuration, so the user's settings do not interfere, and
 * none of the inherited `GIT_*` variables. A build run from a git hook or `git rebase -x` inherits `GIT_DIR`, which
 * would point the tests' git commands at the slu-ide repository itself.
 */
internal fun testGitEnvironment(): Map<String, String> =
    System.getenv().filterKeys { !it.startsWith("GIT_") } +
        mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")

class GitArgumentsTest {
    @Test
    fun `characters a charset cannot encode become a dot per UTF-8 byte`() {
        val ascii = Charsets.US_ASCII
        assertEquals("plain [a-z]+", encodableArgument("plain [a-z]+", ascii))
        assertEquals("Gr....e", encodableArgument("Gr\u00f6\u00dfe", ascii))
        assertEquals("a...b", encodableArgument("a\u20acb", ascii))
        assertEquals("....!", encodableArgument("\ud83d\ude00!", ascii), "a surrogate pair is one character")
        assertEquals("Gr\u00f6\u00dfe", encodableArgument("Gr\u00f6\u00dfe", Charsets.UTF_8))
        val latin9 = Charset.forName("ISO-8859-15")
        assertEquals("a..b", encodableArgument("a\u00a4b", latin9), "\u00a4 is not in Latin-9")
        assertEquals("a\u20acb", encodableArgument("a\u20acb", latin9))
    }

    @Test
    fun `escapes regular expressions and matches authors ignoring case`() {
        assertEquals("a\\.b\\[c\\]\\(\\)\\*\\+\\?\\{\\}\\|\\^\\$\\\\ x", escapeRegex("a.b[c]()*+?{}|^$\\ x"))
        assertEquals(listOf("^[Aa][Nn] [Oo]\\. <", "<[Aa][Nn] [Oo]\\.>"), authorPatterns("An o."))
        assertEquals(listOf("^1\u00e4 <", "<1\u00e4>"), authorPatterns("1\u00e4"))
    }
}
