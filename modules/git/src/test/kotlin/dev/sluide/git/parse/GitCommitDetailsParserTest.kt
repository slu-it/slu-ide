package dev.sluide.git.parse

import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitCommitDetailsParserTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun output(
        vararg fields: String,
        message: String,
    ) = (fields.toList() + message).joinToString("\u001f") + "\n"

    @Test
    fun `reads the commit, the committer and the full message`() {
        val details =
            GitCommitDetailsParser.parse(
                output(
                    a,
                    "$b $c",
                    "Jane",
                    "jane@example.com",
                    "1000",
                    "John",
                    "john@example.com",
                    "2000",
                    message = "Merge it\n\nThe body,\nover two lines.\n\n",
                ),
            )

        assertEquals(
            GitCommitDetails(
                GitCommit(a, listOf(b, c), "Jane", "jane@example.com", Instant.ofEpochSecond(1000), "Merge it"),
                "John",
                "john@example.com",
                Instant.ofEpochSecond(2000),
                "Merge it\n\nThe body,\nover two lines.",
            ),
            details,
        )
    }

    @Test
    fun `the subject joins the lines of the first paragraph, and separators stay in the message`() {
        val details =
            GitCommitDetailsParser.parse(
                output(a, "", "J", "j@x", "1", "J", "j@x", "1", message = "A long\nsubject\n\nBody \u001f here\n"),
            )

        assertEquals("A long subject", details?.commit?.subject)
        assertEquals("A long\nsubject\n\nBody \u001f here", details?.message)
        assertEquals(emptyList(), details?.commit?.parents)
    }

    @Test
    fun `trailing CRLF line breaks are not part of the message`() {
        val details =
            GitCommitDetailsParser.parse(
                output(a, "", "J", "j@x", "1", "J", "j@x", "1", message = "Subject\r\n\r\nBody\r\n\r\n"),
            )

        assertEquals("Subject\r\n\r\nBody", details?.message)
        assertEquals("Subject", details?.commit?.subject)
    }

    @Test
    fun `malformed output gives nothing`() {
        assertNull(GitCommitDetailsParser.parse(""))
        assertNull(GitCommitDetailsParser.parse(output("x", "", "J", "j@x", "1", "J", "j@x", "1", message = "M")))
        assertNull(GitCommitDetailsParser.parse(output(a, "", "J", "j@x", "1", "J", "j@x", "now", message = "M")))
        assertNull(GitCommitDetailsParser.parse(output(a, "", "J", "j@x", "1", "J", "j@x", message = "1")))
    }

    @Test
    fun `a commit with a hash that is no hash, or a time that is no number, is no commit`() {
        fun parse(
            hash: String = a,
            authorTime: String = "1",
            committerTime: String = "2",
        ) = GitCommitDetailsParser.parse(
            output(hash, b, "J", "j@x", authorTime, "K", "k@x", committerTime, message = "m"),
        )

        assertEquals(a, parse()?.commit?.hash)
        assertNull(parse(hash = "g".repeat(40)))
        assertNull(parse(hash = a.take(39)))
        assertNull(parse(authorTime = "x"))
        assertNull(parse(committerTime = "x"))
        assertNull(parse(committerTime = ""))
    }

    @Test
    fun `the subject skips leading blank lines and ends at the first blank one`() {
        fun subject(message: String) =
            GitCommitDetailsParser
                .parse(output(a, "", "J", "j@x", "1", "K", "k@x", "2", message = message))
                ?.commit
                ?.subject

        assertEquals("first second", subject("\n  \nfirst\n second \n\nbody"))
        assertEquals("only", subject("only"))
        assertEquals("", subject(""))
        assertEquals("a", subject("a\n \nb"))
    }
}
