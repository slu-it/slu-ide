package dev.sluide.git

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.cli.GitCli
import dev.sluide.projecttrust.ProjectTrustState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [GitStatusService.ignoreRulesAsync] with the real git in a temporary repository; skipped without git. */
class GitStatusServiceIgnoreRulesTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())

    // Everything dispatched runs on this test's thread, like on the JavaFX thread.
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)
    private val service = GitStatusService(git, watcher, testTrust(), BackgroundTasks.forTests()) { dispatched += it }

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = folder.resolve("repo").createDirectories()
        git("init", "-q")
        repo.resolve(".gitignore").writeText("*.log\nnothing\n")
        repo.resolve("a.log").writeText("")
    }

    private fun git(vararg arguments: String) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
    }

    @AfterEach
    fun close() {
        service.close()
        service.awaitClosed(5.seconds)
        watcher.close()
    }

    /** Runs dispatched tasks until [condition] holds. */
    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail(message)
            dispatched.poll(remaining, TimeUnit.NANOSECONDS)?.run()
        }
    }

    /** The answer of [GitStatusService.ignoreRulesAsync] for [file], which arrives through dispatch. */
    private fun rulesOf(
        file: Path,
        service: GitStatusService = this.service,
    ): GitIgnoreRules? {
        val answers = mutableListOf<GitIgnoreRules?>()
        service.ignoreRulesAsync(file) { answers += it }
        assertEquals(emptyList(), answers, "not answered at once")
        await("No rules of $file") { answers.isNotEmpty() }
        return answers.single()
    }

    @Test
    fun `answers for a file of the followed repository and null otherwise`() {
        assertNull(rulesOf(repo.resolve(".gitignore")), "no repository followed yet")
        service.open(repo)

        val rules = assertNotNull(rulesOf(repo.resolve(".gitignore")))
        assertEquals(mapOf(1 to GitIgnoreLineUse(1, 0, 0, listOf("a.log"), emptyList())), rules.lines)
        assertNull(rulesOf(folder.resolve(".gitignore").apply { writeText("*.log\n") }), "outside the repository")
    }

    @Test
    fun `only the newest request for a file is answered, apart from its diff`() {
        service.open(repo)
        val file = repo.resolve(".gitignore")
        val answers = mutableListOf<String>()

        service.ignoreRulesAsync(file) { answers += "older" }
        service.diffAsync(file) { answers += "diff" }
        service.ignoreRulesAsync(file) { answers += "newest ${it?.lines?.keys}" }
        await("No answers") { answers.size >= 2 }

        assertEquals(listOf("diff", "newest [1]"), answers)
    }

    @Test
    fun `a cancelled request is not answered`() {
        service.open(repo)
        val file = repo.resolve(".gitignore")
        val answers = mutableListOf<String>()

        service.ignoreRulesAsync(file) { answers += "cancelled" }
        service.cancelIgnoreRules(file)
        // Answers come in order: one for the cancelled request would come before this one.
        service.diffAsync(file) { answers += "diff" }
        await("No answer") { answers.isNotEmpty() }

        assertEquals(listOf("diff"), answers)
    }

    @Test
    fun `no answer while the repository's configuration is not trusted`() {
        git("config", "core.fsmonitor", "./watch.sh")
        val trust = testTrust(repo, listOf(GitTrustCheck(git)))
        assertIs<ProjectTrustState.Ask>(trust.state.get())
        val gated = GitStatusService(git, watcher, trust, BackgroundTasks.forTests()) { dispatched += it }
        gated.open(repo)

        assertNull(rulesOf(repo.resolve(".gitignore"), gated))

        assertTrue(trust.decide(trust.state.get(), trusted = true, remember = false))
        assertNotNull(rulesOf(repo.resolve(".gitignore"), gated))
        gated.close()
        trust.close()
    }
}
