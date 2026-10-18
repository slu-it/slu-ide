package dev.sluide.ide

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitStatusService
import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class GitDiffsTest {
    @TempDir
    lateinit var repo: Path

    private val gitEnvironment =
        System.getenv().filterKeys { !it.startsWith("GIT_") } +
            mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")

    // Everything dispatched runs on this test's thread, like on the JavaFX thread.
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)
    private val services = mutableListOf<GitStatusService>()

    @AfterEach
    fun close() {
        services.forEach(GitStatusService::close)
        watcher.close()
    }

    private fun service(git: GitCli) =
        GitStatusService(git, watcher, trustingTrust()) {
            dispatched += it
        }.also(services::add)

    private fun git(vararg arguments: String) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(gitEnvironment)
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
    }

    @Test
    fun `without git there is no diff`() {
        val file = repo.resolve("a.txt").apply { writeText("a\n") }
        val service = service(GitCli(repo.resolve("no-such-git").toString(), System.getenv()))
        service.open(repo)

        assertNull(service.textDiff(file))
    }

    @Test
    fun `only modified files get a diff against HEAD`() {
        val git = GitCli("git", gitEnvironment)
        assumeTrue(git.available, "git is not installed")
        git("init", "-q")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        val changed = repo.resolve("changed.txt").apply { writeText("a\nb\nc\n") }
        val clean = repo.resolve("clean.txt").apply { writeText("x\n") }
        git("add", ".")
        git("commit", "-q", "-m", "Initial commit")
        changed.writeText("a\nB\nc\n")
        val added = repo.resolve("added.txt").apply { writeText("new\n") }
        git("add", "added.txt")
        val untracked = repo.resolve("untracked.txt").apply { writeText("new\n") }

        val service = service(git)
        service.open(repo)
        while (service.snapshot.get().root == null) {
            (dispatched.poll(10, TimeUnit.SECONDS) ?: fail("No git status")).run()
        }

        assertEquals(
            TextDiff(
                "a\nb\nc\n",
                listOf(LineChange(1..1, 1..1)),
                originalTitle = "HEAD",
                currentTitle = "Working Tree",
            ),
            service.textDiff(changed),
        )
        assertNull(service.textDiff(added))
        assertNull(service.textDiff(untracked))
        assertNull(service.textDiff(clean))
    }
}
