package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.GitCommandLogSink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adds, renames, removes and reads remotes with the real git in temporary folders; skipped if git is not installed.
 * The behavior was measured with git 2.56.1.
 */
class GitRemoteConfigTest {
    @TempDir
    lateinit var folder: Path

    private val upstream get() = folder.resolve("upstream")
    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val remotes = GitRemotes(git)

    @BeforeEach
    fun createRepositories() {
        assumeTrue(git.available, "git is not installed")
        upstream.createDirectories()
        git("init", "-q", "-b", "main", directory = upstream)
        git("config", "user.name", "Test", directory = upstream)
        git("config", "user.email", "test@example.com", directory = upstream)
        upstream.resolve("file.txt").writeText("First\n")
        git("add", "file.txt", directory = upstream)
        git("commit", "-q", "-m", "First", directory = upstream)
        git("branch", "dev", directory = upstream)
        git("branch", "feature/a", directory = upstream)
        git("clone", "-q", upstream.toString(), repo.toString(), directory = folder)
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
        expect: Set<Int> = setOf(0),
    ): String {
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
        val exit = process.waitFor()
        assertTrue(exit in expect, "exit $exit: git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun lines(vararg arguments: String) = git(*arguments).lines().filter(String::isNotEmpty)

    private fun remoteRefs() = lines("for-each-ref", "--format=%(refname)", "refs/remotes/")

    private fun remoteNames() = lines("remote")

    private fun config(key: String) =
        git("config", "--get-all", key, expect = setOf(0, 1)).lines().filter(String::isNotEmpty)

    private fun setFetch(
        remote: String,
        vararg refspecs: String,
    ) {
        git("config", "--unset-all", "remote.$remote.fetch")
        refspecs.forEach { git("config", "--add", "remote.$remote.fetch", it) }
    }

    @Test
    fun `adds a remote with its URL and the default fetch refspec`() {
        val result = remotes.add(repo, "upstream", "https://example.com/x.git")

        assertTrue(result.success, result.message)
        assertEquals(listOf("https://example.com/x.git"), config("remote.upstream.url"))
        assertEquals(listOf("+refs/heads/*:refs/remotes/upstream/*"), config("remote.upstream.fetch"))
    }

    @Test
    fun `adds a remote whose URL starts with a dash`() {
        val result = remotes.add(repo, "odd", "-weird")

        assertTrue(result.success, result.message)
        assertEquals(listOf("-weird"), config("remote.odd.url"))
    }

    @Test
    fun `a name starting with a dash is refused before git runs`() {
        // git takes such a name after --end-of-options: each call would succeed had git run.
        git("remote", "add", "--end-of-options", "-x", "file:///x")
        val result = remotes.add(repo, "-z", "https://example.com/x.git")

        assertFalse(result.success)
        assertContains(result.message, "starts with '-'")
        assertFalse(remotes.rename(repo, "origin", "-y").result.success)
        assertFalse(remotes.rename(repo, "-x", "y").result.success)
        assertFalse(remotes.remove(repo, "-x").success)
        assertFalse(remotes.setUrls(repo, "-x", "u", null, false).success)
        assertEquals(listOf("-x", "origin"), remoteNames().sorted())
    }

    @Test
    fun `adding a taken name fails with git's message`() {
        val result = remotes.add(repo, "origin", "https://example.com/x.git")

        assertFalse(result.success)
        assertContains(result.message, "already exists")
    }

    @Test
    fun `renaming moves the configuration, the branches' remotes, the push default and the refs`() {
        git("config", "branch.main.pushRemote", "origin")
        git("config", "remote.pushDefault", "origin")

        val renamed = remotes.rename(repo, "origin", "up")

        assertTrue(renamed.result.success, renamed.result.message)
        assertTrue(renamed.refspecsUpdated)
        assertFalse(renamed.partial)
        assertEquals(listOf("up"), remoteNames())
        assertEquals(listOf("up"), config("branch.main.remote"))
        assertEquals(listOf("up"), config("branch.main.pushRemote"))
        assertEquals(listOf("up"), config("remote.pushDefault"))
        assertEquals(listOf("+refs/heads/*:refs/remotes/up/*"), config("remote.up.fetch"))
        assertEquals(
            listOf("refs/remotes/up/HEAD", "refs/remotes/up/dev", "refs/remotes/up/feature/a", "refs/remotes/up/main"),
            remoteRefs(),
        )
    }

    // Whether git rewrote the refspecs is read back from the configuration, so that the flag follows git.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "+refs/heads/*:refs/remotes/origin/*|true",
            "+refs/heads/main:refs/remotes/origin/main|true",
            "refs/heads/*:refs/remotes/origin/*|true",
            "+refs/heads/*:refs/remotes/origin/*;+refs/heads/dev:refs/remotes/origin/dd|true",
            "+refs/*:refs/*|false",
            "+refs/heads/*:refs/remotes/origin/*;^refs/heads/dev|false",
            "^refs/heads/dev|false",
            "+refs/heads/*:refs/remotes/other/*|false",
            "+refs/heads/*:refs/remotes/origin2/*|false",
            "+refs/heads/*:refs/remotes/origin/*;+refs/heads/*:refs/remotes/other/*|false",
            "refs/heads/main|false",
            "refs/heads/main:|false",
        ],
    )
    fun `tells whether the fetch refspecs were rewritten`(
        refspecs: String,
        expected: Boolean,
    ) {
        setFetch("origin", *refspecs.split(';').toTypedArray())
        val before = config("remote.origin.fetch")

        val renamed = remotes.rename(repo, "origin", "up")

        assertTrue(renamed.result.success, renamed.result.message)
        assertEquals(expected, renamed.refspecsUpdated)
        val after = config("remote.up.fetch")
        assertEquals(expected, after.none { it in before }, "git left some as they were: $after")
    }

