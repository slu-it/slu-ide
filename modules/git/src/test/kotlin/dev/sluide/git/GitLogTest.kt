package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in a temporary repository; skipped if git is not installed. */
class GitLogTest {
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
        environment: Map<String, String> = emptyMap(),
        input: String = "",
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment() + environment)
                }.start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits with the given subject at [time] seconds after the epoch, as author and committer date. */
    private fun commit(
        subject: String,
        time: Long,
    ): String {
        val date = "@$time +0000"
        // The committer date decides the date order.
        val dates = mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date)
        git("commit", "-q", "--allow-empty", "-m", subject, environment = dates)
        return git("rev-parse", "HEAD")
    }

    private fun subjects(page: GitLogPage?) = page?.commits?.map { it.subject }

    @Test
    fun `reads the commits of all branches and tags newest first, with parents and author`() {
        val first = commit("First", 1000)
        git("switch", "-q", "-c", "feature")
        val feature = commit("Feature", 3000)
        git("switch", "-q", "main")
        val second = commit("Second", 2000)
        git("tag", "v1", second)
        git("switch", "-q", "--detach", first)
        val tagged = commit("Only tagged", 4000)
        git("tag", "old", tagged)
        git("switch", "-q", "main")

        val page = log.commits(repo, skip = 0, limit = 10)

        assertEquals(listOf("Only tagged", "Feature", "Second", "First"), subjects(page))
        assertTrue(page?.complete == true)
        val commits = page?.commits.orEmpty().associateBy { it.subject }
        assertEquals(listOf(first), commits["Feature"]?.parents)
        assertEquals(feature, commits["Feature"]?.hash)
        assertEquals(emptyList(), commits["First"]?.parents)
        assertEquals("Test", commits["First"]?.author)
        assertEquals("test@example.com", commits["First"]?.authorEmail)
        assertEquals(Instant.ofEpochSecond(1000), commits["First"]?.authorTime)
    }

    @Test
    fun `pages with skip and limit and says when there are no more`() {
        (1..5).forEach { commit("Commit $it", it * 1000L) }

        val first = log.commits(repo, skip = 0, limit = 2)
        val second = log.commits(repo, skip = 2, limit = 2)
        val last = log.commits(repo, skip = 4, limit = 2)

        assertEquals(listOf("Commit 5", "Commit 4"), subjects(first))
        assertFalse(first?.complete == true)
        assertEquals(listOf("Commit 3", "Commit 2"), subjects(second))
        assertFalse(second?.complete == true)
        assertEquals(listOf("Commit 1"), subjects(last))
        assertTrue(last?.complete == true)
        assertEquals(GitLogPage(emptyList(), complete = true), log.commits(repo, skip = 5, limit = 2))
    }

    @Test
    fun `leaves out stashes and includes a detached HEAD`() {
        repo.resolve("file.txt").writeText("one\n")
        git("add", "file.txt")
        val base = commit("Base", 1000)
        repo.resolve("file.txt").writeText("two\n")
        git("stash", "-q")
        git("switch", "-q", "--detach", base)
        commit("Detached", 2000)

        assertEquals(listOf("Detached", "Base"), subjects(log.commits(repo, skip = 0, limit = 10)))
    }

    @Test
    fun `reads subjects and authors as UTF-8 whatever the configured log output encoding`() {
        git("config", "i18n.logOutputEncoding", "ISO-8859-1")
        // Through files, as UTF-8: arguments of started processes take the JVM's locale, which may be plain ASCII.
        repo.resolve(".git/config").appendText("[user]\n\tname = Jürgen\n")
        val message = repo.resolve(".git/message").apply { writeText("Größe\n") }
        git("commit", "-q", "--allow-empty", "-F", message.toString())

        val commit = log.commits(repo, skip = 0, limit = 10)?.commits?.single()

        assertEquals("Größe", commit?.subject)
        assertEquals("Jürgen", commit?.author)
    }

    @Test
    fun `applies the mailmap to authors`() {
        repo.resolve(".mailmap").writeText("Real Name <real@example.com> <test@example.com>\n")
        commit("Mapped", 1000)

        val commit = log.commits(repo, skip = 0, limit = 10)?.commits?.single()

        assertEquals("Real Name", commit?.author)
        assertEquals("real@example.com", commit?.authorEmail)
    }

    @Test
    fun `a repository without commits has an empty, complete first page, also with a file named HEAD`() {
        repo.resolve("HEAD").writeText("")

        assertEquals(GitLogPage(emptyList(), complete = true), log.commits(repo, skip = 0, limit = 10))
    }

    @Test
    fun `an unborn branch still shows the other branches`() {
        commit("On main", 1000)
        git("switch", "-q", "--orphan", "fresh")

        assertEquals(listOf("On main"), subjects(log.commits(repo, skip = 0, limit = 10)))
    }

    @Test
    fun `reads branches, remote branches and lightweight and annotated tags, without symbolic refs`() {
        val first = commit("First", 1000)
        val second = commit("Second", 2000)
        git("tag", "light", first)
        git("tag", "-a", "-m", "Annotated", "annotated", second)
        git("branch", "feature", first)
        git("update-ref", "refs/remotes/origin/main", first)
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        val refs = log.refs(repo)

        assertEquals("refs/heads/main", refs?.currentBranch)
        assertEquals(listOf("HEAD", "main", "annotated"), refs?.at(second)?.map { it.shortName })
        assertEquals(listOf("feature", "origin/main", "light"), refs?.at(first)?.map { it.shortName })
        assertEquals(
            listOf(GitRefKind.LOCAL_BRANCH, GitRefKind.REMOTE_BRANCH, GitRefKind.TAG),
            refs?.at(first)?.map { it.kind },
        )
        assertEquals(6, refs?.refs?.size, "no refs/remotes/origin/HEAD: ${refs?.refs}")
    }

    @Test
    fun `a detached HEAD has no current branch`() {
        val first = commit("First", 1000)
        commit("Second", 2000)
        git("switch", "-q", "--detach", first)

        val refs = log.refs(repo)

        assertNull(refs?.currentBranch)
        assertEquals(listOf(GitRef("HEAD", GitRefKind.HEAD, first)), refs?.at(first))
    }

    @Test
    fun `a repository without commits has no refs and no HEAD`() {
        assertEquals(GitRefs(emptyList(), "refs/heads/main"), log.refs(repo))
    }

    @Test
    fun `reads the details of a commit with a multi-line message and another committer`() {
        val message = repo.resolve(".git/message").apply { writeText("Subject line\n\nFirst body line.\nSecond.\n") }
        val environment =
            mapOf(
                "GIT_AUTHOR_DATE" to "@1000 +0000",
                "GIT_COMMITTER_DATE" to "@2000 +0000",
                "GIT_COMMITTER_NAME" to "Committer",
                "GIT_COMMITTER_EMAIL" to "committer@example.com",
            )
        val parent = commit("Parent", 500)
        git("commit", "-q", "--allow-empty", "-F", message.toString(), environment = environment)
        val hash = git("rev-parse", "HEAD")

        val details = log.details(repo, hash)

        assertEquals(hash, details?.commit?.hash)
        assertEquals(listOf(parent), details?.commit?.parents)
        assertEquals("Subject line", details?.commit?.subject)
        assertEquals("Test", details?.commit?.author)
        assertEquals("test@example.com", details?.commit?.authorEmail)
        assertEquals(Instant.ofEpochSecond(1000), details?.commit?.authorTime)
        assertEquals("Committer", details?.committer)
        assertEquals("committer@example.com", details?.committerEmail)
        assertEquals(Instant.ofEpochSecond(2000), details?.committerTime)
        assertEquals("Subject line\n\nFirst body line.\nSecond.", details?.message)
    }

    @Test
    fun `details of an unknown commit fail, and only hashes are accepted`() {
        commit("First", 1000)

        assertNull(log.details(repo, "0".repeat(40)))
        assertFailsWith<IllegalArgumentException> { log.details(repo, "HEAD") }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "abc") }
        val hash = git("rev-parse", "HEAD")
        assertFailsWith<IllegalArgumentException>("abbreviated") { log.details(repo, hash.take(12)) }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "${hash}0") }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "--all") }
    }

    /** Writes the files (path to content, `null` deletes it), stages everything and commits; gives the commit. */
    private fun commitFiles(
        subject: String,
        vararg files: Pair<String, String?>,
    ): String {
        for ((path, content) in files) {
            val file = repo.resolve(path)
            if (content == null) {
                git("rm", "-q", path)
            } else {
                file.parent.createDirectories()
                file.writeText(content)
            }
        }
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    private val lines = (1..20).joinToString("") { "line $it\n" }

    @Test
    fun `reads the added, modified, deleted and renamed files of a commit, with their diffs`() {
        val parent =
            commitFiles(
                "First",
                "keep.txt" to "one\ntwo\nthree\n",
                "gone.txt" to "a\nb\n",
                "old/name.txt" to lines,
                "source.txt" to lines.uppercase(),
            )
        val commit =
            commitFiles(
                "Second",
                "keep.txt" to "one\nTWO\nthree\nfour\n",
                "gone.txt" to null,
                "old/name.txt" to null,
                "new/name.txt" to lines.replace("line 5\n", "line five\n"),
                "added.txt" to "x\ny\nz",
                // A copy: -M finds renames only, so it is added.
                "copy.txt" to lines.uppercase(),
            )

        val files = log.changedFiles(repo, commit, parent)

        assertEquals(
            listOf(
                GitChangedFile(GitChange.ADDED, "added.txt"),
                GitChangedFile(GitChange.ADDED, "copy.txt"),
                GitChangedFile(GitChange.DELETED, "gone.txt"),
                GitChangedFile(GitChange.MODIFIED, "keep.txt"),
                GitChangedFile(GitChange.RENAMED, "new/name.txt", "old/name.txt"),
            ),
            files?.sortedBy { it.path },
        )
        val byPath = files.orEmpty().associateBy { it.path }

        fun diff(path: String) = log.fileDiff(repo, commit, parent, byPath.getValue(path))

        assertEquals(
            GitFileDiff.Text("", "x\ny\nz", listOf(GitHunk(0 until 0, 0 until 3))),
            diff("added.txt"),
        )
        assertEquals(GitFileDiff.Text("a\nb\n", "", listOf(GitHunk(0 until 2, 0 until 0))), diff("gone.txt"))
        assertEquals(
            GitFileDiff.Text(
                "one\ntwo\nthree\n",
                "one\nTWO\nthree\nfour\n",
                listOf(GitHunk(1 until 2, 1 until 2), GitHunk(3 until 3, 3 until 4)),
            ),
            diff("keep.txt"),
        )
        val renamed = assertIs<GitFileDiff.Text>(diff("new/name.txt"))
        assertEquals(lines, renamed.original)
        assertEquals(listOf(GitHunk(4 until 5, 4 until 5)), renamed.hunks)
    }

    @Test
    fun `a copy is compared with its source, an unchanged rename has no hunks`() {
        val parent = commitFiles("First", "source.txt" to lines, "old.txt" to "same\n")
        val commit =
            commitFiles(
                "Second",
                "copy.txt" to lines.replace("line 2\n", "line two\n"),
                "old.txt" to null,
                "new.txt" to "same\n",
            )

        val copy = log.fileDiff(repo, commit, parent, GitChangedFile(GitChange.COPIED, "copy.txt", "source.txt"))
        val rename = log.fileDiff(repo, commit, parent, GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"))

        assertEquals(listOf(GitHunk(1 until 2, 1 until 2)), assertIs<GitFileDiff.Text>(copy).hunks)
        assertEquals(lines, copy.original)
        assertEquals(GitFileDiff.Text("same\n", "same\n", emptyList()), rename)
        assertEquals(
            listOf(
                GitChangedFile(GitChange.RENAMED, "new.txt", "old.txt"),
                GitChangedFile(GitChange.ADDED, "copy.txt"),
            ),
            log.changedFiles(repo, commit, parent)?.sortedByDescending { it.change == GitChange.RENAMED },
        )
    }

    @Test
    fun `a root commit adds every file`() {
        val root = commitFiles("First", "a.txt" to "a\n", "dir/b.txt" to "")

        assertEquals(
            listOf(GitChangedFile(GitChange.ADDED, "a.txt"), GitChangedFile(GitChange.ADDED, "dir/b.txt")),
            log.changedFiles(repo, root, null),
        )
        assertEquals(
            GitFileDiff.Text("", "a\n", listOf(GitHunk(0 until 0, 0 until 1))),
            log.fileDiff(repo, root, null, GitChangedFile(GitChange.ADDED, "a.txt")),
        )
        assertEquals(
            GitFileDiff.Text("", "", emptyList()),
            log.fileDiff(repo, root, null, GitChangedFile(GitChange.ADDED, "dir/b.txt")),
        )
    }

    @Test
    fun `a merge is compared with its first parent`() {
        commitFiles("Base", "main.txt" to "main\n", "feature.txt" to "feature\n")
        git("switch", "-q", "-c", "feature")
        commitFiles("Feature", "feature.txt" to "feature changed\n")
        git("switch", "-q", "main")
        val first = commitFiles("Main", "main.txt" to "main changed\n")
        git("merge", "-q", "--no-ff", "--no-edit", "feature")
        val merge = git("rev-parse", "HEAD")

        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, "feature.txt")), log.changedFiles(repo, merge, first))
        assertEquals(
            GitFileDiff.Text("feature\n", "feature changed\n", listOf(GitHunk(0 until 1, 0 until 1))),
            log.fileDiff(repo, merge, first, GitChangedFile(GitChange.MODIFIED, "feature.txt")),
        )
    }

    @Test
    fun `binary files are not compared, nor files above the size limit`() {
        val parent = commitFiles("First", "text.txt" to "short\n")
        repo.resolve("data.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
        val commit = commitFiles("Second", "text.txt" to "a longer text\n")

        assertEquals(
            GitFileDiff.Binary,
            log.fileDiff(repo, commit, parent, GitChangedFile(GitChange.ADDED, "data.bin")),
        )
        val modified = GitChangedFile(GitChange.MODIFIED, "text.txt")
        assertEquals(GitFileDiff.TooLarge, log.fileDiff(repo, commit, parent, modified, maxSize = 10))
        assertIs<GitFileDiff.Text>(log.fileDiff(repo, commit, parent, modified, maxSize = 14))
    }

    @Test
    fun `paths with spaces and non-ASCII characters are read as they are`() {
        val parent = commitFiles("First", "plain.txt" to "plain\n")
        // Written through git's standard input, as UTF-8: file names and arguments of the JVM take its locale, which
        // may be plain ASCII.
        val blob = git("hash-object", "-w", "--stdin", input = "Größe\n")
        git("update-index", "--index-info", input = "100644 $blob\tsome folder/Größe ä.txt\n")
        git("commit", "-q", "-m", "Second")
        val commit = git("rev-parse", "HEAD")

        val files = log.changedFiles(repo, commit, parent)

        assertEquals(listOf(GitChangedFile(GitChange.ADDED, "some folder/Größe ä.txt")), files)
        assertEquals(
            GitFileDiff.Text("", "Größe\n", listOf(GitHunk(0 until 0, 0 until 1))),
            log.fileDiff(repo, commit, parent, files.orEmpty().single()),
        )
    }

    @Test
    fun `a submodule gives the commits it points at before and after`() {
        val old = "1".repeat(40)
        val new = "2".repeat(40)
        val first = commitFiles("First", "a.txt" to "a\n")
        git("update-index", "--add", "--cacheinfo", "160000,$old,lib")
        git("commit", "-q", "-m", "Add")
        val added = git("rev-parse", "HEAD")
        git("update-index", "--cacheinfo", "160000,$new,lib")
        git("commit", "-q", "-m", "Move")
        val moved = git("rev-parse", "HEAD")
        git("update-index", "--force-remove", "lib")
        git("commit", "-q", "-m", "Remove")
        val removed = git("rev-parse", "HEAD")

        val modified = log.changedFiles(repo, moved, added)?.single()
        assertEquals(GitChangedFile(GitChange.MODIFIED, "lib"), modified)
        assertEquals(
            GitFileDiff.Submodule(old, new),
            log.fileDiff(repo, moved, added, GitChangedFile(GitChange.MODIFIED, "lib")),
        )
        assertEquals(
            GitFileDiff.Submodule(null, old),
            log.fileDiff(repo, added, first, GitChangedFile(GitChange.ADDED, "lib")),
        )
        assertEquals(
            GitFileDiff.Submodule(new, null),
            log.fileDiff(repo, removed, moved, GitChangedFile(GitChange.DELETED, "lib")),
        )
    }

    @Test
    fun `a path with a line break gives no diff, never another file's`() {
        val first = commitFiles("First", "secret.txt" to "secret\n")
        val blob = git("hash-object", "-w", "--stdin", input = "harmless\n")
        // The name would make git read "<commit>:x" and "HEAD:secret.txt" if it went to git line by line.
        git("update-index", "-z", "--index-info", input = "100644 $blob\tx\nHEAD:secret.txt\u0000")
        git("commit", "-q", "-m", "Second")
        val second = git("rev-parse", "HEAD")

        val file = assertNotNull(log.changedFiles(repo, second, first)?.single())

        assertEquals(GitChangedFile(GitChange.ADDED, "x\nHEAD:secret.txt"), file)
        assertNull(log.fileDiff(repo, second, first, file))
        assertNull(
            log.fileDiff(repo, second, first, GitChangedFile(GitChange.RENAMED, "secret.txt", "x\nHEAD:secret.txt")),
        )
    }

    @Test
    fun `a file whose mode only changed has the same text on both sides and no hunks`() {
        val first = commitFiles("First", "run.sh" to "echo\n")
        git("update-index", "--chmod=+x", "run.sh")
        git("commit", "-q", "-m", "Executable")
        val second = git("rev-parse", "HEAD")

        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, "run.sh")), log.changedFiles(repo, second, first))
        assertEquals(
            GitFileDiff.Text("echo\n", "echo\n", emptyList()),
            log.fileDiff(repo, second, first, GitChangedFile(GitChange.MODIFIED, "run.sh")),
        )
    }

    @Test
    fun `changed files and diffs take only full hashes, and fail for unknown commits`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        val second = commitFiles("Second", "a.txt" to "b\n")
        val file = GitChangedFile(GitChange.MODIFIED, "a.txt")

        assertFailsWith<IllegalArgumentException> { log.changedFiles(repo, "HEAD", first) }
        assertFailsWith<IllegalArgumentException> { log.changedFiles(repo, second, "HEAD~1") }
        assertFailsWith<IllegalArgumentException> { log.fileDiff(repo, second.take(12), first, file) }
        assertFailsWith<IllegalArgumentException> { log.fileDiff(repo, second, "--all", file) }
        assertNull(log.changedFiles(repo, "0".repeat(40), first))
        assertNull(log.fileDiff(repo, second, first, GitChangedFile(GitChange.MODIFIED, "missing.txt")))
        assertNull(log.fileDiff(repo, "0".repeat(40), first, file))
    }

    /** Commits with [subject] (and a [body]) by [author] <[email]> at [time] seconds after the epoch. */
    private fun commitBy(
        subject: String,
        time: Long,
        author: String = "Test",
        email: String = "test@example.com",
        body: String? = null,
    ): String {
        val date = "@$time +0000"
        val environment =
            mapOf(
                "GIT_AUTHOR_DATE" to date,
                "GIT_COMMITTER_DATE" to date,
                "GIT_AUTHOR_NAME" to author,
                "GIT_AUTHOR_EMAIL" to email,
            )
        val message = listOfNotNull(subject, body).flatMap { listOf("-m", it) }
        git("commit", "-q", "--allow-empty", *message.toTypedArray(), environment = environment)
        return git("rev-parse", "HEAD")
    }

    private fun filtered(filter: GitLogFilter) = subjects(log.commits(repo, filter, 0, 100))

    @Test
    fun `the text matches any line of the message, literally unless regex, ignoring case unless match case`() {
        commitBy("Fix a.b", 1000)
        commitBy("Fix axb", 2000)
        commitBy("Other", 3000, body = "Mentions A.B in the body")
        commitBy("Brackets [x] (y) {z} | ^ $ \\ * + ?", 4000)

        assertEquals(listOf("Other", "Fix a.b"), filtered(GitLogFilter(text = " a.b ")))
        assertEquals(listOf("Fix a.b"), filtered(GitLogFilter(text = "a.b", matchCase = true)))
        assertEquals(listOf("Other", "Fix axb", "Fix a.b"), filtered(GitLogFilter(text = "a.b", regex = true)))
        assertEquals(listOf("Fix axb"), filtered(GitLogFilter(text = "^fix a[^.]b$", regex = true)))
        assertEquals(emptyList(), filtered(GitLogFilter(text = "^fix", regex = true, matchCase = true)))
        assertEquals(
            listOf("Brackets [x] (y) {z} | ^ $ \\ * + ?"),
            filtered(GitLogFilter(text = "[x] (y) {z} | ^ $ \\ * + ?")),
        )
        assertNull(log.commits(repo, GitLogFilter(text = "(", regex = true), 0, 100), "an invalid regex fails")
    }

    @Test
    fun `the text may have characters the JVM cannot pass as arguments`() {
        // Through a file, as UTF-8: arguments of started processes take the JVM's locale, which may be plain ASCII.
        val message = repo.resolve(".git/message").apply { writeText("Gr\u00f6\u00dfe \u00e4ndern\n") }
        git("commit", "-q", "--allow-empty", "-F", message.toString())
        commitBy("Grosse", 2000)

        assertEquals(listOf("Gr\u00f6\u00dfe \u00e4ndern"), filtered(GitLogFilter(text = "gr\u00f6\u00dfe")))
        assertEquals(listOf("Gr\u00f6\u00dfe \u00e4ndern"), filtered(GitLogFilter(text = "\u00e4ndern$", regex = true)))
    }

    @Test
    fun `revisions limit the log to what they reach, a deleted branch is skipped`() {
        val base = commitBy("Base", 1000)
        git("switch", "-q", "-c", "feature")
        commitBy("Feature", 2000)
        git("switch", "-q", "main")
        commitBy("Main", 3000)
        git("tag", "v1", base)
        git("switch", "-q", "-c", "other", base)
        commitBy("Other", 4000)
        git("switch", "-q", "main")

        assertEquals(listOf("Other", "Main", "Feature", "Base"), filtered(GitLogFilter()))
        assertEquals(listOf("Feature", "Base"), filtered(GitLogFilter(revisions = listOf("refs/heads/feature"))))
        assertEquals(
            listOf("Main", "Feature", "Base"),
            filtered(GitLogFilter(revisions = listOf("refs/heads/gone", "HEAD", "refs/heads/feature"))),
        )
        assertEquals(listOf("Base"), filtered(GitLogFilter(revisions = listOf("refs/tags/v1"))))
        assertEquals(emptyList(), filtered(GitLogFilter(revisions = listOf("refs/heads/gone"))))
    }

    @Test
    fun `a branch with non-ASCII characters is read from the standard input`() {
        commitBy("Base", 1000)
        val green = "refs/heads/gr\u00fcn"
        // Through git's standard input, as UTF-8 (see above).
        git("update-ref", "--stdin", input = "create $green ${commitBy("Green", 2000)}\n")
        git("reset", "-q", "--hard", "HEAD~1")
        commitBy("Main", 3000)

        assertEquals(listOf("Green", "Base"), filtered(GitLogFilter(revisions = listOf(green))))
    }

    @Test
    fun `users match the name or email after the mailmap, ignoring case, and combine with the text`() {
        repo.resolve(".mailmap").writeText("Ann Real <ann@new.example> <ann@old.example>\n")
        // The users are matched as mapped, as the list shows them, whatever the configuration says.
        git("config", "log.mailmap", "false")
        commitBy("By Test", 1000)
        commitBy("Fix by Ann", 2000, author = "Ann Other", email = "ann@old.example")
        commitBy("By Bob", 3000, author = "Bob", email = "bob@example.com")
        commitBy("Bob's fix", 4000, author = "Bob", email = "bob@example.com")
        commitBy("By Annabel", 5000, author = "Annabel", email = "annabel@example.com")

        assertEquals(listOf("Fix by Ann"), filtered(GitLogFilter(users = listOf("ann real"))))
        assertEquals(listOf("Fix by Ann"), filtered(GitLogFilter(users = listOf("ANN@new.example"))))
        assertEquals(emptyList(), filtered(GitLogFilter(users = listOf("Ann Other"))), "mapped")
        assertEquals(emptyList(), filtered(GitLogFilter(users = listOf("Ann"))), "the whole name")
        assertEquals(
            listOf("Bob's fix", "By Bob", "Fix by Ann"),
            filtered(GitLogFilter(users = listOf("bob", "Ann Real"))),
        )
        assertEquals(
            listOf("Bob's fix", "Fix by Ann"),
            filtered(GitLogFilter(text = "fix", users = listOf("bob", "Ann Real"))),
        )
        // Match case is about the text: the users still ignore case.
        assertEquals(listOf("By Bob"), filtered(GitLogFilter(text = "By", matchCase = true, users = listOf("BOB"))))
    }

    @Test
    fun `paths limit the log with rewritten parents, also with non-ASCII characters`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        commitFiles("Other", "b.txt" to "b\n")
        val second = commitFiles("Second", "dir/sub/c.txt" to "c\n", "a.txt" to "a2\n")
        commitFiles("Third", "dir/d.txt" to "d\n")
        val blob = git("hash-object", "-w", "--stdin", input = "x\n")
        git("update-index", "--add", "--index-info", input = "100644 $blob\tgr\u00fcn.txt\n")
        git("commit", "-q", "-m", "Green")

        val page = log.commits(repo, GitLogFilter(paths = listOf("a.txt")), 0, 100)
        assertEquals(listOf("Second", "First"), subjects(page))
        assertEquals(listOf(first), page?.commits?.first()?.parents, "the nearest commit shown")
        assertEquals(emptyList(), page?.commits?.last()?.parents)
        assertEquals(listOf("Third", "Second"), filtered(GitLogFilter(paths = listOf("dir"))))
        assertEquals(listOf("Third", "Second", "First"), filtered(GitLogFilter(paths = listOf("dir", "a.txt"))))
        assertEquals(listOf("Green"), filtered(GitLogFilter(paths = listOf("gr\u00fcn.txt"))))
        // Literal paths: no patterns.
        assertEquals(emptyList(), filtered(GitLogFilter(paths = listOf("*.txt"))))
        assertEquals(listOf("Second"), filtered(GitLogFilter(paths = listOf("dir"), revisions = listOf(second))))
    }

    @Test
    fun `a single path does not follow renames, whatever log follow says`() {
        git("config", "log.follow", "true")
        commitFiles("First", "a.txt" to lines)
        val renamed = commitFiles("Renamed", "a.txt" to null, "b.txt" to lines)
        commitFiles("Changed", "b.txt" to lines + "more\n")

        val page = log.commits(repo, GitLogFilter(paths = listOf("b.txt")), 0, 10)

        assertEquals(listOf("Changed", "Renamed"), subjects(page))
        assertEquals(listOf(renamed), page?.commits?.first()?.parents)
        assertEquals(emptyList(), page?.commits?.last()?.parents)
    }

    @Test
    fun `a path may start with a dash`() {
        commitFiles("First", "a.txt" to "a\n")
        commitFiles("Dash", "-x.txt" to "x\n")

        assertEquals(listOf("Dash"), filtered(GitLogFilter(paths = listOf("-x.txt"))))
    }

    @Test
    fun `text and user filters page with skip`() {
        for (index in 1..5) {
            commitBy("Fix $index", index * 1000L, author = "Bob", email = "a+b@example.com")
            commitBy("Fix by someone $index", index * 1000L + 100, author = "Someone", email = "s@example.com")
            commitBy("Other $index", index * 1000L + 200, author = "Bob", email = "a+b@example.com")
        }
        val filter = GitLogFilter(text = "FIX", users = listOf("A+B@example.com"))

        val pages = (0..4 step 2).map { skip -> log.commits(repo, filter, skip, 2) }

        assertEquals(listOf(listOf("Fix 5", "Fix 4"), listOf("Fix 3", "Fix 2"), listOf("Fix 1")), pages.map(::subjects))
        assertEquals(listOf(false, false, true), pages.map { it?.complete })
        assertEquals(emptyList(), filtered(GitLogFilter(users = listOf("aab@example.com"))), "+ is no quantifier")
    }

    @Test
    fun `a line break in a revision or path, a revision like an option, or too much input fails without git`() {
        commitBy("First", 1000)

        assertNull(log.commits(repo, GitLogFilter(paths = listOf("a\nb")), 0, 10))
        assertNull(log.commits(repo, GitLogFilter(revisions = listOf("HEAD\nrefs/heads/x")), 0, 10))
        assertNull(log.commits(repo, GitLogFilter(revisions = listOf("--all")), 0, 10))
        assertNull(log.commits(repo, GitLogFilter(paths = List(5000) { "some/longer/path/$it" }), 0, 10))
        assertEquals(emptyList(), filtered(GitLogFilter(paths = List(500) { "some/path/$it" })))
    }

    /** main: Base, Main, Merge (of a deleted branch: Feature); returns the merge. */
    private fun mergeHistory(): String {
        commitBy("Base", 1000)
        git("switch", "-q", "-c", "feature")
        commitBy("Feature", 2000)
        git("switch", "-q", "main")
        commitBy("Main", 3000)
        val date = mapOf("GIT_AUTHOR_DATE" to "@4000 +0000", "GIT_COMMITTER_DATE" to "@4000 +0000")
        git("merge", "-q", "--no-ff", "-m", "Merge", "feature", environment = date)
        git("branch", "-q", "-d", "feature")
        return git("rev-parse", "HEAD")
    }

    @Test
    fun `first parent only, no merges and the topological order`() {
        val merge = mergeHistory()

        val firstParent = log.commits(repo, GitLogFilter(firstParent = true), 0, 10)
        assertEquals(listOf("Merge", "Main", "Base"), subjects(firstParent))
        assertEquals(
            2,
            firstParent
                ?.commits
                ?.first()
                ?.parents
                ?.size,
            "git still gives all parents",
        )
        assertEquals(merge, firstParent?.commits?.first()?.hash)
        assertEquals(listOf("Main", "Feature", "Base"), filtered(GitLogFilter(noMerges = true)))
        assertEquals(listOf("Merge", "Main", "Feature", "Base"), filtered(GitLogFilter()))
        assertEquals(
            listOf("Merge", "Feature", "Main", "Base"),
            filtered(GitLogFilter(order = GitLogOrder.TOPOLOGICAL)),
        )
    }

    @Test
    fun `a hash prefix names its commit if it passes the users, paths and no merges`() {
        val first = commitFiles("First", "a.txt" to "a\n")
        val second = commitFiles("Second", "b.txt" to "b\n")
        git("switch", "-q", "-c", "other")
        val other = commitFiles("Other", "c.txt" to "c\n")
        git("switch", "-q", "main")

        assertEquals(second, log.hashMatch(repo, GitLogFilter(text = " ${second.take(7).uppercase()} "))?.hash)
        assertEquals(listOf(first), log.hashMatch(repo, GitLogFilter(text = second))?.parents)
        assertEquals(first, log.hashMatch(repo, GitLogFilter(text = first.take(4), paths = listOf("a.txt")))?.hash)
        // Branches are not applied to it.
        assertEquals(
            other,
            log.hashMatch(repo, GitLogFilter(text = other.take(10), revisions = listOf("refs/heads/main")))?.hash,
        )
        assertNull(log.hashMatch(repo, GitLogFilter(text = first.take(4), paths = listOf("b.txt"))))
        assertNull(log.hashMatch(repo, GitLogFilter(text = first.take(8), users = listOf("someone else"))))
        assertEquals(first, log.hashMatch(repo, GitLogFilter(text = first.take(8), users = listOf("TEST")))?.hash)
        assertNull(log.hashMatch(repo, GitLogFilter(text = first.take(3))), "too short")
        assertNull(log.hashMatch(repo, GitLogFilter(text = "not a hash")))
        assertNull(log.hashMatch(repo, GitLogFilter()))
        val unknown = unusedPrefix(first, second, other)
        assertNull(log.hashMatch(repo, GitLogFilter(text = unknown)))
        // A branch whose name looks like a hash is not a hash.
        git("branch", unknown, first)
        assertNull(log.hashMatch(repo, GitLogFilter(text = unknown)))
    }

    /** Four hexadecimal characters none of the [hashes] starts with. */
    private fun unusedPrefix(vararg hashes: String) =
        (0..0xffff).map { "%04x".format(it) }.first { prefix -> hashes.none { it.startsWith(prefix) } }

    @Test
    fun `a merge does not match by hash with no merges, nor an ambiguous prefix`() {
        val merge = mergeHistory()
        assertEquals(merge, log.hashMatch(repo, GitLogFilter(text = merge.take(8)))?.hash)
        assertNull(log.hashMatch(repo, GitLogFilter(text = merge.take(8), noMerges = true)))

        // Enough commits for two to share their first four characters.
        val input =
            buildString {
                for (index in 1..2000) {
                    append("commit refs/heads/many\ncommitter A <a@example.com> ${1000 + index} +0000\n")
                    append("data ${"Many $index".length}\nMany $index\n")
                }
            }
        git("fast-import", "--quiet", "--date-format=raw", input = input)
        val prefix =
            git("rev-list", "refs/heads/many")
                .lines()
                .groupBy { it.take(4) }
                .entries
                .first { it.value.size > 1 }
                .key
        assertNull(log.hashMatch(repo, GitLogFilter(text = prefix)))
    }

    @Test
    fun `reads the user name of the repository`() {
        assertEquals("Test", log.userName(repo))
        git("config", "user.name", "  Spaced Name ")
        assertEquals("Spaced Name", log.userName(repo))
        git("config", "--unset", "user.name")
        assertNull(log.userName(repo))
    }

    @Test
    fun `reads the folders and files of HEAD, none while it has no commit`() {
        assertEquals(emptyList(), log.paths(repo))
        commitFiles("First", "a.txt" to "a\n", "dir/sub/b.txt" to "b\n", "dir/c d.txt" to "c\n")
        val blob = git("hash-object", "-w", "--stdin", input = "x\n")
        git("update-index", "--add", "--index-info", input = "100644 $blob\tgr\u00fcn.txt\n")
        git("commit", "-q", "-m", "Green")

        assertEquals(
            listOf(
                GitTreePath("a.txt", folder = false),
                GitTreePath("dir", folder = true),
                GitTreePath("dir/c d.txt", folder = false),
                GitTreePath("dir/sub", folder = true),
                GitTreePath("dir/sub/b.txt", folder = false),
                GitTreePath("gr\u00fcn.txt", folder = false),
            ),
            log.paths(repo),
        )
    }

    @Test
    fun `filters are default, hide commits or not`() {
        assertTrue(GitLogFilter().isDefault)
        assertTrue(GitLogFilter(text = "  ", regex = true, matchCase = true, order = GitLogOrder.TOPOLOGICAL).isDefault)
        assertFalse(GitLogFilter().hidesCommits)
        assertFalse(GitLogFilter(firstParent = true).isDefault)
        assertFalse(GitLogFilter(firstParent = true).hidesCommits)
        assertFalse(GitLogFilter(revisions = listOf("HEAD")).hidesCommits)
        assertFalse(GitLogFilter(paths = listOf("a")).hidesCommits)
        assertTrue(GitLogFilter(text = "x").hidesCommits)
        assertTrue(GitLogFilter(users = listOf("x")).hidesCommits)
        assertTrue(GitLogFilter(noMerges = true).hidesCommits)
        assertFalse(GitLogFilter(noMerges = true).isDefault)
    }

    @Test
    fun `fails outside a repository and without git`() {
        val plain = repo.resolve("plain").createDirectories()
        val outside = GitLog(GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to repo.toString())))
        assertNull(outside.commits(plain, skip = 0, limit = 10))
        assertNull(
            GitLog(GitCli(repo.resolve("no-such-git").toString(), System.getenv())).commits(repo, skip = 0, limit = 10),
        )
        assertNull(outside.refs(plain))
        assertNull(outside.commits(plain, GitLogFilter(text = "x", paths = listOf("a")), 0, 10))
        assertNull(outside.hashMatch(plain, GitLogFilter(text = "abcd")))
        assertNull(outside.userName(plain))
        assertNull(outside.paths(plain))
        assertNull(outside.details(plain, "a".repeat(40)))
        assertNull(outside.changedFiles(plain, "a".repeat(40), null))
        assertNull(outside.fileDiff(plain, "a".repeat(40), null, GitChangedFile(GitChange.ADDED, "a.txt")))
    }
}
