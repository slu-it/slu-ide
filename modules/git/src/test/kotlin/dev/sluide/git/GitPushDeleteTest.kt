package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Deletes remote branches and tags with the real git; skipped if git is not installed. */
internal class GitPushDeleteTest : GitPushFixture() {
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
