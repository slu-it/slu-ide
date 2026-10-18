package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.refs
import dev.sluide.git.cli.sshConfigured
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Fetches with the real git from local "remotes" in temporary folders; skipped if git is not installed. */
class GitRemotesTest {
    @TempDir
    lateinit var folder: Path

    /** The repository on the "server", with a working tree to commit in. */
    private val upstream get() = folder.resolve("upstream")

    /** The repository that fetches, a clone of [upstream]. */
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
        commit("First")
        git("clone", "-q", upstream.toString(), repo.toString(), directory = folder)
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
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits in [upstream]; returns the commit's hash. */
    private fun commit(subject: String): String {
        upstream.resolve("file.txt").writeText("$subject\n")
        git("add", "file.txt", directory = upstream)
        git("commit", "-q", "-m", subject, directory = upstream)
        return git("rev-parse", "HEAD", directory = upstream)
    }

    private fun refs(prefix: String) =
        git("for-each-ref", "--format=%(refname:short) %(objectname)", prefix).lines().filter(String::isNotEmpty)

    private fun tags() = git("tag").lines().filter(String::isNotEmpty)

    private fun tip(ref: String) = git("rev-parse", ref)

    @Test
    fun `fetches new and moved branches, prunes deleted ones and follows the tags of the fetched commits only`() {
        git("branch", "old", directory = upstream)
        git("branch", "moving", directory = upstream)
        git("fetch", "-q", "origin")
        git("tag", "mine")
        val second = commit("Second")
        git("tag", "v2", directory = upstream)
        git("branch", "-f", "moving", second, directory = upstream)
        git("branch", "fresh", directory = upstream)
        git("branch", "-D", "old", directory = upstream)
        // A tag of a commit on no branch: not in the fetched history.
        git("switch", "-q", "--detach", directory = upstream)
        commit("Loose")
        git("tag", "loose", directory = upstream)
        git("switch", "-q", "main", directory = upstream)

        val fetched = remotes.fetch(repo, "origin", GitCancellation())

        assertTrue(fetched.result.success, fetched.result.message)
        assertFalse(fetched.cancelled)
        assertEquals(
            GitFetchChanges(newBranches = 1, updatedBranches = 2, prunedBranches = 1, newTags = 1),
            fetched.changes,
        )
        assertEquals(second, tip("refs/remotes/origin/main"))
        assertEquals(second, tip("refs/remotes/origin/fresh"))
        assertFalse(refs("refs/remotes/origin/").any { it.startsWith("origin/old ") }, "pruned")
        assertEquals(listOf("mine", "v2"), tags(), "the local tag stays, the loose one is not fetched")
        assertContains(fetched.result.message, "[new branch]")

        assertTrue(fetched.pruned)
        val again = remotes.fetch(repo, "origin", GitCancellation())
        assertTrue(again.result.success, again.result.message)
        assertEquals(GitFetchChanges(), again.changes)
    }

    @Test
    fun `a remote whose refspecs write outside refs_remotes is not pruned, as that would delete local refs`() {
        // A mirror: pruning would delete every local branch and tag the server does not have (git 2.53).
        git("config", "--replace-all", "remote.origin.fetch", "+refs/*:refs/*")
        git("config", "fetch.prune", "true")
        git("switch", "-q", "--detach")
        git("branch", "local-only")
        git("tag", "mine")
        commit("Second")

        val fetched = remotes.fetch(repo, "origin", GitCancellation())

        assertTrue(fetched.result.success, fetched.result.message)
        assertFalse(fetched.pruned)
        assertTrue(refs("refs/heads/").any { it.startsWith("local-only ") }, "the local branch stays")
        assertEquals(listOf("mine"), tags())
        assertEquals(tip("refs/heads/main"), git("rev-parse", "HEAD", directory = upstream), "fetched all the same")

        git("config", "--replace-all", "remote.origin.fetch", "+refs/tags/*:refs/tags/*")
        assertFalse(remotes.fetch(repo, "origin", GitCancellation()).pruned)
        assertEquals(listOf("mine"), tags())
    }

