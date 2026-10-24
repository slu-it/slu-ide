package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitIgnoreLineUse
import dev.sluide.git.GitIgnoreRules
import dev.sluide.git.testGitEnvironment
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [ignoreRules] with the real git in temporary repositories; skipped if git is not installed. */
class GitCliIgnoreRulesTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = folder.resolve("repo").createDirectories()
        git("init", "-q")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    /** Runs the real git in [repo] with [input] on its standard input; its output. */
    private fun git(
        vararg arguments: String,
        input: String? = null,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Creates the files [paths] in [repo], with their folders. */
    private fun files(vararg paths: String) {
        for (path in paths) {
            repo.resolve(path).apply { parent.createDirectories() }.writeText("x\n")
        }
    }

    private fun rules(
        ignoreFile: String = ".gitignore",
        limit: Int = MAX_IGNORE_CANDIDATES,
        maxListing: Long = MAX_IGNORE_LISTING,
    ): GitIgnoreRules = assertNotNull(git.ignoreRules(repo, repo.resolve(ignoreFile), null, limit, maxListing))

    private fun ignoring(vararg examples: String) =
        GitIgnoreLineUse(examples.size, 0, 0, examples.toList(), emptyList())

    @Test
    fun `a later line decides over an earlier one, which is left out`() {
        files("a.log", "b.log")
        repo.resolve(".gitignore").writeText("a.log\n*.log\n")

        val rules = rules()

        assertEquals(mapOf(2 to ignoring("a.log", "b.log")), rules.lines)
        assertTrue(rules.complete)
    }

    @Test
    fun `a negation counts what it un-ignores`() {
        files("a.tmp", "b.tmp")
        repo.resolve(".gitignore").writeText("*.tmp\n!a.tmp\n")

        assertEquals(
            mapOf(1 to ignoring("b.tmp"), 2 to GitIgnoreLineUse(0, 1, 0, listOf("a.tmp"), emptyList())),
            rules().lines,
        )
    }

    @Test
    fun `a line below an ignored folder decides nothing`() {
        files("build/a.tmp", "build/x/y.tmp")
        repo.resolve(".gitignore").writeText("build/\nbuild/*.tmp\n")

        // --ignored=matching lists the folder that build/ matches, not its content.
        assertEquals(mapOf(1 to ignoring("build/")), rules().lines)
    }

    @Test
    fun `files ignored one by one are asked for one by one, not their folder`() {
        files("logs/a.log", "logs/b.log")
        repo.resolve(".gitignore").writeText("*.log\n")

        assertEquals(mapOf(1 to ignoring("logs/a.log", "logs/b.log")), rules().lines)
    }

    @Test
    fun `tracked files a line matches are counted apart`() {
        files("tracked/t.log", "keep.txt")
        git("add", ".")
        git("commit", "-q", "-m", "Initial")
        repo.resolve(".gitignore").writeText("*.log\nkeep.txt\n")

        assertEquals(
            mapOf(
                1 to GitIgnoreLineUse(0, 0, 1, emptyList(), listOf("tracked/t.log")),
                2 to GitIgnoreLineUse(0, 0, 1, emptyList(), listOf("keep.txt")),
            ),
            rules().lines,
        )
    }

    @Test
    fun `an unmerged tracked file counts once, not once per stage`() {
        files("c.log")
        val blob = git("hash-object", "-w", "--stdin", input = "x\n")
        git("update-index", "--add", "--index-info", input = (1..3).joinToString("") { "100644 $blob $it\tc.log\n" })
        repo.resolve(".gitignore").writeText("*.log\n")
        assertEquals(3, git("ls-files", "--cached", "--ignored", "--exclude-standard").lines().size, "once per stage")

        assertEquals(mapOf(1 to GitIgnoreLineUse(0, 0, 1, emptyList(), listOf("c.log"))), rules().lines)
    }

    @Test
    fun `a nested ignore file has only its own lines, paths relative to its folder`() {
        files("x.bak", "sub/a.tmp", "sub/b.bak", "sub/deep/c.bak", "sub/excluded.txt", "sub/tracked.bak")
        git("add", "sub/tracked.bak")
        git("commit", "-q", "-m", "Initial")
        repo.resolve(".gitignore").writeText("*.bak\n")
        repo.resolve(".git/info/exclude").writeText("excluded.txt\n")
        repo.resolve("sub/.gitignore").writeText("*.tmp\n*.bak\n")

        assertEquals(
            mapOf(
                1 to ignoring("a.tmp"),
                2 to GitIgnoreLineUse(2, 0, 1, listOf("b.bak", "deep/c.bak"), listOf("tracked.bak")),
            ),
            rules("sub/.gitignore").lines,
        )
        // The root's file has its lines for the whole repository; the nested one decides over it.
        assertEquals(mapOf(1 to ignoring("x.bak")), rules().lines)
        // Git names the sources relative to the root, the info/exclude file too.
        assertEquals(
            "sub/.gitignore\u00002\u0000*.bak\u0000sub/b.bak\u0000" +
                ".git/info/exclude\u00001\u0000excluded.txt\u0000sub/excluded.txt\u0000",
            git("check-ignore", "-v", "-z", "--stdin", input = "sub/b.bak\u0000sub/excluded.txt\u0000"),
        )
    }

    @Test
    fun `an excludes file outside the ignore files is matched by its absolute path`() {
        files("a.x", "b.y")
        repo.resolve("my.ignore").writeText("*.x\n")
        git("config", "core.excludesFile", repo.resolve("my.ignore").toString())
        repo.resolve(".gitignore").writeText("*.y\n")

        assertEquals(mapOf(1 to ignoring("a.x")), rules("my.ignore").lines)
        // As it is configured: an absolute path.
        assertEquals(
            "${repo.resolve("my.ignore")}\u00001\u0000*.x\u0000a.x\u0000",
            git("check-ignore", "-v", "-z", "--stdin", input = "a.x\u0000"),
        )
    }

    @Test
    fun `a line that matches nothing is left out, and nothing ignored is no failure`() {
        files("a.log", "kept.txt")
        repo.resolve(".gitignore").writeText("nothing\n*.log\n")
        assertEquals(mapOf(2 to ignoring("a.log")), rules().lines)

        repo.resolve(".gitignore").writeText("nothing\n")
        val none = rules()
        assertEquals(emptyMap(), none.lines)
        assertTrue(none.complete)
    }

    @Test
    fun `many paths are asked in several chunks`() {
        val name = "a-rather-long-file-name-to-fill-the-input-of-git-quickly"
        val paths = (1..600).map { "logs/$name-$it.log" }
        files(*paths.toTypedArray())
        repo.resolve(".gitignore").writeText("*.log\n")
        assertTrue(inputChunks(paths, GitCli.MAX_INPUT).size > 1, "more than one chunk")

        val use = rules().lines.getValue(1)

        assertEquals(600, use.ignored)
        assertEquals(10, use.examples.size, "at most ten examples")
        assertEquals(paths.sorted().take(10), use.examples)
    }

    @Test
    fun `paths with spaces, non-ASCII characters and pathspec magic`() {
        files("with space.log", ":(glob)colon.log", "sub/:x.log")
        // Glob characters, a negation and an escape, where the file system allows them.
        val special =
            listOf("[ab].log", "!x.log", "*.log", "\\a.log").filter {
                try {
                    files(it)
                    true
                } catch (_: InvalidPathException) {
                    false
                } catch (_: IOException) {
                    false
                }
            }
        // Written by git, as the JVM may not name the file in a POSIX locale; then made untracked.
        val blob = git("hash-object", "-w", "--stdin", input = "x\n")
        git("update-index", "--add", "--index-info", input = "100644 $blob\tgrün.log\n")
        git("checkout", "-q", "--", ".")
        git("update-index", "--force-remove", "--stdin", input = "grün.log\n")
        repo.resolve(".gitignore").writeText("*.log\n")

        assertEquals(
            mapOf(
                1 to
                    ignoring(
                        *(listOf(":(glob)colon.log", "grün.log", "sub/:x.log", "with space.log") + special)
                            .sorted()
                            .toTypedArray(),
                    ),
            ),
            rules().lines,
        )
    }

    @Test
    fun `beyond the limit the first candidates are asked and the result is incomplete`() {
        files("a.log", "b.log", "c.log", "t.log")
        git("add", "t.log")
        git("commit", "-q", "-m", "Initial")
        repo.resolve(".gitignore").writeText("*.log\n")

        val all = rules(limit = 5)
        assertTrue(all.complete, "the .gitignore itself, three untracked and one tracked")
        assertEquals(GitIgnoreLineUse(3, 0, 1, listOf("a.log", "b.log", "c.log"), listOf("t.log")), all.lines[1])

        val capped = rules(limit = 3)
        assertFalse(capped.complete)
        assertEquals(mapOf(1 to ignoring("a.log", "b.log")), capped.lines)
    }

    @Test
    fun `a listing cut off keeps its complete records and is incomplete`() {
        files("a.log", "b.log", "c.log")
        repo.resolve(".gitignore").writeText("*.log\n")
        // "? .gitignore\0! a.log\0! b.log\0! c.log\0": cut off within b.log.
        val capped = rules(maxListing = 24)

        assertFalse(capped.complete)
        assertEquals(mapOf(1 to ignoring("a.log")), capped.lines)
    }

    @Test
    fun `no answer when the listings take all the time`() {
        files("a.log")
        repo.resolve(".gitignore").writeText("*.log\n")

        assertNull(git.ignoreRules(repo, repo.resolve(".gitignore"), null, deadline = Duration.ZERO))
    }

    @Test
    fun `once the time is up between chunks, the lines found so far are the incomplete answer`() {
        val marker = folder.resolve("asked")
        // Lists 600 long names (several chunks); the first check-ignore answers, any later one waits.
        val script =
            """
            case " ${'$'}* " in
              *' status '*) i=0; while [ ${'$'}i -lt 600 ]; do
                  printf '! a-rather-long-file-name-to-fill-the-input-of-check-ignore-%s.log\000' ${'$'}i; i=${'$'}((i+1)); done ;;
              *' check-ignore '*) cat > /dev/null; [ -e '$marker' ] && sleep 30; touch '$marker'
                  printf '%s\000' .gitignore 1 '*.log' first.log ;;
            esac
            """.trimIndent()
        val fake =
            folder.resolve("fake-git").apply {
                writeText(FAKE_GIT_START + script + "\n")
                toFile().setExecutable(true)
            }
        val slow = GitCli(fake.toString(), System.getenv())
        assumeTrue(slow.available, "no /bin/sh to stand in for git")
        val start = System.nanoTime()

        val rules = assertNotNull(slow.ignoreRules(repo, repo.resolve(".gitignore"), null, deadline = 1.seconds))

        assertTrue(System.nanoTime() - start < 5.seconds.inWholeNanoseconds, "stopped at the deadline")
        assertFalse(rules.complete)
        assertEquals(mapOf(1 to ignoring("first.log")), rules.lines)
    }

    @Test
    fun `no answer for a file outside the repository, without git or when cancelled`() {
        repo.resolve(".gitignore").writeText("*.log\n")
        assertNull(git.ignoreRules(repo, folder.resolve(".gitignore"), null))
        assertNull(git.ignoreRules(repo, repo, null))
        val noGit = GitCli(folder.resolve("no-such-git").toString(), System.getenv())
        assertNull(noGit.ignoreRules(repo, repo.resolve(".gitignore"), null))
        assertNull(git.ignoreRules(repo, repo.resolve(".gitignore"), GitCancellation().apply { cancel() }))
    }
}