    @Test
    fun `a rename that fails after the configuration moved is partial`() {
        repo.resolve(".git/packed-refs.lock").writeText("")

        val renamed = remotes.rename(repo, "origin", "up")

        assertFalse(renamed.result.success)
        assertTrue(renamed.partial, renamed.result.message)
        assertEquals(listOf("up"), remoteNames())
        assertTrue(remoteRefs().all { it.startsWith("refs/remotes/origin/") }, "the refs stayed")
    }

    @Test
    fun `a rename that git refuses at once is not partial`() {
        git("remote", "add", "taken", "file:///x")

        val missing = remotes.rename(repo, "nope", "up")
        val taken = remotes.rename(repo, "origin", "taken")

        assertFalse(missing.result.success)
        assertFalse(missing.partial)
        assertContains(missing.result.message, "No such remote")
        assertFalse(taken.result.success)
        assertFalse(taken.partial)
        assertContains(taken.result.message, "already exists")
    }

    @Test
    fun `removes a remote with its branches`() {
        val result = remotes.remove(repo, "origin")

        assertTrue(result.success, result.message)
        assertEquals(emptyList(), remoteNames())
        assertEquals(emptyList(), remoteRefs())
        assertEquals(emptyList(), config("branch.main.remote"))
    }

    @Test
    fun `removing a missing remote fails with git's message`() {
        val result = remotes.remove(repo, "nope")

        assertFalse(result.success)
        assertContains(result.message, "No such remote")
    }

    @Test
    fun `changes the URL`() {
        val result = remotes.setUrls(repo, "origin", "https://example.com/new.git", null, false)

        assertTrue(result.success, result.message)
        assertEquals(listOf("https://example.com/new.git"), config("remote.origin.url"))
        assertEquals(emptyList(), config("remote.origin.pushurl"))
    }

    @Test
    fun `sets a push URL where there was none, then changes and removes it`() {
        val set = remotes.setUrls(repo, "origin", null, "https://example.com/push.git", false)
        assertTrue(set.success, set.message)
        assertEquals(listOf("https://example.com/push.git"), config("remote.origin.pushurl"))
        assertEquals(listOf(upstream.toString()), config("remote.origin.url"))

        val changed = remotes.setUrls(repo, "origin", null, "https://example.com/push2.git", false)
        assertTrue(changed.success, changed.message)
        assertEquals(listOf("https://example.com/push2.git"), config("remote.origin.pushurl"))

        val removed = remotes.setUrls(repo, "origin", null, null, removePushUrl = true)
        assertTrue(removed.success, removed.message)
        assertEquals(emptyList(), config("remote.origin.pushurl"))
    }