    @Test
    fun `a tag git refuses to move fails the fetch while the branches are updated`() {
        git("tag", "v1", directory = upstream)
        git("config", "remote.origin.tagOpt", "--tags")
        git("fetch", "-q", "origin")
        val old = tip("refs/tags/v1")
        val second = commit("Second")
        git("tag", "-f", "v1", directory = upstream)

        val fetched = remotes.fetch(repo, "origin", GitCancellation())

        assertFalse(fetched.result.success)
        assertContains(fetched.result.message, "would clobber existing tag")
        assertEquals(GitFetchChanges(updatedBranches = 1), fetched.changes)
        assertEquals(second, tip("refs/remotes/origin/main"))
        assertEquals(old, tip("refs/tags/v1"))
    }

    @Test
    fun `lists the remotes but those fetch --all skips`() {
        git("remote", "add", "second", upstream.toString())
        git("remote", "add", "skipped", upstream.toString())
        git("config", "remote.skipped.skipFetchAll", "yes")
        git("remote", "add", "with.dot", upstream.toString())
        git("config", "remote.with.dot.skipFetchAll", "false")

        assertEquals(listOf("origin", "second", "with.dot"), remotes.remotes(repo))

        git("config", "--add", "remote.second.skipFetchAll", "true")
        git("config", "--add", "remote.second.skipFetchAll", "false")
        assertEquals(listOf("origin", "second", "with.dot"), remotes.remotes(repo), "the last value counts, as for git")
        git("config", "--add", "remote.second.skipFetchAll", "true")
        assertEquals(listOf("origin", "with.dot"), remotes.remotes(repo))
        assertEquals(listOf("origin", "second", "skipped", "with.dot"), remotes.names(repo), "all")

        git("config", "--unset-all", "remote.second.skipFetchAll")
        git("config", "remote.second.skipFetchAll", "maybe")
        assertEquals(null, remotes.remotes(repo), "not a boolean")

        val alone = folder.resolve("alone").createDirectories()
        git("init", "-q", directory = alone)
        assertEquals(emptyList(), remotes.remotes(alone))
    }

    @Test
    fun `a remote that cannot be reached fails while another is fetched`() {
        git("remote", "add", "gone", folder.resolve("no-such-repo").toString())
        val second = commit("Second")

        val gone = remotes.fetch(repo, "gone", GitCancellation())
        val origin = remotes.fetch(repo, "origin", GitCancellation())

        assertFalse(gone.result.success)
        assertContains(gone.result.message, "no-such-repo")
        assertEquals(GitFetchChanges(), gone.changes)
        assertTrue(origin.result.success, origin.result.message)
        assertEquals(second, tip("refs/remotes/origin/main"))
    }

