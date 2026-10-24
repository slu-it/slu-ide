package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
import kotlin.time.Duration.Companion.seconds

/** Pushes with the real git to bare "remotes" in temporary folders; skipped if git is not installed. */
internal class GitPushTest : GitPushFixture() {
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
        val withFull = pushes.push(repo, "origin", "main", "main", true, full, false, GitCancellation())
        assertFalse(withFull.cancelled)
        assertEquals(GitPushRejection.STALE, withFull.rejection, "a full hash is passed to git, whose lease fails")
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

    @Test
    fun `a push stopped by its timeout may have reached the remote and says so`() {
        val script = folder.resolve("slow-git")
        script.writeText(
            FAKE_GIT_START +
                "case \"\$*\" in *config*) exit 1;; esac\necho 'Writing objects: 100%' >&2\nexec sleep 30\n",
        )
        script.toFile().setExecutable(true)
        val fake = GitCli(script.toString(), testGitEnvironment())
        assumeTrue(fake.available, "no /bin/sh to stand in for git")

        val pushed =
            GitPush(
                fake,
            ).push(repo, "origin", "main", "main", false, null, false, GitCancellation(), timeout = 1.seconds)

        assertFalse(pushed.result.success)
        assertFalse(pushed.cancelled)
        assertEquals(
            "git did not finish within 1 s.\nIt may have reached origin. Fetch to check.",
            pushed.result.message,
        )
    }
}
