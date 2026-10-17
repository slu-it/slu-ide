package dev.sluide.git

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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Pushes with the real git to bare "remotes" in temporary folders; skipped if git is not installed. */
class GitPushTest {
    @TempDir
    lateinit var folder: Path

    /** The remote `origin`: a bare repository. */
    private val server get() = folder.resolve("server.git")

    /** The repository that pushes, a clone of [server]. */
    private val repo get() = folder.resolve("repo")

    /** A second clone, to move the remote behind the back of [repo]. */
    private val other get() = folder.resolve("other")

    private val git = GitCli("git", testGitEnvironment())
    private val pushes = GitPush(git)

    @BeforeEach
    fun createRepositories() {
        assumeTrue(git.available, "git is not installed")
        folder.createDirectories()
        git("init", "-q", "--bare", "-b", "main", server.toString(), directory = folder)
        git("clone", "-q", server.toString(), repo.toString(), directory = folder)
        configure(repo)
        commit("First")
        git("push", "-q", "origin", "main")
        git("clone", "-q", server.toString(), other.toString(), directory = folder)
        configure(other)
    }

    private fun configure(directory: Path) {
        git("config", "user.name", "Test", directory = directory)
        git("config", "user.email", "test@example.com", directory = directory)
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

    private fun commit(
        subject: String,
        directory: Path = repo,
    ): String {
        directory.resolve("file.txt").writeText("$subject\n")
        git("add", "file.txt", directory = directory)
        git("commit", "-q", "-m", subject, directory = directory)
        return git("rev-parse", "HEAD", directory = directory)
    }

    private fun rev(
        ref: String,
        directory: Path = repo,
    ) = git("rev-parse", ref, directory = directory)

    private fun remoteTip(branch: String) = git("rev-parse", "refs/heads/$branch", directory = server)

    /** The remote moves on, by a commit of [other], which [repo] has not fetched. */
    private fun moveRemote(subject: String): String {
        git("pull", "-q", "--ff-only", "origin", "main", directory = other)
        val moved = commit(subject, other)
        git("push", "-q", "origin", "main", directory = other)
        return moved
    }

    private fun push(
        target: String = "main",
        branch: String = "main",
        force: Boolean = false,
        expected: String? = null,
        setUpstream: Boolean = false,
        cancellation: GitCancellation = GitCancellation(),
    ) = pushes.push(repo, "origin", branch, target, force, expected, setUpstream, cancellation)

    // pushTarget

    @Test
    fun `the target is the upstream when the branch has one`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")

        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `the push remote and remote_pushDefault come before the upstream, with the branch's own name`() {
        git("remote", "add", "fork", folder.resolve("fork").toString())
        git("remote", "add", "extra", folder.resolve("extra").toString())
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "remote.pushDefault", "extra")
        assertEquals(GitPushTarget("extra", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("config", "branch.main.pushRemote", "fork")
        assertEquals(GitPushTarget("fork", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `a push remote that is the upstream's remote takes the upstream's branch`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "branch.main.pushRemote", "origin")

        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `a local upstream is not a remote`() {
        git("config", "branch.feature.remote", ".")
        git("config", "branch.feature.merge", "refs/heads/main")

        assertEquals(GitPushTarget("origin", "feature", hasUpstream = true), pushes.pushTarget(repo, "feature"))
    }

    @Test
    fun `without an upstream the only remote, else origin, else the first remote`() {
        assertEquals(GitPushTarget("origin", "topic"), pushes.pushTarget(repo, "topic"))

        git("remote", "rename", "origin", "alpha")
        assertEquals(GitPushTarget("alpha", "topic"), pushes.pushTarget(repo, "topic"))

        git("remote", "add", "beta", folder.resolve("beta").toString())
        assertEquals(GitPushTarget("alpha", "topic"), pushes.pushTarget(repo, "topic"), "the first")

        git("remote", "add", "origin", folder.resolve("o").toString())
        assertEquals(GitPushTarget("origin", "topic"), pushes.pushTarget(repo, "topic"), "origin among several")
    }

    @Test
    fun `there is no target without a remote`() {
        git("remote", "remove", "origin")

        assertNull(pushes.pushTarget(repo, "main"))
    }

    // trackingRef

    @Test
    fun `the tracking ref follows the fetch refspec, the default one without any`() {
        assertEquals("refs/remotes/origin/main", pushes.trackingRef(repo, "origin", "main"))
        assertEquals("refs/remotes/origin/feature/x", pushes.trackingRef(repo, "origin", "feature/x"))

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/mirror/*")
        assertEquals("refs/remotes/mirror/feature/x", pushes.trackingRef(repo, "origin", "feature/x"))

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/trunk")
        assertEquals("refs/remotes/origin/trunk", pushes.trackingRef(repo, "origin", "main"))
        assertEquals("refs/remotes/origin/other", pushes.trackingRef(repo, "origin", "other"), "unmapped: fallback")

        git("config", "--unset-all", "remote.origin.fetch")
        assertEquals("refs/remotes/origin/main", pushes.trackingRef(repo, "origin", "main"))
    }

    // outgoing

    @Test
    fun `a tracked branch ahead by two`() {
        val first = rev("HEAD")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "main")

        assertEquals(listOf(third, second), outgoing?.commits?.map { it.hash })
        assertEquals(listOf("Third", "Second"), outgoing?.commits?.map { it.subject })
        assertEquals(GitOutgoing(outgoing!!.commits, false, first, 0, first), outgoing)
    }

    @Test
    fun `a branch the remote does not have holds the commits of no remote branch`() {
        val first = rev("HEAD")
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "topic")

        assertEquals(listOf(third, second), outgoing?.commits?.map { it.hash })
        assertNull(outgoing?.remoteCommit)
        assertEquals(0, outgoing?.behind)
        assertEquals(first, outgoing?.base, "the first parent of the oldest commit")
    }

    @Test
    fun `a new branch with a root commit has no base`() {
        git("switch", "-q", "--orphan", "fresh")
        commit("Root")

        val outgoing = pushes.outgoing(repo, "origin", "fresh")

        assertEquals(1, outgoing?.commits?.size)
        assertNull(outgoing?.base)
    }

    @Test
    fun `nothing outgoing when the branch is where the remote has it`() {
        val outgoing = pushes.outgoing(repo, "origin", "main")

        assertEquals(GitOutgoing(emptyList(), false, rev("HEAD"), 0, null), outgoing)
    }

    @Test
    fun `behind and diverged counts, and the merge base`() {
        val first = rev("HEAD")
        moveRemote("Remote 2")
        moveRemote("Remote 3")
        git("fetch", "-q")
        assertEquals(
            GitOutgoing(emptyList(), false, rev("origin/main"), 2, null),
            pushes.outgoing(repo, "origin", "main"),
        )

        val local = commit("Local")
        val diverged = pushes.outgoing(repo, "origin", "main")

        assertEquals(listOf(local), diverged?.commits?.map { it.hash })
        assertEquals(2, diverged?.behind)
        assertEquals(first, diverged?.base)
    }

    @Test
    fun `more than the limit says so and the new branch's base is still the first parent of the oldest`() {
        val first = rev("HEAD")
        git("switch", "-q", "-c", "topic")
        repeat(5) { commit("Commit $it") }

        val limited = pushes.outgoing(repo, "origin", "topic", limit = 3)

        assertEquals(listOf("Commit 4", "Commit 3", "Commit 2"), limited?.commits?.map { it.subject })
        assertEquals(true, limited?.more)
        assertEquals(first, limited?.base)
        assertEquals(false, pushes.outgoing(repo, "origin", "topic", limit = 5)?.more)

        git("push", "-q", "origin", "topic")
        for (i in 0 until 4) commit("More $i")
        val tracked = pushes.outgoing(repo, "origin", "topic", limit = 3)
        assertEquals(true, tracked?.more)
        assertEquals(rev("origin/topic"), tracked?.base)
    }

    // push

    @Test
    fun `a normal push`() {
        val second = commit("Second")

        val pushed = push()

        assertTrue(pushed.result.success, pushed.result.message)
        assertFalse(pushed.cancelled)
        assertNull(pushed.rejection)
        assertEquals(second, remoteTip("main"))
    }

    @Test
    fun `a push to a new branch with set upstream sets the upstream`() {
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")

        val pushed = push(branch = "topic", target = "topic", setUpstream = true)

        assertTrue(pushed.result.success, pushed.result.message)
        assertEquals(second, remoteTip("topic"))
        assertEquals("origin", git("config", "branch.topic.remote"))
        assertEquals("refs/heads/topic", git("config", "branch.topic.merge"))
    }

    @Test
    fun `a push without set upstream leaves the configuration alone`() {
        git("switch", "-q", "-c", "topic")
        commit("Second")

        assertTrue(push(branch = "topic", target = "topic").result.success)

        assertFalse("branch.topic" in git("config", "--list"))
    }

    @Test
    fun `a push to a different target name`() {
        val second = commit("Second")

        val pushed = push(target = "other-name", setUpstream = true)

        assertTrue(pushed.result.success, pushed.result.message)
        assertEquals(second, remoteTip("other-name"))
        assertEquals("refs/heads/other-name", git("config", "branch.main.merge"))
    }

    @Test
    fun `a diverged remote rejects a normal push as non-fast-forward`() {
        moveRemote("Remote 2")
        commit("Local")

        val pushed = push()

        assertFalse(pushed.result.success)
        assertEquals(GitPushRejection.NON_FAST_FORWARD, pushed.rejection)
        assertTrue(
            pushed.result.message.startsWith("origin/main has commits that your branch does not have."),
            pushed.result.message,
        )
        assertContains(pushed.result.message, "failed to push some refs")
        assertFalse(pushed.result.message.contains("hint:"))
    }

    @Test
    fun `a force push with the expected commit replaces the remote branch`() {
        moveRemote("Remote 2")
        git("fetch", "-q")
        val local = commit("Local")

        val pushed = push(force = true, expected = rev("origin/main"))

        assertTrue(pushed.result.success, pushed.result.message)
        assertEquals(local, remoteTip("main"))
    }

    @Test
    fun `a force push is stale when the remote moved after the fetch`() {
        moveRemote("Remote 2")
        git("fetch", "-q")
        val looked = rev("origin/main")
        val moved = moveRemote("Remote 3")
        // A background fetch must not weaken the lease: the expected value was explicit.
        git("fetch", "-q")
        commit("Local")

        val pushed = push(force = true, expected = looked)

        assertFalse(pushed.result.success)
        assertEquals(GitPushRejection.STALE, pushed.rejection)
        assertTrue(
            pushed.result.message.startsWith("origin/main changed since your last fetch."),
            pushed.result.message,
        )
        assertEquals(moved, remoteTip("main"))
    }

    @Test
    fun `a force push of a new branch needs the branch to be missing`() {
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")

        val pushed = push(branch = "topic", target = "topic", force = true, expected = null)
        assertTrue(pushed.result.success, pushed.result.message)
        assertEquals(second, remoteTip("topic"))

        // The same push, as the branch has meanwhile appeared on the remote with other commits.
        git("switch", "-q", "-c", "late", "main")
        val late = commit("Late")
        moveRemoteBranch("late")
        val refused = push(branch = "late", target = "late", force = true, expected = null)
        assertFalse(refused.result.success)
        assertEquals(GitPushRejection.STALE, refused.rejection)
        assertNotEquals(late, remoteTip("late"))
    }

    /** Creates the branch [name] on the remote with a commit of [other]. */
    private fun moveRemoteBranch(name: String) {
        git("switch", "-q", "-c", name, directory = other)
        commit("Remote $name", other)
        git("push", "-q", "origin", name, directory = other)
    }

    @Test
    fun `a failing pre-push hook is a plain failure with its output`() {
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\necho 'no pushing today' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        commit("Second")

        val pushed = push()

        assertFalse(pushed.result.success)
        assertNull(pushed.rejection)
        assertContains(pushed.result.message, "no pushing today")
    }

    @Test
    fun `a push that was cancelled before does not run`() {
        val second = commit("Second")
        val cancellation = GitCancellation().apply { cancel() }

        val pushed = push(cancellation = cancellation)

        assertEquals(GitPushResult(GitResult(false, "Cancelled."), cancelled = true), pushed)
        assertFalse(second == remoteTip("main"))
    }

    @Test
    fun `names git would read as options are refused without running git`() {
        commit("Second")
        val first = rev("origin/main")
        val full = "a".repeat(40)
        val cases =
            listOf(
                PushCall("-x", "main", "main", null),
                PushCall("origin", "-x", "main", null),
                PushCall("origin", "main", "--delete", null),
                PushCall("origin", "main", "main", "-1"),
            )

        for (call in cases) {
            val pushed =
                pushes.push(repo, call.remote, call.branch, call.target, true, call.expected, false, GitCancellation())
            assertFalse(pushed.result.success)
            assertContains(pushed.result.message, "cannot be passed to git")
        }
        assertFalse(pushes.push(repo, "origin", "main", "main", true, full, false, GitCancellation()).cancelled)
        assertEquals(first, remoteTip("main"))
    }

    private data class PushCall(
        val remote: String,
        val branch: String,
        val target: String,
        val expected: String?,
    )

    @Test
    fun `a forced push needs a full hash as the expected commit and does not run git without`() {
        commit("Second")
        val first = rev("origin/main")

        for (expected in listOf("abc123", "origin/main", "HEAD", first.uppercase(), first.take(39))) {
            val pushed = push(force = true, expected = expected)
            assertFalse(pushed.result.success)
            assertEquals("'$expected' is not a full commit hash.", pushed.result.message)
        }
        assertEquals(first, remoteTip("main"))
        // Without force the expected value does not count.
        assertTrue(push(expected = "whatever").result.success)
    }

    @Test
    fun `a push tells whether it created the branch or the remote had it already`() {
        git("switch", "-q", "-c", "topic")
        commit("Second")

        val created = push(branch = "topic", target = "topic")
        val again = push(branch = "topic", target = "topic")
        val updated = push(branch = "topic", target = "main")

        assertTrue(created.created && !created.upToDate)
        assertTrue(again.upToDate && !again.created)
        assertEquals("", created.result.message)
        assertTrue(updated.result.success && !updated.created && !updated.upToDate)
    }

    @Test
    fun `a remote that declines is another rejection`() {
        val hook = server.resolve("hooks/pre-receive")
        hook.writeText("#!/bin/sh\necho 'declined by the server' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        commit("Second")

        val pushed = push()

        assertFalse(pushed.result.success)
        assertEquals(GitPushRejection.OTHER, pushed.rejection)
        assertContains(pushed.result.message, "declined by the server")
    }

    @Test
    fun `after a fetch the reason is non-fast-forward, told the same way`() {
        moveRemote("Remote 2")
        git("fetch", "-q")
        val diverged = commit("Diverged")

        val pushed = push()

        assertFalse(pushed.result.success)
        assertEquals(GitPushRejection.NON_FAST_FORWARD, pushed.rejection)
        assertNotEquals(diverged, remoteTip("main"))
    }

    @Test
    fun `cancelling a push that waits in a hook stops it`() {
        val pid = folder.resolve("hook-pid")
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\necho \$\$ > '$pid'\nsleep 30\n")
        hook.toFile().setExecutable(true)
        commit("Second")
        val cancellation = GitCancellation()

        val pushing = CompletableFuture.supplyAsync { push(cancellation = cancellation) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pid.exists() || pid.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("the hook did not start")
            Thread.sleep(20)
        }
        val started = System.nanoTime()
        cancellation.cancel()
        val pushed = pushing.get(10, TimeUnit.SECONDS)

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "stopped at once")
        assertEquals(GitPushResult(GitResult(false, "Cancelled."), cancelled = true), pushed)
    }

    @Test
    fun `the target says whether the branch has an upstream`() {
        assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("branch", "-q", "--unset-upstream")

        assertEquals(GitPushTarget("origin", "main", hasUpstream = false), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `nothing is outgoing for a repository without commits`() {
        val empty = folder.resolve("empty")
        empty.createDirectories()
        git("init", "-q", "-b", "main", directory = empty)
        git("remote", "add", "origin", server.toString(), directory = empty)

        assertNull(pushes.outgoing(empty, "origin", "main"))
    }

    @Test
    fun `a new branch is found among 600 remote branches, with a custom refspec too`() {
        val head = rev("HEAD")
        val names = (1..600).joinToString("") { "$head refs/heads/a-rather-long-branch-name-number-$it\n" }
        server.resolve("packed-refs").writeText("# pack-refs with: peeled fully-peeled sorted\n$names")
        git("fetch", "-q")
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "topic", limit = 1)

        assertEquals(listOf(third), outgoing?.commits?.map { it.hash })
        assertEquals(true, outgoing?.more)
        assertEquals(head, outgoing?.base, "the first parent of the oldest outgoing commit")
        assertEquals(listOf(third, second), pushes.outgoing(repo, "origin", "topic")?.commits?.map { it.hash })

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/mirror/*")
        git("fetch", "-q")
        val mirrored = pushes.outgoing(repo, "origin", "topic")
        assertEquals(listOf(third, second), mirrored?.commits?.map { it.hash })
        assertEquals(head, mirrored?.base)
    }

    // the command line

    @Test
    fun `the command line has the porcelain, the lease with the explicit value and the network environment`() {
        val script = folder.resolve("fake-git")
        val log = folder.resolve("calls.txt")
        script.writeText(
            "#!/bin/sh\n[ \"\$1\" = --version ] && exit 0\n" +
                "case \"\$*\" in *config*) exit 1;; esac\n" +
                "echo \"\$GIT_SSH_COMMAND|\$*\" >> '$log'\n",
        )
        script.toFile().setExecutable(true)
        val fake = GitCli(script.toString(), System.getenv().filterKeys { !it.startsWith("GIT_") })
        assumeTrue(fake.available, "no /bin/sh to stand in for git")

        val expected = "a".repeat(40)
        GitPush(fake).push(repo, "origin", "topic", "main", true, expected, true, GitCancellation())
        GitPush(fake).push(repo, "origin", "topic", "main", false, expected, false, GitCancellation())

        assertEquals(
            listOf(
                "ssh -o BatchMode=yes|push --porcelain --recurse-submodules=no --set-upstream " +
                    "--force-with-lease=refs/heads/main:$expected --end-of-options origin " +
                    "refs/heads/topic:refs/heads/main",
                "ssh -o BatchMode=yes|push --porcelain --recurse-submodules=no --end-of-options origin " +
                    "refs/heads/topic:refs/heads/main",
            ),
            log.toFile().readLines(),
        )
    }
}