    @Test
    fun `counts the refs where the remote's refspecs write them, not where the default one would`() {
        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/mirror/origin/*")
        git("config", "--add", "remote.origin.fetch", "+refs/heads/main:refs/pinned/main")
        git("config", "--add", "remote.origin.fetch", "^refs/heads/skipped")
        git("fetch", "-q", "origin")
        commit("Second")
        git("branch", "feature/a", directory = upstream)
        git("branch", "skipped", directory = upstream)

        val fetched = remotes.fetch(repo, "origin", GitCancellation())

        assertTrue(fetched.result.success, fetched.result.message)
        // refs/mirror/origin/main and refs/pinned/main move, refs/mirror/origin/feature/a is new; not "skipped".
        assertEquals(GitFetchChanges(newBranches = 1, updatedBranches = 2), fetched.changes)
    }

    @Test
    fun `refuses remote names git cannot get without running git`() {
        assertEquals(
            GitFetchResult(GitResult(false, "'-x' cannot be passed to git: it starts with '-'.")),
            remotes.fetch(repo, "-x", GitCancellation()),
        )
        assertEquals(
            GitFetchResult(GitResult(false, "An empty name cannot be passed to git.")),
            remotes.fetch(repo, "", GitCancellation()),
        )
    }

    @Test
    fun `cancelling stops a hanging fetch with what it started, and one cancelled before does not run git`() {
        val pidFile = folder.resolve("pid")
        val childFile = folder.resolve("child")
        val hang =
            folder.resolve("hang.sh").apply {
                writeText("#!/bin/sh\nsleep 30 &\necho $! > '$childFile'\necho $$ > '$pidFile'\nwait\n")
                toFile().setExecutable(true)
            }
        git("remote", "add", "slow", upstream.toString())
        git("config", "remote.slow.uploadpack", hang.toString())
        val cancellation = GitCancellation()

        val fetching = CompletableFuture.supplyAsync { remotes.fetch(repo, "slow", cancellation) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pidFile.exists() || pidFile.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("the upload pack did not start")
            Thread.sleep(20)
        }
        val helper = ProcessHandle.of(pidFile.readText().trim().toLong())
        val child = ProcessHandle.of(childFile.readText().trim().toLong())
        assertTrue(helper.isPresent && child.isPresent, "the remote side runs")
        val started = System.nanoTime()

        cancellation.cancel()
        val fetched = fetching.get(10, TimeUnit.SECONDS)

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "stopped at once, not after the timeout")
        assertTrue(fetched.cancelled)
        assertEquals(GitResult(false, "Cancelled."), fetched.result)
        assertEquals(GitFetchChanges(), fetched.changes)
        helper.get().onExit().get(5, TimeUnit.SECONDS)
        child.get().onExit().get(5, TimeUnit.SECONDS)
        assertFalse(helper.get().isAlive, "the helper git started was stopped")
        assertFalse(child.get().isAlive, "and what it started")

        pidFile.toFile().delete()
        val before = GitCancellation().apply { cancel() }
        val skipped = remotes.fetch(repo, "slow", before)
        assertTrue(skipped.cancelled)
        assertFalse(pidFile.exists(), "git was not run")
    }

    @Test
    fun `ssh runs in batch mode unless the user chose how git runs it`() {
        val args = folder.resolve("ssh-args")
        val bin =
            folder.resolve("bin").createDirectories().also { bin ->
                bin.resolve("ssh").apply {
                    writeText("#!/bin/sh\necho \"$*\" > '$args'\nexit 1\n")
                    toFile().setExecutable(true)
                }
            }
        val withFakeSsh =
            GitRemotes(GitCli("git", testGitEnvironment() + ("PATH" to "$bin:${System.getenv("PATH")}")))
        git("remote", "add", "server", "user@example.invalid:repo.git")

        val batch = withFakeSsh.fetch(repo, "server", GitCancellation())

        assertFalse(batch.result.success)
        assertContains(args.readText(), "-o BatchMode=yes")

        git("config", "core.sshCommand", "ssh -o ConnectTimeout=1")
        withFakeSsh.fetch(repo, "server", GitCancellation())

        val configured = args.readText()
        assertContains(configured, "ConnectTimeout=1")
        assertFalse("BatchMode" in configured, configured)
    }

    @Test
    fun `tells whether ssh is configured`() {
        assertEquals(false, git.sshConfigured(repo))
        git("config", "ssh.variant", "simple")
        assertEquals(true, git.sshConfigured(repo))
        git("config", "--unset", "ssh.variant")
        git("config", "core.sshCommand", "ssh")
        assertEquals(true, git.sshConfigured(repo))
    }

    @Test
    fun `counts new, moved and deleted remote branches and tags apart`() {
        val before = mapOf("refs/remotes/o/a" to "1", "refs/remotes/o/b" to "1", "refs/tags/t" to "1")
        val after =
            mapOf(
                "refs/remotes/o/a" to "2",
                "refs/remotes/o/c" to "1",
                "refs/tags/t" to "1",
                "refs/tags/u" to "3",
            )

        val changes = fetchChanges(before, after)

        assertEquals(GitFetchChanges(1, 1, 1, 1, 0, 0), changes)
        assertTrue(fetchChanges(after, after).none)
        assertEquals(GitFetchChanges(2, 2, 2, 2, 0, 0), changes + changes)
    }
}