    @Test
    fun `removing a push URL that is not set, or keeping everything, changes nothing and succeeds`() {
        val before = git("config", "--list", "--local")

        assertTrue(remotes.setUrls(repo, "origin", null, null, removePushUrl = true).success)
        assertTrue(remotes.setUrls(repo, "origin", null, null, removePushUrl = false).success)

        assertEquals(before, git("config", "--list", "--local"))
    }

    @Test
    fun `changes both URLs in one call and a failing URL change leaves the push URL alone`() {
        val both = remotes.setUrls(repo, "origin", "u1", "p1", false)
        assertTrue(both.success, both.message)
        assertEquals(listOf("u1"), config("remote.origin.url"))
        assertEquals(listOf("p1"), config("remote.origin.pushurl"))

        git("config", "--add", "remote.origin.url", "u2")
        val failed = remotes.setUrls(repo, "origin", "u3", "p2", false)
        assertFalse(failed.success)
        assertEquals(listOf("p1"), config("remote.origin.pushurl"))
    }

    /** Runs [block] and returns the lines the git command log wrote meanwhile. */
    private fun loggedDuring(block: () -> Unit): String {
        val lines = CopyOnWriteArrayList<String>()
        val previous = GitCommandLogSink.sink
        GitCommandLogSink.sink = { _, message, _ -> lines += message }
        try {
            block()
        } finally {
            GitCommandLogSink.sink = previous
        }
        return lines.joinToString("\n")
    }

    @Test
    fun `a failing URL change shows and logs no credentials, neither of the URL nor of the push URL`() {
        val url = "https://user:s3cret@h.example/x.git"
        val push = "https://tok3n:pw0rd@h.example/p.git"
        git("config", "--add", "remote.origin.url", "u2")
        git("config", "--add", "remote.origin.pushurl", "p1")
        git("config", "--add", "remote.origin.pushurl", "p2")
        val results = mutableListOf<GitResult>()

        val log =
            loggedDuring {
                results += remotes.setUrls(repo, "origin", url, null, false)
                results += remotes.setUrls(repo, "origin", null, push, false)
            }

        for (result in results) {
            assertFalse(result.success)
            assertTrue(listOf("s3cret", "user:", "tok3n", "pw0rd").none { it in result.message }, result.message)
        }
        assertContains(results[0].message, "https://****@h.example/x.git")
        assertContains(results[1].message, "https://****@h.example/p.git")
        assertTrue(listOf("s3cret", "user:", "tok3n", "pw0rd").none { it in log }, log)
        assertContains(log, "https://***@h.example/x.git")
    }

    @Test
    fun `a blank URL does not change git's message`() {
        val result = remotes.setUrls(repo, "nope", " ", null, false)

        assertFalse(result.success)
        assertContains(result.message, "No such remote")
    }

    @Test
    fun `adding with a held configuration lock shows and logs no credentials`() {
        repo.resolve(".git/config.lock").writeText("")
        // an apostrophe is legal in userinfo, and git prints the URL inside its own apostrophes
        val url = "https://user:s3'cret@h.example/x.git"
        lateinit var result: GitResult

        val log = loggedDuring { result = remotes.add(repo, "up", url) }

        assertFalse(result.success)
        assertContains(result.message, "https://****@h.example/x.git")
        assertTrue(listOf("s3", "cret", "user:").none { it in result.message || it in log }, "${result.message}\n$log")
    }

