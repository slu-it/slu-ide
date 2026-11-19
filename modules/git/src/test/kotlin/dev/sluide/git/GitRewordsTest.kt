package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.TestRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitRewordsTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val rewords = GitRewords(git)

    /** Who made the commits of the tests; a reword makes the configured `Test` their committer. */
    private val makers =
        mapOf(
            "GIT_AUTHOR_NAME" to "Author",
            "GIT_AUTHOR_EMAIL" to "author@example.com",
            "GIT_AUTHOR_DATE" to "2020-01-02T03:04:05+01:00",
            "GIT_COMMITTER_NAME" to "Committer",
            "GIT_COMMITTER_EMAIL" to "committer@example.com",
        )
    private val environment = testGitEnvironment() + makers
    private lateinit var repository: TestRepository

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        // No local main or master: commits on them are never reworded.
        repository = TestRepository(repo, initialBranch = "feature", environment = environment)
    }

    private fun gitBytes(
        vararg arguments: String,
        directory: Path = repo,
        input: ByteArray? = null,
        succeeds: Boolean = true,
    ): ByteArray {
        val run = runGitRaw(directory, *arguments, input = input, environment = environment)
        if (succeeds) assertEquals(0, run.exit, "git ${arguments.joinToString(" ")}: ${run.output}")
        return run.bytes
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
        input: ByteArray? = null,
        succeeds: Boolean = true,
    ) = gitBytes(*arguments, directory = directory, input = input, succeeds = succeeds).decodeToString().trim()

    private fun head() = git("rev-parse", "HEAD")

    private fun tip() = git("rev-parse", "refs/heads/feature")

    private fun tree(commit: String) = git("rev-parse", "$commit^{tree}")

    private fun message(commit: String) = git("show", "-s", "--format=%B", commit)

    private fun parents(commit: String) = git("rev-list", "--parents", "-n", "1", commit).split(' ').drop(1)

    /** The raw commit object, as a Latin-1 string: one character per byte. */
    private fun raw(commit: String) = gitBytes("cat-file", "commit", commit).toString(Charsets.ISO_8859_1)

    /** [raw] without its `parent` and `committer` lines, which a copy changes. */
    private fun copied(commit: String) =
        raw(commit).lines().filterNot { it.startsWith("parent ") || it.startsWith("committer ") }

    /** A bare repository as the remote `origin`. */
    private fun origin() {
        val bare = folder.resolve("origin.git")
        git("init", "-q", "--bare", bare.toString(), directory = folder)
        git("remote", "add", "origin", bare.toString())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2, 3])
    fun `rewords a commit and copies the later ones with their trees and authors`(index: Int) {
        val commits = (0..3).map { repository.commit("Commit $it\n\nBody $it.", "file$it.txt") }

        val done = assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", commits[index], "New\n\nNew body."))

        assertEquals(head(), done.head)
        assertEquals(done.head, tip())
        val after = git("rev-list", "--reverse", "HEAD").lines()
        assertEquals(commits.size, after.size)
        assertEquals(commits.take(index), after.take(index))
        for (i in commits.indices) {
            if (i >= index) assertNotEquals(commits[i], after[i])
            assertEquals(tree(commits[i]), tree(after[i]))
            assertEquals(listOfNotNull(after.getOrNull(i - 1)), parents(after[i]))
            assertEquals(if (i == index) "New\n\nNew body." else "Commit $i\n\nBody $i.", message(after[i]))
            val author = "Author|author@example.com|1577930645"
            assertEquals(author, git("show", "-s", "--format=%an|%ae|%at", after[i]))
            val committer = if (i >= index) "Test|test@example.com" else "Committer|committer@example.com"
            assertEquals(committer, git("show", "-s", "--format=%cn|%ce", after[i]))
        }
        assertEquals("reword: New", git("reflog", "-1", "--format=%gs", "refs/heads/feature"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `keeps a merge a merge with its other side as it is`(rewordMerge: Boolean) {
        repository.commit("Base")
        git("switch", "-q", "-c", "side")
        val side = repository.commit("Side", "side.txt")
        git("switch", "-q", "feature")
        val a = repository.commit("A", "a.txt")
        git("merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = head()
        val last = repository.commit("C", "c.txt")

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", if (rewordMerge) merge else a, "Reworded"))

        val newMerge = git("rev-parse", "HEAD^")
        val (first, second) = parents(newMerge)
        assertEquals(side, second)
        assertEquals(tree(merge), tree(newMerge))
        assertEquals(tree(last), tree("HEAD"))
        if (rewordMerge) {
            assertEquals(a, first)
            assertEquals("Reworded", message(newMerge))
        } else {
            assertNotEquals(a, first)
            assertEquals("Reworded", message(first))
            assertEquals("Merge side", message(newMerge))
        }
    }

    @Test
    fun `rewrites both parents of a merge whose other side also descends from the commit`() {
        repository.commit("Base")
        val a = repository.commit("A", "a.txt")
        git("switch", "-q", "-c", "side")
        val side = repository.commit("Side", "side.txt")
        git("switch", "-q", "feature")
        val b = repository.commit("B", "b.txt")
        git("merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = head()

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", a, "Reworded"))

        val (first, second) = parents("HEAD")
        assertNotEquals(b, first)
        assertNotEquals(side, second)
        assertEquals("Side", message("HEAD^2"))
        assertEquals("Merge side", message("HEAD"))
        assertEquals(tree(merge), tree("HEAD"))
        val reworded = git("rev-parse", "HEAD^2^")
        assertEquals(reworded, git("rev-parse", "HEAD^1^"))
        assertEquals("Reworded", message(reworded))
    }

    @Test
    fun `keeps a mergetag header with its continuation lines byte for byte`() {
        repository.commit("Base")
        val a = repository.commit("A", "a.txt")
        git("switch", "-q", "-c", "side", "HEAD^")
        val side = repository.commit("Side", "side.txt")
        git("switch", "-q", "feature")
        git("merge", "-q", "--no-ff", "-m", "Merge tag", "side")
        val tag =
            "mergetag object $side\n type commit\n tag v1\n tagger Tagger <tagger@example.com> 1577930645 +0100\n" +
                " \n Tag message.\n"
        val (headers, body) = raw("HEAD").split("\n\n", limit = 2)
        val content = "$headers\n$tag\n$body".toByteArray(Charsets.ISO_8859_1)
        val merge = git("hash-object", "-t", "commit", "-w", "--stdin", input = content)
        git("update-ref", "refs/heads/feature", merge)

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", a, "Reworded"))

        assertNotEquals(merge, head())
        assertEquals(copied(merge), copied("HEAD"))
        assertTrue("\n \n Tag message.\n" in raw("HEAD"), raw("HEAD"))
    }

    @Test
    fun `leaves the working tree and the index as they are`() {
        repository.commit("Base")
        val a = repository.commit("A", "a.txt")
        repository.commit("B", "b.txt")
        repo.resolve("a.txt").writeText("staged\n")
        git("add", "a.txt")
        repo.resolve("b.txt").writeText("unstaged\n")
        repo.resolve("new.txt").writeText("untracked\n")
        val status = git("status", "--porcelain=v2", "--untracked-files=all")
        val index = git("ls-files", "--stage")

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", a, "A reworded"))

        assertEquals(status, git("status", "--porcelain=v2", "--untracked-files=all"))
        assertEquals(index, git("ls-files", "--stage"))
        assertEquals("staged\n", repo.resolve("a.txt").readText())
        assertEquals("unstaged\n", repo.resolve("b.txt").readText())
        assertEquals("untracked\n", repo.resolve("new.txt").readText())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "origin/HEAD", "origin/Master without origin/HEAD", "local main", "detached HEAD", "another branch",
            "local branch tracking origin/HEAD", "local master tracking origin/master", "not on the branch",
            "rebase in progress", "too many commits after it",
        ],
    )
    fun `refuses to reword`(case: String) {
        repository.commit("Base")
        var target = repository.commit("Target", "t.txt")
        repository.commit("Later", "l.txt")
        var branch = "feature"
        val short = target.take(8)
        val reason =
            when (case) {
                "origin/HEAD" -> {
                    origin()
                    git("push", "-q", "origin", "$target:refs/heads/develop")
                    git("remote", "set-head", "origin", "develop")
                    "'$short' is already on origin/develop; its message can't be changed."
                }

                "origin/Master without origin/HEAD" -> {
                    origin()
                    git("push", "-q", "origin", "$target:refs/heads/Master")
                    "'$short' is already on origin/Master; its message can't be changed."
                }

                "local main" -> {
                    git("branch", "main", target)
                    "'$short' is already on main; its message can't be changed."
                }

                "local branch tracking origin/HEAD" -> {
                    origin()
                    git("push", "-q", "origin", "HEAD~2:refs/heads/develop")
                    git("remote", "set-head", "origin", "develop")
                    // Its commits after Base are not pushed yet.
                    git("branch", "develop")
                    git("branch", "-q", "--set-upstream-to=origin/develop", "develop")
                    "'$short' is already on develop; its message can't be changed."
                }

                "local master tracking origin/master" -> {
                    origin()
                    git("push", "-q", "origin", "HEAD~2:refs/heads/master")
                    git("branch", "master")
                    git("branch", "-q", "--set-upstream-to=origin/master", "master")
                    "'$short' is already on master; its message can't be changed."
                }

                "detached HEAD" -> {
                    git("switch", "-q", "--detach")
                    "'feature' is no longer the current branch."
                }

                "another branch" -> {
                    git("branch", "other")
                    branch = "other"
                    "'other' is no longer the current branch."
                }

                "not on the branch" -> {
                    git("switch", "-q", "-c", "side")
                    target = repository.commit("Side", "side.txt")
                    git("switch", "-q", "feature")
                    "'${target.take(8)}' is not on the current branch 'feature'."
                }

                "rebase in progress" -> {
                    git("rebase", "-x", "false", "HEAD~1", succeeds = false)
                    "A rebase is in progress; finish or abort it first."
                }

                else -> {
                    repository.commit("More", "m.txt")
                    repository.commit("Even more", "e.txt")
                    rewords.maxCommits = 2
                    "'$short' has more than 2 commits after it; change its message in a terminal."
                }
            }
        val before = tip()

        assertEquals(GitRewordCheck.Refused(reason), rewords.check(repo, branch, target))
        assertEquals(GitRewordResult.Failed(reason), rewords.reword(repo, branch, target, "New"))
        assertEquals(before, tip())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `rewords on a branch of another name that tracks the default branch`(originHead: Boolean) {
        val base = repository.commit("Base")
        origin()
        git("push", "-q", "origin", "$base:refs/heads/main")
        // Without origin/HEAD, origin/main is the default branch all the same.
        if (originHead) git("remote", "set-head", "origin", "main")
        git("switch", "-q", "-c", "fix-123", "--track", "origin/main")
        val first = repository.commit("First", "f.txt")
        repository.commit("Second", "s.txt")

        assertEquals(GitRewordCheck.Allowed("First", emptyList(), null), rewords.check(repo, "fix-123", first))
        assertIs<GitRewordResult.Done>(rewords.reword(repo, "fix-123", first, "Reworded"))
        assertEquals("Reworded", message("HEAD^"))
    }

    @Test
    fun `takes only lowercase full hashes`() {
        val hash = repository.commit("Base")

        for (bad in listOf(hash.uppercase(), hash.take(12))) {
            val check = assertFailsWith<IllegalArgumentException> { rewords.check(repo, "feature", bad) }
            val reword = assertFailsWith<IllegalArgumentException> { rewords.reword(repo, "feature", bad, "New") }
            assertEquals("not a commit hash: $bad", check.message)
            assertEquals("not a commit hash: $bad", reword.message)
        }
    }

    @Test
    fun `rewords a commit with as many commits after it as allowed`() {
        repository.commit("Base")
        val target = repository.commit("Target", "t.txt")
        repository.commit("Later", "l.txt")
        repository.commit("More", "m.txt")
        rewords.maxCommits = 2

        assertIs<GitRewordCheck.Allowed>(rewords.check(repo, "feature", target))
        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", target, "New"))
        assertEquals("New", message("HEAD~2"))
    }

    @Test
    fun `reads the commits to copy in several parts`() {
        val target = repository.commit("Target")
        // More hashes than fit into one part of cat-file's input (GitCli.MAX_INPUT), made by one git fast-import.
        val count = 400
        assertTrue(count + 1 > GitCli.MAX_INPUT / (target.length + 1), "the hashes fit into one part")
        val stream =
            buildString {
                for (i in 1..count) {
                    append("commit refs/heads/feature\ncommitter Committer <committer@example.com> 1577930645 +0100\n")
                    append("data <<END\nCommit $i\nEND\n")
                    if (i == 1) append("from $target\n")
                    append("M 100644 inline file.txt\ndata <<END\n$i\nEND\n\n")
                }
            }
        git("fast-import", "--quiet", input = stream.toByteArray(Charsets.UTF_8))
        git("reset", "-q", "--hard")
        val before = git("rev-list", "--reverse", "HEAD").lines()

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", target, "Reworded"))

        val after = git("rev-list", "--reverse", "HEAD").lines()
        assertEquals(count + 1, after.size)
        assertEquals("Reworded", message(after[0]))
        for (i in listOf(1, 200, count)) {
            assertEquals("Commit $i", message(after[i]))
            assertEquals(tree(before[i]), tree(after[i]))
            assertEquals(after[i - 1], parents(after[i]).single())
        }
    }

    @Test
    fun `fails and leaves the branch where it is if it moved meanwhile`() {
        repository.commit("Base")
        val a = repository.commit("A", "a.txt")
        var moved = ""
        rewords.beforeUpdate = { moved = repository.commit("Moved", "m.txt") }

        val failed = assertIs<GitRewordResult.Failed>(rewords.reword(repo, "feature", a, "Reworded"))

        assertTrue("refs/heads/feature" in failed.message, failed.message)
        assertEquals(moved, tip())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \n\n\t \n"])
    fun `refuses a blank message`(message: String) {
        repository.commit("Base")
        val a = repository.commit("A", "a.txt")

        assertEquals(GitRewordResult.Failed("The message is empty."), rewords.reword(repo, "feature", a, message))
        assertEquals(a, tip())
    }

    @ParameterizedTest
    @ValueSource(strings = ["Subject\n\nBody.", "\n\nSubject  \n\n\n\nBody.\t\n\n"])
    fun `writes nothing for the same message after the whitespace cleanup`(message: String) {
        repository.commit("Base")
        val a = repository.commit("Subject\n\nBody.", "a.txt")
        val objects = git("count-objects")

        assertEquals(GitRewordResult.Unchanged, rewords.reword(repo, "feature", a, message))
        assertEquals(a, tip())
        assertEquals(objects, git("count-objects"))
    }

    @Test
    fun `writes a non-ASCII message as UTF-8 and keeps the bytes of a later Latin-1 message`() {
        repository.commit("Base")
        val target = latin1Commit("Ältere Nachricht", "t.txt")
        val later = latin1Commit("Spätere Nachricht", "l.txt")
        assertTrue("encoding ISO-8859-1" in raw(target))

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", target, "Nachricht geändert ✓"))

        val reworded = git("rev-parse", "HEAD^")
        assertFalse("\nencoding " in raw(reworded))
        val utf8 = gitBytes("cat-file", "commit", reworded).toString(Charsets.UTF_8)
        assertTrue(utf8.endsWith("\n\nNachricht geändert ✓\n"), utf8)
        assertEquals(copied(later), copied("HEAD"))
    }

    /** Commits [file] with [message] stored as ISO-8859-1, with an `encoding` header; returns the commit's hash. */
    private fun latin1Commit(
        message: String,
        file: String,
    ): String {
        repo.resolve(file).writeText("$message\n")
        git("add", file)
        val messageFile = folder.resolve("message.txt")
        messageFile.writeBytes("$message\n".toByteArray(Charsets.ISO_8859_1))
        git("-c", "i18n.commitEncoding=ISO-8859-1", "commit", "-q", "-F", messageFile.toString())
        return head()
    }

    @ParameterizedTest
    @ValueSource(strings = ["gpgsig", "gpgsig-sha256"])
    fun `drops the signatures of the commits it copies`(header: String) {
        repository.commit("Base")
        val target = signed(repository.commit("Target", "t.txt"), header)
        val later = signed(repository.commit("Later", "l.txt"), header)
        assertTrue("\n$header " in raw(later))

        assertIs<GitRewordResult.Done>(rewords.reword(repo, "feature", target, "Reworded"))

        for (copy in listOf("HEAD^", "HEAD")) assertFalse("gpgsig" in raw(copy), raw(copy))
        assertEquals(copied(later).filterNot { it.startsWith("$header ") || it.startsWith(" ") }, copied("HEAD"))
    }

    /** [commit], the branch's tip, replaced by a copy with a fake [header] signature; returns the copy's hash. */
    private fun signed(
        commit: String,
        header: String,
    ): String {
        val signature = "$header -----BEGIN PGP SIGNATURE-----\n \n iQEzBAABCAAdFiEE\n -----END PGP SIGNATURE-----\n"
        val (headers, body) = raw(commit).split("\n\n", limit = 2)
        val content = "$headers\n$signature\n$body".toByteArray(Charsets.ISO_8859_1)
        val copy = git("hash-object", "-t", "commit", "-w", "--stdin", input = content)
        git("update-ref", "refs/heads/feature", copy, commit)
        return copy
    }

    @Test
    fun `check tells the message, the other branches that keep the commit and the upstream that has it`() {
        val base = repository.commit("Base")
        val target = repository.commit("Target\n\nBody.", "t.txt")
        git("branch", "other")
        git("branch", "side", base)
        origin()
        git("push", "-q", "origin", "$base:refs/heads/develop", "$target:refs/heads/main")
        git("push", "-q", "-u", "origin", "feature")
        // The remote's default branch is develop, so its main does not count as one.
        git("remote", "set-head", "origin", "develop")
        val unpushed = repository.commit("Unpushed", "u.txt")

        assertEquals(
            GitRewordCheck.Allowed("Target\n\nBody.", listOf("other"), "origin/feature"),
            rewords.check(repo, "feature", target),
        )
        assertEquals(GitRewordCheck.Allowed("Unpushed", emptyList(), null), rewords.check(repo, "feature", unpushed))
    }
}
