package dev.sluide.gitlog.operations

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitResult
import dev.sluide.git.GitTagPushOutcome
import dev.sluide.git.GitTagPushRefResult
import dev.sluide.git.GitTagPushResult
import dev.sluide.gitlog.data.FakeWrites
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagPushOperationsTest {
    private val root = Path.of("/repo")

    private fun result(
        success: Boolean,
        message: String,
        vararg outcomes: Pair<String, GitTagPushRefResult>,
    ) = GitTagPushResult(GitResult(success, message), outcomes = outcomes.toMap())

    private fun upToDate() = GitTagPushRefResult(GitTagPushOutcome.UP_TO_DATE)

    @Test
    fun `one tag to one remote is pushed and told`() {
        val writes = FakeWrites()
        val progress = mutableListOf<String>()
        val operation = PushTags(listOf("v1"), listOf("origin"))

        val outcome = operation.run(writes, root, RunContext(progress = { progress += it }))

        assertEquals(RefOutcome(GitResult(true, "", changed = false), "Pushed tag v1 to origin."), outcome)
        assertEquals(listOf("push-tags origin refs/tags/v1"), writes.calls)
        assertEquals(listOf("Pushing tag v1 to origin…"), progress)
        assertTrue(operation.cancellable)
        assertNull(operation.fileChange)
        assertFalse(operation.holdsStatus)
        assertEquals("Push Failed", operation.errorTitle)
    }

    @Test
    fun `several tags go to each remote in order`() {
        val writes = FakeWrites()
        val progress = mutableListOf<String>()

        val outcome =
            PushTags(listOf("v1", "v2", "v3"), listOf("origin", "upstream"))
                .run(writes, root, RunContext(progress = { progress += it }))

        assertEquals("Pushed 3 tags to origin and upstream.", outcome.done)
        assertEquals(
            listOf(
                "push-tags origin refs/tags/v1 refs/tags/v2 refs/tags/v3",
                "push-tags upstream refs/tags/v1 refs/tags/v2 refs/tags/v3",
            ),
            writes.calls,
        )
        assertEquals(listOf("Pushing 3 tags to origin…", "Pushing 3 tags to upstream…"), progress)
    }

    @ParameterizedTest
    @CsvSource(
        "v1;origin,Tag v1 is already on origin.",
        "v1|v2;origin|upstream,The tags are already on origin and upstream.",
        delimiter = ',',
    )
    fun `tags that every remote has are told so`(
        setup: String,
        expected: String,
    ) {
        val (tags, remotes) = setup.split(";").map { it.split("|") }
        val writes = FakeWrites()
        writes.pushTagsResult = { _, names -> result(true, "", *names.map { it to upToDate() }.toTypedArray()) }

        val outcome = PushTags(tags, remotes).run(writes, root)

        assertEquals(expected, outcome.done)
        assertTrue(outcome.result.success)
    }

    @Test
    fun `a tag the remote has elsewhere stops the pushes and names what the remotes before got`() {
        val writes = FakeWrites()
        writes.pushTagsResult = { remote, names ->
            if (remote == "upstream") {
                result(
                    false,
                    "error: failed to push some refs",
                    "v1" to GitTagPushRefResult(GitTagPushOutcome.PUSHED),
                    "v2" to GitTagPushRefResult(GitTagPushOutcome.EXISTS, "[rejected] (already exists)"),
                )
            } else {
                result(true, "", *names.map { it to GitTagPushRefResult(GitTagPushOutcome.PUSHED) }.toTypedArray())
            }
        }
        val operation = PushTags(listOf("v1", "v2"), listOf("origin", "upstream", "fork"))

        val outcome = operation.run(writes, root)

        assertEquals(2, writes.calls.size, "fork is not tried")
        assertFalse(outcome.result.success)
        assertEquals("Not every tag could be pushed to upstream.", outcome.failure)
        assertEquals(
            "v2: upstream has another tag v2; delete it there or rename yours.\n" +
                "Skipped: fork.\n" +
                "Pushed to origin before that: v1, v2.\n\nerror: failed to push some refs",
            outcome.result.message,
        )
    }

    @Test
    fun `one tag that fails says so in the header, a refusal gives git's summary, silence says not pushed`() {
        val writes = FakeWrites()
        writes.pushTagsResult = { _, _ ->
            result(
                false,
                "remote: denied",
                "v1" to GitTagPushRefResult(GitTagPushOutcome.REJECTED, "[remote rejected] (hook)"),
            )
        }
        val rejected = PushTags(listOf("v1"), listOf("origin")).run(writes, root)
        assertEquals("Tag v1 could not be pushed to origin.", rejected.failure)
        assertEquals("v1: [remote rejected] (hook)\n\nremote: denied", rejected.result.message)

        writes.pushTagsResult = { _, _ -> result(false, "fatal: could not read from remote") }
        val silent = PushTags(listOf("v1", "v2"), listOf("origin")).run(writes, root)
        assertEquals(
            "v1: no answer from origin\nv2: no answer from origin\n\nfatal: could not read from remote",
            silent.result.message,
        )
    }

    @Test
    fun `a push a cancel interrupted may have reached its remote, one skipped after a cancel is only cancelled`() {
        val writes = FakeWrites()
        writes.pushTagsResult = { _, _ -> GitTagPushResult(GitResult(false, "Cancelled."), cancelled = true) }
        val interrupted = PushTags(listOf("v1"), listOf("origin")).run(writes, root, RunContext(GitCancellation()))
        assertEquals("Push cancelled; it may have reached origin. Fetch to check.", interrupted.done)
        assertTrue(interrupted.cancelled)

        writes.calls.clear()
        val cancellation = GitCancellation()
        writes.pushTagsResult = { _, names ->
            cancellation.cancel()
            result(true, "", *names.map { it to GitTagPushRefResult(GitTagPushOutcome.PUSHED) }.toTypedArray())
        }
        val between = PushTags(listOf("v1"), listOf("origin", "upstream")).run(writes, root, RunContext(cancellation))
        assertEquals(listOf("push-tags origin refs/tags/v1"), writes.calls)
        assertEquals("Pushed to origin before that: v1. Push cancelled.", between.done)
        assertTrue(between.cancelled)
        assertTrue(between.result.success)
    }
}
