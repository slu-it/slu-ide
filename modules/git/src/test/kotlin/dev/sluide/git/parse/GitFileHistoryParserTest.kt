package dev.sluide.git.parse

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class GitFileHistoryParserTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun header(
        hash: String,
        subject: String,
    ) = "\u001e$hash\u001f\u001fA\u001fa@example.com\u001f0\u001f$subject\u0000"

    @Test
    fun `reads renames, a commit without status and separators in subjects and paths, fields in their order`() {
        val output =
            header(a, "Rename \u001e") + "\nR100\u0000old\u001e\u0000new\u0000" +
                header(b, "No status") +
                header(c, "Add \u001e\n") + "\nA\u0000old\u001e\u0000"

        val entries = GitFileHistoryParser.parse(output, "new")

        assertEquals(
            listOf(
                GitChangedFile(GitChange.RENAMED, "new", "old\u001e"),
                GitChangedFile(GitChange.MODIFIED, "old\u001e"),
                GitChangedFile(GitChange.ADDED, "old\u001e"),
            ),
            entries.map { it.file },
        )
        assertEquals(listOf("Rename \u001e", "No status", "Add \u001e\n"), entries.map { it.commit.subject })
    }

    @Test
    fun `stops at an incomplete record`() {
        val output = header(a, "One") + "\nM\u0000f\u0000" + header(b, "Two") + "\nR100\u0000old\u0000"

        assertEquals(listOf("One"), GitFileHistoryParser.parse(output, "f").map { it.commit.subject })
    }
}
