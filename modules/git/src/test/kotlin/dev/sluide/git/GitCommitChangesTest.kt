package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.headMessage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitCommitChangesTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val commits = GitCommits(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun write(
        name: String,
        content: String,
    ) = repo.resolve(name).also { it.parent.createDirectories() }.writeText(content)

    /** A first commit of a.txt, b.txt and c.txt. */
    private fun base() {
        for (name in listOf("a.txt", "b.txt", "c.txt")) write(name, "$name\n")
        git("add", ".")
        git("commit", "-q", "-m", "Base")
    }

    private fun changed(
        vararg paths: String,
        amend: Boolean = false,
        untracked: List<String> = emptyList(),
        message: String = "Subject",
    ) = commits.commit(
        repo,
        message,
        paths.map { GitChangedFile(GitChange.MODIFIED, it) },
        untracked,
        amend,
    )

    private fun committed(): List<String> =
        git("show", "--name-only", "--format=", "HEAD").lines().filter(String::isNotEmpty)

    private fun conflictingMerge() {
        base()
        git("switch", "-q", "-c", "other")
        write("a.txt", "theirs\n")
        git("commit", "-q", "-am", "Theirs")
        git("switch", "-q", "main")
        write("a.txt", "ours\n")
        git("commit", "-q", "-am", "Ours")
        process("merge", "other")
    }

    private fun process(vararg arguments: String) {
        ProcessBuilder(listOf("git") + arguments)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(testGitEnvironment())
            }.start()
            .also { it.inputStream.readAllBytes() }
            .waitFor()
    }

    /** Runs the shell [script] in the repository, which must succeed. */
    private fun shell(script: String) {
        val process =
            ProcessBuilder("sh", "-c", script)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "$script: $output")
    }

    @Test
    fun `reads the message as UTF-8 without signature lines whatever the log configuration says`() {
        base()
        git("config", "log.showSignature", "true")
        git("config", "i18n.logOutputEncoding", "ISO-8859-1")
        git("config", "i18n.commitEncoding", "ISO-8859-1")
        write("a.txt", "a2\n")

        assertTrue(changed("a.txt", message = "Füge Überschrift hinzu ✓").result.success)

        assertEquals("Füge Überschrift hinzu ✓", commits.headMessage(repo))
    }

    @Test
    fun `commits the path of a copy but not its source`() {
        base()
        write("a.txt", "a edited, not checked\n")
        write("copy.txt", "a.txt\n")
        git("add", "copy.txt")

        val copy = GitChangedFile(GitChange.COPIED, "copy.txt", "a.txt")
        val outcome = commits.commit(repo, "Copy", listOf(copy), listOf(), false)

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals(listOf("copy.txt"), committed())
        assertEquals("M a.txt", git("status", "--porcelain"))
    }

    @Test
    fun `commits a staged deletion`() {
        base()
        git("rm", "-q", "b.txt")

        val outcome =
            commits.commit(repo, "Delete", listOf(GitChangedFile(GitChange.DELETED, "b.txt")), emptyList(), false)

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals("D\tb.txt", git("show", "--name-status", "--format=", "HEAD"))
    }

    @Test
    fun `does not commit when already cancelled`() {
        base()
        write("a.txt", "a2\n")
        val cancellation = GitCancellation().also { it.cancel() }

        val outcome =
            commits.commit(
                repo,
                "Cancelled",
                listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")),
                emptyList(),
                false,
                cancellation,
            )

        assertEquals(GitResult(false, GitCli.CANCELLED), outcome.result)
        assertNull(outcome.commit)
        assertEquals("1", git("rev-list", "--count", "HEAD"))
    }

    @Test
    fun `refuses unmerged paths when nothing is in progress`() {
        base()
        write("a.txt", "stashed\n")
        git("stash", "push", "-q")
        write("a.txt", "committed\n")
        git("commit", "-q", "-am", "Other")
        process("stash", "pop")
        val changes = assertNotNull(commits.commitChanges(repo))
        assertNull(changes.inProgress)
        assertEquals(listOf("a.txt"), changes.conflicts)

        val outcome = changed("a.txt")

        assertEquals(GitResult(false, "Resolve the conflicts first."), outcome.result)
    }

    @Test
    fun `lists a nested repository as its folder and commits it as a gitlink`() {
        base()
        shell(
            "mkdir sub && cd sub && git init -q -b main && git config user.name T && " +
                "git config user.email t@e && echo x > x && git add . && git commit -qm inner",
        )

        assertEquals(listOf("sub/"), assertNotNull(commits.commitChanges(repo)).untracked)
        val outcome = changed(untracked = listOf("sub/"))

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals("160000", git("ls-tree", "HEAD", "sub").substringBefore(' '))
    }

    @Test
    fun `commits only the checked files and leaves other changes alone`() {
        base()
        write("a.txt", "a2\n")
        write("b.txt", "b2\n")
        write("c.txt", "c2\n")
        git("add", "c.txt")

        val outcome = changed("a.txt")

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals(git("rev-parse", "HEAD"), outcome.commit)
        assertEquals(listOf("a.txt"), committed())
        assertEquals("c.txt", git("diff", "--cached", "--name-only"))
        assertEquals("b.txt", git("diff", "--name-only"))
    }

    @Test
    fun `commits the working tree content of a partly staged file`() {
        base()
        write("a.txt", "staged\n")
        git("add", "a.txt")
        write("a.txt", "staged\nand more\n")

        assertTrue(changed("a.txt").result.success)

        assertEquals("staged\nand more", git("show", "HEAD:a.txt"))
        assertEquals("", git("status", "--porcelain"))
    }

    @Test
    fun `adds and commits checked untracked files and leaves the others`() {
        base()
        write("new.txt", "new\n")
        write("other.txt", "other\n")

        val outcome = changed(untracked = listOf("new.txt"))

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals(listOf("new.txt"), committed())
        assertEquals("?? other.txt", git("status", "--porcelain"))
    }

    @Test
    fun `commits a deleted file`() {
        base()
        repo.resolve("b.txt").deleteExisting()

        val outcome =
            commits.commit(repo, "Delete", listOf(GitChangedFile(GitChange.DELETED, "b.txt")), emptyList(), false)

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals("D\tb.txt", git("show", "--name-status", "--format=", "HEAD"))
    }

    @Test
    fun `commits both sides of a rename`() {
        base()
        git("mv", "a.txt", "renamed.txt")
        val changes = assertNotNull(commits.commitChanges(repo)).files
        assertEquals(listOf(GitChangedFile(GitChange.RENAMED, "renamed.txt", "a.txt")), changes)

        val outcome = commits.commit(repo, "Rename", changes, emptyList(), false)

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals("R100\ta.txt\trenamed.txt", git("show", "-M", "--name-status", "--format=", "HEAD"))
    }

    @Test
    fun `amends with files and keeps the other changes`() {
        base()
        write("a.txt", "a2\n")
        git("commit", "-q", "-am", "Second")
        val before = git("rev-parse", "HEAD")
        write("b.txt", "b2\n")

        val outcome = changed("b.txt", amend = true, message = "Amended")

        assertTrue(outcome.result.success, outcome.result.message)
        assertNotEquals(before, outcome.commit)
        assertEquals(listOf("a.txt", "b.txt"), committed())
        assertEquals("Amended", git("log", "-1", "--format=%s"))
        assertEquals("2", git("rev-list", "--count", "HEAD"))
    }

    @Test
    fun `amends only the message and keeps the tree`() {
        base()
        write("b.txt", "b2\n")
        git("add", "b.txt")
        val tree = git("rev-parse", "HEAD^{tree}")

        val outcome = changed(amend = true, message = "Reworded")

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals(tree, git("rev-parse", "HEAD^{tree}"))
        assertEquals("Reworded", git("log", "-1", "--format=%s"))
        assertEquals("b.txt", git("diff", "--cached", "--name-only"))
    }

    @Test
    fun `refuses an amend without a commit`() {
        write("a.txt", "a\n")

        val outcome = changed(amend = true)

        assertFalse(outcome.result.success)
        assertNull(outcome.commit)
    }

    @Test
    fun `makes the first commit`() {
        write("a.txt", "a\n")
        write("u.txt", "u\n")
        git("add", "a.txt")

        val changes = assertNotNull(commits.commitChanges(repo))
        assertEquals(listOf(GitChangedFile(GitChange.ADDED, "a.txt")), changes.files)
        assertEquals(listOf("u.txt"), changes.untracked)
        assertFalse(changes.headPushed)
        assertNull(commits.headMessage(repo))

        val outcome = commits.commit(repo, "First", changes.files, listOf("u.txt"), false)

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals(listOf("a.txt", "u.txt"), committed())
        assertEquals("First", commits.headMessage(repo))
    }

    @Test
    fun `commits a non-ASCII path with a non-ASCII message`() {
        base()
        // Made by the shell from an ASCII command: a JVM in a POSIX locale cannot name this file itself.
        shell("echo x > \"\$(printf '\\303\\274-datei.txt')\"")
        assertEquals(listOf("ü-datei.txt"), assertNotNull(commits.commitChanges(repo)).untracked)

        val outcome = changed(untracked = listOf("ü-datei.txt"), message = "Füge Überschrift hinzu ✓")

        assertTrue(outcome.result.success, outcome.result.message)
        assertEquals("Füge Überschrift hinzu ✓", commits.headMessage(repo))
        assertEquals("", git("status", "--porcelain"))
    }

    @Test
    fun `keeps lines starting with a hash and cleans up whitespace`() {
        base()
        write("a.txt", "a2\n")

        assertTrue(changed("a.txt", message = "\n\nSubject  \n\n# not a comment\n\n\nBody\n\n").result.success)

        assertEquals("Subject\n\n# not a comment\n\nBody", commits.headMessage(repo))
    }

    @Test
    fun `reports a failing hook and keeps the added files staged`() {
        base()
        val hook = repo.resolve(".git/hooks/pre-commit")
        hook.writeText("#!/bin/sh\necho 'no way' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        write("new.txt", "new\n")

        val outcome = changed(untracked = listOf("new.txt"))

        assertFalse(outcome.result.success)
        assertTrue("no way" in outcome.result.message, outcome.result.message)
        assertNull(outcome.commit)
        assertEquals("new.txt", git("diff", "--cached", "--name-only"))
    }

    @Test
    fun `refuses blank messages and empty selections without running git`() {
        base()
        write("a.txt", "a2\n")
        val calls = folder.resolve("calls.txt")
        val wrapper =
            folder.resolve("recording-git").apply {
                writeText("#!/bin/sh\necho \" \$* \" >> '$calls'\nexec git \"\$@\"\n")
                toFile().setExecutable(true)
            }
        val recorded = GitCommits(GitCli(wrapper.toString(), testGitEnvironment()))

        assertFalse(
            recorded
                .commit(
                    repo,
                    " \n",
                    listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")),
                    emptyList(),
                    false,
                ).result.success,
        )
        assertFalse(recorded.commit(repo, "Subject", emptyList(), emptyList(), false).result.success)
        assertTrue(!calls.toFile().exists() || calls.toFile().readLines().none { " commit " in it }, "git committed")
        assertEquals("a.txt", git("status", "--porcelain").substringAfter(' ').trim())
        assertEquals("1", git("rev-list", "--count", "HEAD"))
    }

    @Test
    fun `refuses during a merge and with conflicts`() {
        conflictingMerge()
        val changes = assertNotNull(commits.commitChanges(repo))
        assertEquals(GitPendingOperation(GitOperation.MERGE), changes.inProgress)
        assertEquals(listOf("a.txt"), changes.conflicts)

        val outcome = changed("a.txt")

        assertFalse(outcome.result.success)
        assertTrue("merge" in outcome.result.message, outcome.result.message)
        assertEquals("2", git("rev-list", "--count", "HEAD"))
    }

    @Test
    fun `refuses unmerged paths of a stopped cherry-pick with its message`() {
        conflictingMerge()
        git("merge", "--abort")
        process("cherry-pick", "other")

        val outcome = changed("a.txt")

        assertFalse(outcome.result.success)
        assertTrue("cherry-pick" in outcome.result.message, outcome.result.message)
    }

    @Test
    fun `lists tracked and untracked changes and leaves out ignored files`() {
        base()
        write(".gitignore", "ignored.txt\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore")
        write("a.txt", "a2\n")
        write("ignored.txt", "x\n")
        write("sub/deep.txt", "x\n")
        repo.resolve("c.txt").deleteExisting()

        val changes = assertNotNull(commits.commitChanges(repo))

        assertEquals(
            listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"), GitChangedFile(GitChange.DELETED, "c.txt")),
            changes.files,
        )
        assertEquals(listOf("sub/deep.txt"), changes.untracked)
        assertNull(changes.inProgress)
        assertEquals(emptyList(), changes.conflicts)
    }

    @Test
    fun `knows whether HEAD is pushed`() {
        base()
        assertFalse(assertNotNull(commits.commitChanges(repo)).headPushed, "no upstream")
        val remote = folder.resolve("remote.git")
        remote.createDirectories()
        git("init", "-q", "--bare", directory = remote)
        git("remote", "add", "origin", remote.toString())
        git("push", "-q", "-u", "origin", "main")
        assertTrue(assertNotNull(commits.commitChanges(repo)).headPushed)

        write("a.txt", "a2\n")
        git("commit", "-q", "-am", "Local")

        assertFalse(assertNotNull(commits.commitChanges(repo)).headPushed)
    }

    @Test
    fun `reads the full message of HEAD`() {
        base()
        write("a.txt", "a2\n")
        git("commit", "-q", "-am", "Subject", "-m", "Body line one\nline two")

        assertEquals("Subject\n\nBody line one\nline two", commits.headMessage(repo))
    }
}