    @Test
    fun `reads the URLs and tells several values`() {
        assertEquals(
            GitRemoteUrls(upstream.toString(), null, severalUrls = false, severalPushUrls = false),
            remotes.urls(repo, "origin"),
        )

        git("config", "--add", "remote.origin.url", "u2")
        git("config", "--add", "remote.origin.pushurl", "p1")
        assertEquals(
            GitRemoteUrls(upstream.toString(), "p1", severalUrls = true, severalPushUrls = false),
            remotes.urls(repo, "origin"),
        )
        git("config", "--add", "remote.origin.pushurl", "p2")
        assertEquals(
            GitRemoteUrls(upstream.toString(), "p1", severalUrls = true, severalPushUrls = true),
            remotes.urls(repo, "origin"),
        )
    }

    @Test
    fun `a remote without URLs, or a name git cannot take, has no URLs to read`() {
        assertEquals(GitRemoteUrls(null, null, false, false), remotes.urls(repo, "nope"))
        assertNull(remotes.urls(repo, "-x"))
    }

    @Test
    fun `locations name the place of each remote and nothing else`() {
        git("remote", "add", "a.b", "https://user:token@github.com/x/y.git")
        git("remote", "add", "team/a", "git@host.example:x/y.git")
        git("config", "--add", "remote.a.b.url", "https://second.example/z")
        git("config", "remote.nourl.fetch", "+refs/heads/*:refs/remotes/nourl/*")

        val locations = remotes.locations(repo)

        assertEquals(
            mapOf(
                "origin" to "Local repository",
                "a.b" to "https://github.com",
                "team/a" to "ssh host.example",
            ),
            locations,
        )
    }

    @Test
    fun `locations of a repository without remotes are empty`() {
        git("remote", "remove", "origin")

        assertEquals(emptyMap(), remotes.locations(repo))
    }

    /** What `git remote remove` deletes below refs/remotes/ is what [GitRemotes.removal] counted. */
    private fun assertRemovalCountsWhatGitDeletes(
        name: String,
        expected: Int,
    ) {
        val removal = remotes.removal(repo, name)
        val before = remoteRefs()

        val result = remotes.remove(repo, name)

        assertTrue(result.success, result.message)
        val after = remoteRefs()
        val head = "refs/remotes/$name/HEAD"
        val branches = before.size - after.size - if (head in before && head !in after) 1 else 0
        assertEquals(branches, removal?.remoteBranches, "git deleted $branches branches of $before")
        assertEquals(expected, branches)
    }

    @Test
    fun `counts the branches of a default remote, not its HEAD`() {
        assertRemovalCountsWhatGitDeletes("origin", 3)
    }

    @Test
    fun `counts the one branch of a single-branch remote`() {
        setFetch("origin", "+refs/heads/main:refs/remotes/origin/main")

        assertRemovalCountsWhatGitDeletes("origin", 1)
    }

    @Test
    fun `a ref that one source of the refspecs negates is kept although another source is not negated`() {
        git("update-ref", "refs/remotes/origin/v1", "refs/remotes/origin/main")
        git("update-ref", "refs/remotes/origin/v2", "refs/remotes/origin/main")
        setFetch(
            "origin",
            "+refs/heads/*:refs/remotes/origin/*",
            "+refs/tags/*:refs/remotes/origin/*",
            "^refs/tags/v1",
        )

        assertRemovalCountsWhatGitDeletes("origin", 4)
        assertTrue("refs/remotes/origin/v1" in remoteRefs(), "git kept origin/v1")
    }

    @Test
    fun `counts only the fetched branch of a remote whose HEAD points elsewhere`() {
        setFetch("origin", "+refs/heads/dev:refs/remotes/origin/dev")

        assertRemovalCountsWhatGitDeletes("origin", 1)
    }

    @Test
    fun `counts what a wildcard maps although the branch HEAD points at is excluded`() {
        setFetch("origin", "+refs/heads/*:refs/remotes/origin/*", "^refs/heads/main")

        assertRemovalCountsWhatGitDeletes("origin", 2)
    }

    @Test
    fun `leaves out what a negative refspec excludes`() {
        setFetch("origin", "+refs/heads/*:refs/remotes/origin/*", "^refs/heads/dev")

        assertRemovalCountsWhatGitDeletes("origin", 2)
    }

