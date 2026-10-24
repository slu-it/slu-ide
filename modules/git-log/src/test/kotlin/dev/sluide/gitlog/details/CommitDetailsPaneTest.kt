package dev.sluide.gitlog.details

import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The text the details pane makes of a commit, without controls. */
class CommitDetailsPaneTest {
    @Test
    fun `a message is the subject, a blank line and the body`() {
        assertEquals("Subject" to "Body of 1.\nMore body.", splitMessage("Subject\n\nBody of 1.\nMore body.\n"))
        assertEquals("Subject" to "", splitMessage("Subject"))
        assertEquals("Subject" to "", splitMessage("Subject\n\n\n"))
    }

    @Test
    fun `the subject is a paragraph, leading blank lines are skipped and blank lines around the body dropped`() {
        assertEquals("one\ntwo" to "body", splitMessage("one\ntwo\n\nbody"))
        assertEquals("Subject" to "Body", splitMessage("\n\n  \nSubject\n\n\n\nBody\n\n\n"))
        assertEquals("Subject" to "Body\n\nsecond paragraph", splitMessage("Subject\n\nBody\n\nsecond paragraph"))
    }

    @Test
    fun `an empty message has neither a subject nor a body`() {
        assertEquals("" to "", splitMessage(""))
        assertEquals("" to "", splitMessage("\n \n"))
    }

    private val time = Instant.ofEpochSecond(1_000_020)
    private val author = GitCommit("h1", emptyList(), "Alice", "alice@example.com", time, "Subject")

    private fun at(time: Instant) = "t${time.epochSecond / 60}"

    private fun details(
        committer: String = "Alice",
        email: String = "alice@example.com",
        committerTime: Instant = time,
    ) = GitCommitDetails(author, committer, email, committerTime, "Subject")

    @Test
    fun `the committer line is left out when the author committed at the same minute`() {
        assertNull(committedLine(details(), ::at))
        assertNull(committedLine(details(committerTime = time.plusSeconds(30)), ::at), "the same as shown")
    }

    @Test
    fun `another time alone adds a line without a name`() {
        assertEquals("committed on t16677", committedLine(details(committerTime = time.plusSeconds(600)), ::at))
    }

    @Test
    fun `another committer is named, also at the same time, and by a different email alone`() {
        assertEquals("committed on t16667 by Bob", committedLine(details(committer = "Bob"), ::at))
        assertEquals(
            "committed on t16667 by Alice",
            committedLine(details(email = "alice@work.example.com"), ::at),
            "same name, another email",
        )
    }
}
