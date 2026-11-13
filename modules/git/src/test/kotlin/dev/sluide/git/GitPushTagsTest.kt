package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** Pushes tags with the real git; skipped if git is not installed. */
internal class GitPushTagsTest : GitPushFixture() {
    private fun pushTags(
        vararg tags: String,
        cancellation: GitCancellation = GitCancellation(),
    ) = pushes.pushTags(repo, "origin", tags.toList(), cancellation)

    private fun serverTags() = git("tag", "--list", directory = server).lines().filter { it.isNotEmpty() }.toSet()

    private fun serverObject(tag: String) = git("rev-parse", "refs/tags/$tag", directory = server)

    @Test
    fun `a lightweight tag is pushed to the remote`() {
        git("tag", "v1")

        val pushed = pushTags("v1")

        assertTrue(pushed.result.success)
        assertEquals(mapOf("v1" to GitTagPushRefResult(GitTagPushOutcome.PUSHED)), pushed.outcomes)
        assertEquals(rev("HEAD"), serverObject("v1"))
    }

    @Test
    fun `an annotated tag is pushed with its tag object`() {
        git("tag", "-a", "-m", "Release", "v1")

        val pushed = pushTags("v1")

        assertEquals(GitTagPushOutcome.PUSHED, pushed.outcomes["v1"]?.outcome)
        assertEquals(rev("v1"), serverObject("v1"))
        assertEquals("tag", git("cat-file", "-t", "refs/tags/v1", directory = server))
    }

    @Test
    fun `two tags are pushed in one call`() {
        git("tag", "v1")
        git("tag", "-a", "-m", "Two", "v2")

        val pushed = pushTags("v1", "v2")

        assertTrue(pushed.result.success)
        assertEquals(
            mapOf(
                "v1" to GitTagPushRefResult(GitTagPushOutcome.PUSHED),
                "v2" to GitTagPushRefResult(GitTagPushOutcome.PUSHED),
            ),
            pushed.outcomes,
        )
        assertEquals(setOf("v1", "v2"), serverTags())
    }

    @Test
    fun `a tag the remote has at the same object is up to date and a success`() {
        git("tag", "v1")
        pushTags("v1")

        val again = pushTags("v1")

        assertTrue(again.result.success)
        assertEquals(mapOf("v1" to GitTagPushRefResult(GitTagPushOutcome.UP_TO_DATE)), again.outcomes)
    }

    @Test
    fun `a tag the remote has at another commit exists and fails, the others are still pushed`() {
        git("tag", "v1")
        val remoteTag = pushTags("v1")
        assertTrue(remoteTag.result.success)
        val first = rev("v1")
        commit("Second")
        git("tag", "-f", "v1")
        git("tag", "v2")

        val pushed = pushTags("v1", "v2")

        assertFalse(pushed.result.success)
        assertEquals(
            mapOf(
                "v1" to GitTagPushRefResult(GitTagPushOutcome.EXISTS, "[rejected] (already exists)"),
                "v2" to GitTagPushRefResult(GitTagPushOutcome.PUSHED),
            ),
            pushed.outcomes,
        )
        assertEquals(first, serverObject("v1"))
        assertEquals(rev("v2"), serverObject("v2"))
    }

    @Test
    fun `a tag a hook of the remote declines is rejected with git's summary`() {
        val hook = server.resolve("hooks/pre-receive")
        hook.writeText("#!/bin/sh\necho 'declined by the server' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        git("tag", "v1")

        val pushed = pushTags("v1")

        assertFalse(pushed.result.success)
        val outcome = pushed.outcomes["v1"]
        assertEquals(GitTagPushOutcome.REJECTED, outcome?.outcome)
        assertEquals("[remote rejected] (pre-receive hook declined)", outcome?.summary)
        assertContains(pushed.result.message, "declined by the server")
        assertEquals(emptySet(), serverTags())
    }

    @ParameterizedTest
    @ValueSource(strings = ["-x", ""])
    fun `names that cannot be passed are refused without running git`(bad: String) {
        git("tag", "v1")
        val ran = folder.resolve("hook-ran")
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\ntouch '$ran'\n")
        hook.toFile().setExecutable(true)

        val asTag = pushes.pushTags(repo, "origin", listOf("v1", bad), GitCancellation())
        val asRemote = pushes.pushTags(repo, bad, listOf("v1"), GitCancellation())

        for (refused in listOf(asTag, asRemote)) {
            assertFalse(refused.result.success)
            assertFalse(refused.cancelled)
            assertEquals(emptyMap(), refused.outcomes)
        }
        assertFalse(ran.exists(), "git ran")
        assertEquals(emptySet(), serverTags())
    }

    @ParameterizedTest
    @ValueSource(strings = ["v*", "a:b"])
    fun `a name that is no tag name is refused, a pattern or refspec of its own never reaches git`(bad: String) {
        git("tag", "v1")
        val ran = folder.resolve("hook-ran")
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\ntouch '$ran'\n")
        hook.toFile().setExecutable(true)

        val pushed = pushTags("v1", bad)

        assertEquals(GitTagPushResult(GitResult(false, "'$bad' is no valid tag name.")), pushed)
        assertFalse(ran.exists(), "git ran")
        assertEquals(emptySet(), serverTags())
    }

    @Test
    fun `a tag named twice is pushed once and reported as pushed`() {
        git("tag", "v1")

        val pushed = pushTags("v1", "v1")

        assertTrue(pushed.result.success)
        assertEquals(mapOf("v1" to GitTagPushRefResult(GitTagPushOutcome.PUSHED)), pushed.outcomes)
        assertEquals(setOf("v1"), serverTags())
    }

    @Test
    fun `no tags is a programming error`() {
        assertFailsWith<IllegalArgumentException> {
            pushes.pushTags(repo, "origin", emptyList(), GitCancellation())
        }
    }

    @Test
    fun `a push cancelled before does not run, one cancelled while git runs stops it`() {
        git("tag", "v1")
        val cancelled = pushTags("v1", cancellation = GitCancellation().apply { cancel() })
        assertEquals(GitTagPushResult(GitResult(false, "Cancelled."), cancelled = true), cancelled)

        val pid = folder.resolve("hook-pid")
        val hook = repo.resolve(".git/hooks/pre-push")
        hook.writeText("#!/bin/sh\necho \$\$ > '$pid'\nsleep 30\n")
        hook.toFile().setExecutable(true)
        val cancellation = GitCancellation()
        val pushing = CompletableFuture.supplyAsync { pushTags("v1", cancellation = cancellation) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pid.exists() || pid.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("the hook did not start")
            Thread.sleep(20)
        }
        cancellation.cancel()

        assertEquals(
            GitTagPushResult(GitResult(false, "Cancelled."), cancelled = true),
            pushing.get(10, TimeUnit.SECONDS),
        )
        assertEquals(emptySet(), serverTags())
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

        val pushed = GitPush(fake).pushTags(repo, "origin", listOf("v1"), GitCancellation(), timeout = 1.seconds)

        assertFalse(pushed.result.success)
        assertFalse(pushed.cancelled)
        assertEquals(
            "git did not finish within 1 s.\nIt may have reached origin. Fetch to check.",
            pushed.result.message,
        )
    }
}
