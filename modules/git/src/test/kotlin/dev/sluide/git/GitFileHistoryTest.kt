package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.fileHistory
import dev.sluide.git.cli.shown
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The history of a file (ADR 0046) with the real git in a temporary repository; skipped if git is not installed. */
class GitFileHistoryTest {
    @TempDir
    lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())
    private val log = GitLog(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(
        vararg arguments: String,
        input: String = "",
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Writes the files (path to content, `null` deletes it), stages everything and commits; gives the commit. */
    private fun commitFiles(
        subject: String,
        vararg files: Pair<String, String?>,
    ): String {
        for ((path, content) in files) {
            if (content == null) {
                git("rm", "-q", path)
            } else {
                val file = repo.resolve(path)
                file.parent.createDirectories()
                file.writeText(content)
            }
        }
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    /** Stages [content] at [path] and removes the [removed] paths through git's standard input, for any characters. */
    private fun commitIndex(
        subject: String,
        path: String,
        content: String,
        vararg removed: String,
    ): String {
        val blob = git("hash-object", "-w", "--stdin", input = content)
        val zero = "0".repeat(40)
        val lines = removed.joinToString("") { "0 $zero\t$it\n" } + "100644 $blob\t$path\n"
        git("update-index", "--add", "--index-info", input = lines)
        git("commit", "-q", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    private val lines = (1..20).joinToString("") { "line $it\n" }

    private fun shown(page: GitFileHistoryPage?) =
        page?.entries?.map { entry ->
            val file = entry.file
            listOfNotNull(entry.commit.subject, file.change.name, file.oldPath, file.path).joinToString(" ")
        }

    @Test
    fun `follows a file across a rename, with edits before and after, whatever diff renames says`() {
        git("config", "diff.renames", "false")
        val first = commitFiles("First", "old.txt" to lines)
        commitFiles("Edit before", "old.txt" to lines + "before\n")
        commitFiles("Unrelated", "other.txt" to "x\n")
        commitFiles("Rename", "old.txt" to null, "new.txt" to lines + "before\n")
        commitFiles("Edit after", "new.txt" to lines + "before\nafter\n")

        val page = log.fileHistory(repo, "new.txt", skip = 0, limit = 10)

        assertEquals(
            listOf(
                "Edit after MODIFIED new.txt",
                "Rename RENAMED old.txt new.txt",
                "Edit before MODIFIED old.txt",
                "First ADDED old.txt",
            ),
            shown(page),
        )
        assertTrue(page?.complete == true)
        assertEquals(
            first,
            page
                ?.entries
                ?.last()
                ?.commit
                ?.hash,
        )
        assertEquals(
            emptyList(),
            page
                ?.entries
                ?.last()
                ?.commit
                ?.parents,
        )
        assertEquals(
            "Test",
            page
                ?.entries
                ?.first()
                ?.commit
                ?.author,
        )
    }

    @Test
    fun `reads files with non-ASCII names from the standard input`() {
        commitIndex("Green", "grün.txt", lines)
        commitIndex("Greener", "grün.txt", lines + "more\n")
        commitIndex("Blue", "blå.txt", lines + "more\n", "grün.txt")

        val page = log.fileHistory(repo, "blå.txt", skip = 0, limit = 10)

        assertEquals(
            listOf(
                "Blue RENAMED grün.txt blå.txt",
                "Greener MODIFIED grün.txt",
                "Green ADDED grün.txt",
            ),
            shown(page),
        )
    }

    @Test
    fun `starts at a given commit`() {
        commitFiles("First", "old.txt" to lines)
        val edit = commitFiles("Edit", "old.txt" to lines + "edit\n")
        val rename = commitFiles("Rename", "old.txt" to null, "new.txt" to lines + "edit\n")
        commitFiles("After", "new.txt" to lines + "edit\nafter\n")

        assertEquals(
            listOf("Edit MODIFIED old.txt", "First ADDED old.txt"),
            shown(log.fileHistory(repo, "old.txt", edit, skip = 0, limit = 10)),
        )
        assertEquals(
            listOf("Rename RENAMED old.txt new.txt", "Edit MODIFIED old.txt", "First ADDED old.txt"),
            shown(log.fileHistory(repo, "new.txt", rename, skip = 0, limit = 10)),
        )
        assertFailsWith<IllegalArgumentException> { log.fileHistory(repo, "new.txt", "main", skip = 0, limit = 10) }
    }

    @Test
    fun `a deleted file has its history up to the parent of the commit that deleted it`() {
        commitFiles("Add", "gone.txt" to lines)
        commitFiles("Edit", "gone.txt" to lines + "edit\n")
        val deletion = commitFiles("Delete", "gone.txt" to null)
        commitFiles("Later", "other.txt" to "x\n")
        val parent = git("rev-parse", "$deletion^")

        assertEquals(
            listOf("Edit MODIFIED gone.txt", "Add ADDED gone.txt"),
            shown(log.fileHistory(repo, "gone.txt", parent, skip = 0, limit = 10)),
        )
        // From HEAD, the deletion is the newest change.
        assertEquals(
            "Delete DELETED gone.txt",
            shown(log.fileHistory(repo, "gone.txt", skip = 0, limit = 10))?.first(),
        )
    }

    @Test
    fun `an untracked file and a repository without commits have an empty, complete history`() {
        assertEquals(
            GitFileHistoryPage(emptyList(), complete = true),
            log.fileHistory(repo, "a.txt", skip = 0, limit = 5),
        )
        commitFiles("First", "a.txt" to "a\n")
        repo.resolve("untracked.txt").writeText("u\n")

        assertEquals(
            GitFileHistoryPage(emptyList(), complete = true),
            log.fileHistory(repo, "untracked.txt", skip = 0, limit = 5),
        )
        // A literal path, not a pattern.
        assertEquals(
            GitFileHistoryPage(emptyList(), complete = true),
            log.fileHistory(repo, "*.txt", skip = 0, limit = 5),
        )
        assertNull(log.fileHistory(repo, "a\n.txt", skip = 0, limit = 5))
    }

    @Test
    fun `pages follow renames across their boundaries`() {
        commitFiles("First", "a.txt" to lines)
        commitFiles("To b", "a.txt" to null, "b.txt" to lines)
        commitFiles("Edit b", "b.txt" to lines + "edit\n")
        commitFiles("To c", "b.txt" to null, "c.txt" to lines + "edit\n")
        commitFiles("Edit c", "c.txt" to lines + "edit\nmore\n")
        val all = shown(log.fileHistory(repo, "c.txt", skip = 0, limit = 10)).orEmpty()

        val pages = (0 until 5).map { log.fileHistory(repo, "c.txt", skip = it, limit = 1) }

        assertEquals(all, pages.flatMap { shown(it).orEmpty() })
        assertEquals(5, all.size)
        // A full page cannot know that nothing follows, as for the log.
        assertEquals(List(5) { false }, pages.map { it?.complete })
        assertEquals(
            GitFileHistoryPage(emptyList(), complete = true),
            log.fileHistory(repo, "c.txt", skip = 5, limit = 2),
        )
    }

    @Test
    fun `pages keep the commits of merged branches`() {
        commitFiles("Base", "f.txt" to lines)
        git("switch", "-q", "-c", "feature")
        commitFiles("Feature 1", "f.txt" to "top\n" + lines)
        commitFiles("Feature 2", "f.txt" to "top\ntop 2\n" + lines)
        git("switch", "-q", "main")
        commitFiles("Main", "f.txt" to lines + "bottom\n")
        git("merge", "-q", "--no-ff", "-m", "Merge", "feature")
        val all = shown(log.fileHistory(repo, "f.txt", skip = 0, limit = 10)).orEmpty()

        val pages = (0 until all.size step 2).flatMap { shown(log.fileHistory(repo, "f.txt", skip = it, limit = 2))!! }

        assertEquals(4, all.count { !it.startsWith("Merge") })
        assertEquals(all, pages)
    }

    @Test
    fun `lists the root commit that added the file whatever log showRoot says`() {
        git("config", "log.showRoot", "false")
        val root = commitFiles("Root", "a.txt" to lines)
        commitFiles("Edit", "a.txt" to lines + "edit\n")

        val page = log.fileHistory(repo, "a.txt", skip = 0, limit = 10)

        assertEquals(listOf("Edit MODIFIED a.txt", "Root ADDED a.txt"), shown(page))
        assertEquals(
            root,
            page
                ?.entries
                ?.last()
                ?.commit
                ?.hash,
        )
    }

    @Test
    fun `a file named like an option is a path`() {
        commitFiles("Dash", "-dash.txt" to lines)
        commitFiles("Edit", "-dash.txt" to lines + "edit\n")

        assertEquals(
            listOf("Edit MODIFIED -dash.txt", "Dash ADDED -dash.txt"),
            shown(log.fileHistory(repo, "-dash.txt", skip = 0, limit = 10)),
        )
    }

    @Test
    fun `subjects and paths with the record separator are read as they are`() {
        commitIndex("Odd \u001e subject", "odd\u001e.txt", lines)
        commitIndex("Next \u001e\u001f one", "odd\u001e.txt", lines + "more\n")

        assertEquals(
            listOf("Next \u001e\u001f one MODIFIED odd\u001e.txt", "Odd \u001e subject ADDED odd\u001e.txt"),
            shown(log.fileHistory(repo, "odd\u001e.txt", skip = 0, limit = 10)),
        )
    }

    @Test
    fun `cancelling a read while git runs stops git and gives nothing`() {
        val pidFile = repo.resolve("git.pid")
        // Answers --version; for anything else it writes its process id and sleeps, standing in for a slow git log.
        val executable =
            repo.resolve("slow-git").apply {
                writeText(FAKE_GIT_START + "echo $$ > '$pidFile'\nexec sleep 30\n")
                toFile().setExecutable(true)
            }
        val slow = GitLog(GitCli(executable.toString(), System.getenv()))
        val cancellation = GitCancellation()
        var result: GitFileHistoryPage? = GitFileHistoryPage(emptyList(), complete = false)
        val caller =
            Thread.ofPlatform().start {
                result = slow.fileHistory(repo, "a.txt", skip = 0, limit = 10, cancellation = cancellation)
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pidFile.exists() || pidFile.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("git did not start")
            Thread.sleep(20)
        }
        val process = ProcessHandle.of(pidFile.readText().trim().toLong())
        assertTrue(process.isPresent, "git runs")

        val cancelledAt = System.nanoTime()
        cancellation.cancel()
        caller.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(caller.isAlive)
        assertTrue(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(5), "not waiting for git's end")
        assertNull(result)
        // Killed, not left to sleep its 30 s.
        process.get().onExit().get(5, TimeUnit.SECONDS)
        assertFalse(process.get().isAlive, "git is stopped")
    }

    @Test
    fun `a cancelled read gives nothing`() {
        commitFiles("First", "a.txt" to lines)
        val cancellation = GitCancellation().apply { cancel() }

        assertNull(log.fileHistory(repo, "a.txt", skip = 0, limit = 10, cancellation = cancellation))
    }
}
