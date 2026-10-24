package dev.sluide.gitlog.data

import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitRevisionResolution
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The words of a commit message that may be hashes, and how the details resolve them (ADR 0045). */
class CommitLinksTest {
    private fun candidates(text: String) = hashCandidates(text).map { text.substring(it) }

    @Test
    fun `finds words of 7 to 40 hexadecimal characters, not inside longer words or hex runs`() {
        val full = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            listOf("abc1234", "DEADBEEF", full, "1234567", "cafe123"),
            candidates("Revert abc1234 and DEADBEEF (see $full), fixes #1234567; also cafe123."),
        )
        assertEquals(emptyList(), candidates("abc123 is short, ${full}8 is too long"))
        assertEquals(emptyList(), candidates("xabc1234 abc1234y abc1234_ _abc1234 0xabc1234 g1234567"))
        assertEquals(emptyList(), candidates("äabc1234 abc1234ß"), "letters of any script are word characters")
        assertEquals(listOf("abc1234", "def5678"), candidates("abc1234-def5678/x"))
    }

    @Test
    fun `resolves the candidates of the message with one call, linking only those naming one commit by hash`() {
        val repo = Path.of("/repo")
        val background = ArrayDeque<Runnable>()
        val dispatched = ArrayDeque<Runnable>()
        val asked = mutableListOf<List<String>>()
        val full = "abc1234" + "0".repeat(33)
        val commit = GitCommit("h", emptyList(), "A", "a@example.com", Instant.EPOCH, "Subject")
        val message = "Revert ABC1234\n\nAlso abc1234, deadbee (a branch), 1111111 (unknown) and ffffffff (ambiguous)."
        val row = GitLogRow(commit, GraphLayout().next("h", emptyList()))
        val details =
            CommitDetails(
                { _, _, _ -> GitCommitDetails(commit, "A", "a@example.com", Instant.EPOCH, message) },
                { _, _, _ -> emptyList() },
                background::addLast,
                dispatched::addLast,
                { root, revisions, _ ->
                    assertEquals(repo, root)
                    asked += revisions
                    revisions.map { revision ->
                        when (revision) {
                            "abc1234" -> GitRevisionResolution.Commit(full)

                            // A branch named like a hash points somewhere else.
                            "deadbee" -> GitRevisionResolution.Commit("1".repeat(40))

                            "ffffffff" -> GitRevisionResolution.Ambiguous

                            else -> GitRevisionResolution.Missing
                        }
                    }
                },
            ).apply {
                selection = { listOf(row) }
                root = { repo }
            }

        details.selectionChanged()
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            dispatched.removeFirstOrNull()?.run()
            background.removeFirstOrNull()?.run()
        }

        assertEquals(listOf(listOf("abc1234", "deadbee", "1111111", "ffffffff")), asked)
        assertEquals(mapOf("abc1234" to full), (details.state.get() as CommitDetailsState.Loaded).links)
    }

    /** The state of the details of a commit whose message is [message], read with the given [resolve]. */
    private fun loadedWith(
        message: String,
        resolve: (List<String>) -> List<GitRevisionResolution>?,
    ): CommitDetailsState {
        val repo = Path.of("/repo")
        val background = ArrayDeque<Runnable>()
        val dispatched = ArrayDeque<Runnable>()
        val commit = GitCommit("h", emptyList(), "A", "a@example.com", Instant.EPOCH, "Subject")
        val row = GitLogRow(commit, GraphLayout().next("h", emptyList()))
        val details =
            CommitDetails(
                { _, _, _ -> GitCommitDetails(commit, "A", "a@example.com", Instant.EPOCH, message) },
                { _, _, _ -> emptyList() },
                background::addLast,
                dispatched::addLast,
                { _, revisions, _ -> resolve(revisions) },
            ).apply {
                selection = { listOf(row) }
                root = { repo }
            }
        details.selectionChanged()
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            dispatched.removeFirstOrNull()?.run()
            background.removeFirstOrNull()?.run()
        }
        return details.state.get()
    }

    @Test
    fun `details without links are still shown when resolving the candidates fails`() {
        val state = loadedWith("Revert abc1234") { null }
        assertEquals(emptyMap(), (state as CommitDetailsState.Loaded).links)
    }

    @Test
    fun `at most 100 distinct candidates are resolved`() {
        val words = (1..120).map { "%07x".format(0xabc0000 + it) }
        val asked = mutableListOf<List<String>>()

        val state =
            loadedWith(words.joinToString(" ") + " " + words.first()) { revisions ->
                asked += revisions
                revisions.map { GitRevisionResolution.Missing }
            }

        assertEquals(listOf(words.take(100)), asked)
        assertEquals(emptyMap(), (state as CommitDetailsState.Loaded).links)
    }
}