    @Test
    fun `leaves out what a negative refspec with a wildcard excludes`() {
        setFetch("origin", "+refs/heads/*:refs/remotes/origin/*", "^refs/heads/feature/*")

        assertRemovalCountsWhatGitDeletes("origin", 2)
    }

    @Test
    fun `leaves out what another remote also maps`() {
        git("remote", "add", "other", upstream.toString())
        setFetch("other", "+refs/heads/*:refs/remotes/origin/*")

        assertRemovalCountsWhatGitDeletes("other", 0)
    }

    @Test
    fun `leaves out only the refs another remote also maps`() {
        git("remote", "add", "other", upstream.toString())
        setFetch("other", "+refs/heads/dev:refs/remotes/origin/dev")

        assertRemovalCountsWhatGitDeletes("origin", 2)
    }

    @Test
    fun `a mirror remote counts the refs below refs remotes except those of other remotes`() {
        git("remote", "add", "other", upstream.toString())
        git("fetch", "-q", "other")
        setFetch("origin", "+refs/*:refs/*")

        assertRemovalCountsWhatGitDeletes("origin", 3)
        // git fetch also creates other/HEAD.
        assertEquals(4, remoteRefs().count { it.startsWith("refs/remotes/other/") })
    }

    @Test
    fun `a remote that fetches into refs heads counts nothing`() {
        setFetch("origin", "+refs/heads/*:refs/heads/*")

        assertRemovalCountsWhatGitDeletes("origin", 0)
    }

    @Test
    fun `does not count refs below the remote that no refspec maps`() {
        git("update-ref", "refs/remotes/origin/extra", "refs/remotes/origin/main")
        setFetch("origin", "+refs/heads/main:refs/remotes/origin/main")

        assertRemovalCountsWhatGitDeletes("origin", 1)
    }

    @Test
    fun `lists the tracking branches by their real names, sorted and once`() {
        git("branch", "Feat/X", "main")
        git("config", "branch.Feat/X.pushRemote", "origin")
        git("branch", "both", "main")
        git("config", "branch.both.remote", "origin")
        git("config", "branch.both.pushRemote", "origin")
        git("branch", "elsewhere", "main")
        git("remote", "add", "other", upstream.toString())
        git("config", "branch.elsewhere.remote", "other")

        val removal = remotes.removal(repo, "origin")

        assertEquals(listOf("Feat/X", "both", "main"), removal?.trackingBranches)
        assertFalse(removal?.pushDefault ?: true)
    }

    @Test
    fun `tells when the remote is the push default`() {
        git("config", "remote.pushDefault", "origin")

        assertEquals(true, remotes.removal(repo, "origin")?.pushDefault)
        assertEquals(false, remotes.removal(repo, "other")?.pushDefault)
    }

    @Test
    fun `a name git cannot take has no removal`() {
        assertNull(remotes.removal(repo, "-x"))
    }

    @Test
    fun `removal reads fail with null when the refs cannot be read`() {
        repo.resolve(".git/packed-refs").writeText("garbage\n")

        assertNull(remotes.removal(repo, "origin"))
    }

    @Test
    fun `a refspec without a destination, or a destination with a wildcard only, counts as git counts`() {
        setFetch("origin", "+refs/heads/*:refs/remotes/origin/*", "+refs/heads/dev")
        assertRemovalCountsWhatGitDeletes("origin", 3)
    }

    @Test
    fun `a refspec that names a remote ref without a destination maps nothing`() {
        setFetch("origin", "refs/remotes/origin/main")

        assertRemovalCountsWhatGitDeletes("origin", 0)
    }

    @Test
    fun `reads fail with null and writes with git's message when the configuration is broken`() {
        repo.resolve(".git/config").appendText("\n[broken\n")

        assertNull(remotes.urls(repo, "origin"))
        assertNull(remotes.removal(repo, "origin"))
        assertNull(remotes.locations(repo))
        val unset = remotes.setUrls(repo, "origin", null, null, removePushUrl = true)
        assertFalse(unset.success, "removing push URLs of a broken configuration is no success")
        assertFalse(remotes.rename(repo, "origin", "up").partial)
    }
}
