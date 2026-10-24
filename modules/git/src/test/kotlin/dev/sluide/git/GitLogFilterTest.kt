package dev.sluide.git

import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The log filters of [GitLog] with the real git; skipped if git is not installed. */
internal class GitLogFilterTest : GitLogFixture() {
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
        // Compared with its parent, not the empty tree.
        assertEquals(second, log.hashMatch(repo, GitLogFilter(text = second.take(8), paths = listOf("b.txt")))?.hash)
        assertNull(log.hashMatch(repo, GitLogFilter(text = second.take(8), paths = listOf("a.txt"))))
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
}
