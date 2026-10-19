package dev.sluide.ide.git

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitStatusSnapshot
import dev.sluide.git.cli.GitCli
import dev.sluide.ide.trustingTrust
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
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

    /** The answer of [textDiff] for [file], which may arrive through dispatch. */
    private fun GitStatusService.textDiffOf(file: Path): TextDiff? {
        val answers = mutableListOf<TextDiff?>()
        textDiff(file) { answers += it }
        while (answers.isEmpty()) (dispatched.poll(10, TimeUnit.SECONDS) ?: fail("No diff of $file")).run()
        return answers.single()
    }

    @Test
    fun `without git there is no diff`() {
        val file = repo.resolve("a.txt").apply { writeText("a\n") }
        val service = service(GitCli(repo.resolve("no-such-git").toString(), System.getenv()))
        service.open(repo)

        assertNull(service.textDiffOf(file))
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
            service.textDiffOf(changed),
        )
        val answers = mutableListOf<TextDiff?>()
        listOf(added, untracked, clean).forEach { file -> service.textDiff(file) { answers += it } }
        assertEquals(listOf<TextDiff?>(null, null, null), answers, "answered at once, without git")
    }

    @Test
    fun `a file no longer modified drops the diff still asked for it`(
        @TempDir scripts: Path,
    ) {
        // Stands in for git: status prints the status file, every other command waits while the hold file exists.
        val status = scripts.resolve("status")
        val hold = scripts.resolve("hold")
        val executable =
            scripts.resolve("git").apply {
                writeText(
                    """
                    #!/bin/sh
                    [ "${'$'}1" = --version ] && exit 0
                    case " ${'$'}* " in *' --local-env-vars '*|*' core.fsmonitor '*) exit 1;; esac
                    case " ${'$'}* " in *' status '*) cat '$status'; exit 0;; esac
                    while [ -e '$hold' ]; do sleep 0.01; done
                    echo old
                    """.trimIndent() + "\n",
                )
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")
        repo.resolve(".git").createDirectories()
        val changed = repo.resolve("changed.txt").apply { writeText("new\n") }
        status.writeText("1 .M N... 100644 100644 100644 e69de29 e69de29 changed.txt\u0000")
        val service = service(git)
        service.open(repo)
        awaitSnapshot(service) { it.fileStatus(changed) == GitStatus.MODIFIED }
        val answers = mutableListOf<String>()
        hold.createFile()
        service.textDiff(changed) { answers += "while modified" }

        status.writeText("")
        service.refresh()
        awaitSnapshot(service) { it.fileStatus(changed) == null }
        service.textDiff(changed) { answers += "clean: $it" }
        hold.deleteExisting()
        // Diffs are answered in order: one for the request while modified would come before this one.
        var marker = false
        service.diffAsync(repo.resolve("other.txt")) { marker = true }
        while (!marker) (dispatched.poll(10, TimeUnit.SECONDS) ?: fail("No answer")).run()

        assertEquals(listOf("clean: null"), answers)
    }

    private fun awaitSnapshot(
        service: GitStatusService,
        condition: (GitStatusSnapshot) -> Boolean,
    ) {
        while (!condition(service.snapshot.get())) {
            (dispatched.poll(10, TimeUnit.SECONDS) ?: fail("No git status")).run()
        }
    }
}
