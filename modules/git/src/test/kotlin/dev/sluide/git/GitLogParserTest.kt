package dev.sluide.git

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLogParserTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun record(vararg fields: String) = fields.joinToString("\u001f") + "\u0000"

    @Test
    fun `reads the fields of each record`() {
        val output = record(a, b, "Jane Doe", "jane@example.com", "1792163000", "Fix the parser")

        assertEquals(
            listOf(
                GitCommit(
                    a,
                    listOf(b),
                    "Jane Doe",
                    "jane@example.com",
                    Instant.ofEpochSecond(1792163000),
                    "Fix the parser",
                ),
            ),
            GitLogParser.parse(output),
        )
    }

    @Test
    fun `reads root commits without parents and merges with two`() {
        val output =
            record(c, "$a $b", "Jane", "jane@example.com", "3", "Merge branch 'x'") +
                record(b, a, "Jane", "jane@example.com", "2", "Second") +
                record(a, "", "Jane", "jane@example.com", "1", "Root")

        val commits = GitLogParser.parse(output)

        assertEquals(listOf(listOf(a, b), listOf(a), emptyList()), commits.map { it.parents })
        assertEquals(listOf(c, b, a), commits.map { it.hash })
    }

    @Test
    fun `takes the last record without a trailing NUL and ignores empty output`() {
        val output = record(a, "", "Jane", "jane@example.com", "1", "Root").removeSuffix("\u0000")

        assertEquals(listOf(a), GitLogParser.parse(output).map { it.hash })
        assertEquals(emptyList(), GitLogParser.parse(""))
    }

    @Test
    fun `skips malformed records`() {
        val output =
            record("not a hash", "", "Jane", "jane@example.com", "1", "Bad hash") +
                record(a, "", "Jane", "jane@example.com", "yesterday", "Bad time") +
                record(b, "", "Jane", "jane@example.com", "1") +
                record(c, "", "Jane", "jane@example.com", "1", "Good")

        assertEquals(listOf(c), GitLogParser.parse(output).map { it.hash })
    }

    @Test
    fun `keeps spaces, unicode and separators in the subject`() {
        val sha256 = "d".repeat(64)
        val output = record(sha256, "", "Zoë Ünal", "zoe@example.com", "1", "  Grüße 👋 an \u001f alle  ")

        val commit = GitLogParser.parse(output).single()

        assertEquals(sha256, commit.hash)
        assertEquals("Zoë Ünal", commit.author)
        assertEquals("  Grüße 👋 an \u001f alle  ", commit.subject)
    }
}
