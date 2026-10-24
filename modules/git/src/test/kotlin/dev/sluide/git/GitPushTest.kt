package dev.sluide.git

import dev.sluide.git.cli.GitCli
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
import kotlin.time.Duration.Companion.seconds

/** Pushes with the real git to bare "remotes" in temporary folders; skipped if git is not installed. */
class GitPushTest {
    @TempDir
    lateinit var folder: Path

    /** The remote `origin`: a bare repository. */
    private val server get() = folder.resolve("server.git")

    /** The repository that pushes, a clone of [server]. */
    private val repo get() = folder.resolve("repo")

    /** A second clone, to move the remote behind the back of [repo]. */
    private val other: Path by lazy {
        val clone = folder.resolve("other")
        git("clone", "-q", server.toString(), clone.toString(), directory = folder)
        configure(clone)
        clone
    }

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
    fun `the target keeps the branch's name when its upstream has another, as push_default simple does`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        for (mode in listOf("simple", "current", "matching", "nothing")) {
            git("config", "push.default", mode)
            assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"), mode)
        }
    }

    @Test
    fun `a branch created from a remote branch is not pushed into it`() {
        // Git's default branch.autoSetupMerge makes origin/main the upstream of the new branch.
        git("branch", "feature", "refs/remotes/origin/main")

        assertEquals(GitPushTarget("origin", "feature", hasUpstream = true), pushes.pushTarget(repo, "feature"))
    }

    @Test
    fun `the target is the upstream with push_default upstream or tracking`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "push.default", "upstream")
        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("config", "push.default", "tracking")
        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `the push remote and remote_pushDefault come before the upstream, with the branch's own name`() {
        git("remote", "add", "fork", folder.resolve("fork").toString())
        git("remote", "add", "extra", folder.resolve("extra").toString())
        git("config", "branch.main.merge", "refs/heads/trunk")
        // Even where git would push to the upstream: these remotes are not the upstream's.
        git("config", "push.default", "upstream")
        git("config", "remote.pushDefault", "extra")
        assertEquals(GitPushTarget("extra", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("config", "branch.main.pushRemote", "fork")
        assertEquals(GitPushTarget("fork", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `a push remote that is the upstream's remote takes the upstream's branch with push_default upstream`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "branch.main.pushRemote", "origin")
        git("config", "push.default", "upstream")

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

    @Test
    fun `a push stopped by its timeout may have reached the remote and says so`() {
        val script = folder.resolve("slow-git")
        script.writeText(FAKE_GIT_START + "echo 'Writing objects: 100%' >&2\nexec sleep 30\n")
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

    // remoteBranch

    @Test
    fun `the remote branch maps a tracking ref back through the fetch refspecs`() {
        assertEquals("refs/heads/main", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"))
        assertEquals("refs/heads/feature/x", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/feature/x"))
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/upstream/main"), "another remote's")

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/trunk")
        assertEquals("refs/heads/main", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/trunk"))
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"), "no refspec maps it")

        git("config", "--add", "remote.origin.fetch", "+refs/tags/*:refs/remotes/origin/tags/*")
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/tags/v1"), "no branch")

        git("config", "--unset-all", "remote.origin.fetch")
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"), "without refspecs")
        assertNull(pushes.remoteBranch(repo, "-x", "refs/remotes/-x/main"))
    }

    // deleteRemoteRefs

    private fun delete(
        vararg refs: GitRemoteDeletion,
        cancellation: GitCancellation = GitCancellation(),
    ) = pushes.deleteRemoteRefs(repo, "origin", refs.toList(), cancellation)

    private fun serverRefs() = git("for-each-ref", "--format=%(refname)", directory = server).lines().toSet()

    /** Creates the branches [names] at `HEAD` on the remote and fetches them. */
    private fun remoteBranches(vararg names: String) {
        for (name in names) git("push", "-q", "origin", "HEAD:refs/heads/$name")
        git("fetch", "-q")
    }

    @Test
    fun `deleting a remote branch deletes its remote-tracking ref too`() {
        remoteBranches("topic")

        val deleted = delete(GitRemoteDeletion("refs/heads/topic", rev("origin/topic")))

        assertTrue(deleted.result.success, deleted.result.message)
        assertEquals(
            mapOf("refs/heads/topic" to GitRemoteRefResult(GitRemoteDeletionOutcome.DELETED)),
            deleted.outcomes,
        )
        assertFalse("refs/heads/topic" in serverRefs())
        assertEquals("", git("for-each-ref", "refs/remotes/origin/topic"))
    }

    @Test
    fun `a branch that moved since the fetch is stale and stays`() {
        remoteBranches("topic")
        val looked = rev("origin/topic")
        git("fetch", "-q", directory = other)
        git("switch", "-q", "topic", directory = other)
        val moved = commit("Moved", other)
        git("push", "-q", "origin", "topic", directory = other)

        val deleted = delete(GitRemoteDeletion("refs/heads/topic", looked))

        assertFalse(deleted.result.success)
        assertFalse(deleted.cancelled)
        assertEquals(GitRemoteDeletionOutcome.STALE, deleted.outcomes["refs/heads/topic"]?.outcome)
        assertEquals(moved, remoteTip("topic"))
        assertEquals(looked, rev("origin/topic"), "the remote-tracking ref stays")
    }

    @Test
    fun `a branch the remote no longer has is missing`() {
        remoteBranches("topic")
        git("update-ref", "-d", "refs/heads/topic", directory = server)

        val deleted = delete(GitRemoteDeletion("refs/heads/topic", rev("origin/topic")))

        assertFalse(deleted.result.success)
        assertEquals(GitRemoteDeletionOutcome.MISSING, deleted.outcomes["refs/heads/topic"]?.outcome)
    }

    @Test
    fun `tags are deleted without a lease, also one the remote does not have`() {
        git("tag", "v1")
        git("tag", "-a", "v2", "-m", "Two")
        git("push", "-q", "origin", "v1", "v2")
        git("tag", "v3")

        val deleted =
            delete(
                GitRemoteDeletion("refs/tags/v1"),
                GitRemoteDeletion("refs/tags/v2"),
                GitRemoteDeletion("refs/tags/v3"),
            )

        assertTrue(deleted.result.success, deleted.result.message)
        assertEquals(
            listOf("refs/tags/v1", "refs/tags/v2", "refs/tags/v3").associateWith {
                GitRemoteRefResult(GitRemoteDeletionOutcome.DELETED)
            },
            deleted.outcomes,
        )
        assertTrue(serverRefs().none { it.startsWith("refs/tags/") })
        assertEquals("v1\nv2\nv3", git("tag"), "the local tags stay")
    }

    @Test
    fun `several refs in one push are reported one by one`() {
        remoteBranches("a", "b", "gone")
        git("tag", "v1")
        git("push", "-q", "origin", "v1")
        git("update-ref", "-d", "refs/heads/gone", directory = server)
        val head = rev("HEAD")
        commit("Moved")
        git("push", "-q", "-f", "origin", "HEAD:refs/heads/b")
        git("update-ref", "refs/remotes/origin/b", head)

        val deleted =
            delete(
                GitRemoteDeletion("refs/heads/a", rev("origin/a")),
                GitRemoteDeletion("refs/heads/b", rev("origin/b")),
                GitRemoteDeletion("refs/heads/gone", rev("origin/gone")),
                GitRemoteDeletion("refs/tags/v1"),
            )

        assertFalse(deleted.result.success)
        assertEquals(
            mapOf(
                "refs/heads/a" to GitRemoteDeletionOutcome.DELETED,
                "refs/heads/b" to GitRemoteDeletionOutcome.STALE,
                "refs/heads/gone" to GitRemoteDeletionOutcome.MISSING,
                "refs/tags/v1" to GitRemoteDeletionOutcome.DELETED,
            ),
            deleted.outcomes.mapValues { it.value.outcome },
        )
        assertEquals(setOf("refs/heads/main", "refs/heads/b"), serverRefs())
    }

    @Test
    fun `a deletion the remote refuses is rejected with git's summary`() {
        remoteBranches("topic")
        git("config", "receive.denyDeletes", "true", directory = server)

        val deleted = delete(GitRemoteDeletion("refs/heads/topic", rev("origin/topic")))

        assertFalse(deleted.result.success)
        val outcome = deleted.outcomes["refs/heads/topic"]
        assertEquals(GitRemoteDeletionOutcome.REJECTED, outcome?.outcome)
        assertEquals("[remote rejected] (deletion prohibited)", outcome?.summary)
        assertContains(deleted.result.message, "denying ref deletion")
        assertTrue("refs/heads/topic" in serverRefs())
    }

    @Test
    fun `names that cannot be passed, short names and short hashes are refused without running git`() {
        remoteBranches("topic")
        val full = rev("origin/topic")
        val cases =
            listOf(
                "-x" to listOf(GitRemoteDeletion("refs/heads/topic", full)),
                "origin" to listOf(GitRemoteDeletion("topic", full)),
                "origin" to listOf(GitRemoteDeletion("-x")),
                "origin" to listOf(GitRemoteDeletion("refs/heads/topic", full.take(12))),
                "origin" to listOf(GitRemoteDeletion("refs/heads/topic", "-f")),
            )

        for ((remote, refs) in cases) {
            val deleted = pushes.deleteRemoteRefs(repo, remote, refs, GitCancellation())
            assertFalse(deleted.result.success, "$remote $refs")
            assertEquals(emptyMap(), deleted.outcomes)
        }
        assertEquals(
            "'topic' is no full ref name.",
            pushes.deleteRemoteRefs(repo, "origin", cases[1].second, GitCancellation()).result.message,
        )
        assertTrue("refs/heads/topic" in serverRefs())
    }

    @Test
    fun `a deletion cancelled before does not run, one cancelled while git runs stops it`() {
        remoteBranches("topic")
        val ref = GitRemoteDeletion("refs/heads/topic", rev("origin/topic"))
        val cancelled = delete(ref, cancellation = GitCancellation().apply { cancel() })
        assertEquals(GitRemoteDeletionResult(GitResult(false, "Cancelled."), cancelled = true), cancelled)

        val pid = folder.resolve("hook-pid")
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\necho \$\$ > '$pid'\nsleep 30\n")
        hook.toFile().setExecutable(true)
        val cancellation = GitCancellation()
        val deleting = CompletableFuture.supplyAsync { delete(ref, cancellation = cancellation) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pid.exists() || pid.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("the hook did not start")
            Thread.sleep(20)
        }
        cancellation.cancel()

        assertEquals(
            GitRemoteDeletionResult(GitResult(false, "Cancelled."), cancelled = true),
            deleting.get(10, TimeUnit.SECONDS),
        )
        assertTrue("refs/heads/topic" in serverRefs())
    }

    /**
     * A fake git that logs its calls; a push reports a stale `fix` and a deleted `v1` after [pushDelay] seconds, `remote
     * get-url` answers with a URL, and `ls-remote` runs [lsRemote].
     */
    private fun deletingGit(
        log: Path,
        pushDelay: String = "0",
        lsRemote: String = "exit 128",
        pushUrl: String = "ssh://push.example/repo.git",
    ): GitCli {
        val script = folder.resolve("fake-git")
        script.writeText(
            FAKE_GIT_START +
                "case \"\$*\" in *config*) exit 1;; esac\n" +
                "echo \"\$GIT_SSH_COMMAND|\$*\" >> '$log'\n" +
                "case \"\$1\" in remote) echo '$pushUrl'; exit 0;; ls-remote) $lsRemote;; esac\n" +
                "sleep $pushDelay\n" +
                "printf 'To server\\n!\\t(delete):refs/heads/fix\\t[rejected] (stale info)\\n" +
                "-\\t:refs/tags/v1\\t[deleted]\\nDone\\n'\nexit 1\n",
        )
        script.toFile().setExecutable(true)
        return GitCli(script.toString(), System.getenv().filterKeys { !it.startsWith("GIT_") })
    }

    @Test
    fun `the deletion's command line, and a stale lease stays open when ls-remote fails`() {
        val log = folder.resolve("calls.txt")
        val fake = deletingGit(log)
        assumeTrue(fake.available, "no /bin/sh to stand in for git")
        val expected = "a".repeat(40)

        val deleted =
            GitPush(fake).deleteRemoteRefs(
                repo,
                "origin",
                listOf(GitRemoteDeletion("refs/heads/fix", expected), GitRemoteDeletion("refs/tags/v1")),
                GitCancellation(),
            )

        assertEquals(
            listOf(
                "ssh -o BatchMode=yes|push --porcelain --recurse-submodules=no " +
                    "--force-with-lease=refs/heads/fix:$expected --end-of-options origin :refs/heads/fix :refs/tags/v1",
                "|remote get-url --push --end-of-options origin",
                "ssh -o BatchMode=yes|ls-remote --end-of-options ssh://push.example/repo.git refs/heads/fix",
            ),
            log.toFile().readLines(),
        )
        assertEquals(
            mapOf(
                "refs/heads/fix" to
                    GitRemoteRefResult(GitRemoteDeletionOutcome.STALE_OR_MISSING, "[rejected] (stale info)"),
                "refs/tags/v1" to GitRemoteRefResult(GitRemoteDeletionOutcome.DELETED),
            ),
            deleted.outcomes,
        )
        assertFalse(deleted.result.success)
        assertFalse(deleted.cancelled)
    }

    @Test
    fun `a push URL that cannot be passed to git is not listed, the lease stays open`() {
        val log = folder.resolve("calls.txt")
        val fake = deletingGit(log, pushUrl = "-oProxyCommand=x")
        assumeTrue(fake.available, "no /bin/sh to stand in for git")

        val deleted =
            GitPush(fake).deleteRemoteRefs(
                repo,
                "origin",
                listOf(GitRemoteDeletion("refs/heads/fix", "a".repeat(40))),
                GitCancellation(),
            )

        assertEquals(GitRemoteDeletionOutcome.STALE_OR_MISSING, deleted.outcomes["refs/heads/fix"]?.outcome)
        assertTrue(log.toFile().readLines().none { "ls-remote" in it }, "ls-remote ran")
    }

    @Test
    fun `ls-remote gets the time the push left of the one timeout`() {
        val fake = deletingGit(folder.resolve("calls.txt"), pushDelay = "1", lsRemote = "exec sleep 30")
        assumeTrue(fake.available, "no /bin/sh to stand in for git")
        val started = System.nanoTime()

        val deleted =
            GitPush(fake).deleteRemoteRefs(
                repo,
                "origin",
                listOf(GitRemoteDeletion("refs/heads/fix", "a".repeat(40))),
                GitCancellation(),
                timeout = 2.seconds,
            )

        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(GitRemoteDeletionOutcome.STALE_OR_MISSING, deleted.outcomes["refs/heads/fix"]?.outcome)
        // About 2 s: the push takes 1 s, ls-remote the 1 s left; with a whole timeout of its own, 3 s.
        assertTrue(took < 2800, "the push and ls-remote took $took ms, not 1 s + 2 s")
    }

    @Test
    fun `a cancel while ls-remote runs keeps what the push deleted`() {
        val log = folder.resolve("calls.txt")
        val fake = deletingGit(log, lsRemote = "exec sleep 30")
        assumeTrue(fake.available, "no /bin/sh to stand in for git")
        val cancellation = GitCancellation()

        val deleting =
            CompletableFuture.supplyAsync {
                GitPush(fake).deleteRemoteRefs(
                    repo,
                    "origin",
                    listOf(GitRemoteDeletion("refs/heads/fix", "a".repeat(40)), GitRemoteDeletion("refs/tags/v1")),
                    cancellation,
                )
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!log.exists() || log.readText().lines().none { it.contains("ls-remote") }) {
            if (System.nanoTime() > deadline) fail("ls-remote did not start")
            Thread.sleep(20)
        }
        cancellation.cancel()
        val deleted = deleting.get(10, TimeUnit.SECONDS)

        assertFalse(deleted.cancelled, "the push was not interrupted")
        assertEquals(
            mapOf(
                "refs/heads/fix" to GitRemoteDeletionOutcome.STALE_OR_MISSING,
                "refs/tags/v1" to GitRemoteDeletionOutcome.DELETED,
            ),
            deleted.outcomes.mapValues { it.value.outcome },
        )
    }

    @Test
    fun `ls-remote asks the push URL, not the one fetched from`() {
        remoteBranches("topic")
        git("update-ref", "-d", "refs/heads/topic", directory = server)
        git("config", "remote.origin.pushurl", server.toString())
        git("config", "remote.origin.url", folder.resolve("nowhere.git").toString())

        val deleted = delete(GitRemoteDeletion("refs/heads/topic", rev("origin/topic")))

        assertEquals(GitRemoteDeletionOutcome.MISSING, deleted.outcomes["refs/heads/topic"]?.outcome)
    }
}
