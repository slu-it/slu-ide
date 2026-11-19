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

    @Test
    fun `an output that ends right after a record, or after a commit without status, is read to its end`() {
        assertEquals(
            listOf(GitChangedFile(GitChange.ADDED, "f")),
            GitFileHistoryParser.parse(header(a, "One") + "\nA\u0000f", "f").map { it.file },
        )
        assertEquals(
            listOf(GitChangedFile(GitChange.MODIFIED, "f")),
            GitFileHistoryParser.parse(header(a, "One").removeSuffix("\u0000"), "f").map { it.file },
        )
        assertEquals(emptyList(), GitFileHistoryParser.parse("", "f"))
    }

    @Test
    fun `reading stops at a field that is no commit, whether it starts or follows entries`() {
        assertEquals(emptyList(), GitFileHistoryParser.parse("junk\u0000" + header(a, "One") + "\nM\u0000f\u0000", "f"))
        assertEquals(
            listOf("One"),
            GitFileHistoryParser
                .parse(header(a, "One") + "\nM\u0000f\u0000" + "junk\u0000" + header(b, "Two"), "f")
                .map { it.commit.subject },
        )
    }

    @Test
    fun `a copy has two paths like a rename, any other status one, and the path followed is the old one`() {
        val output =
            header(a, "Copy") + "\nC75\u0000src\u0000dst\u0000" +
                header(b, "Modify") + "\nM\u0000src\u0000" +
                header(c, "Rename?") + "\nT\u0000src\u0000"

        val files = GitFileHistoryParser.parse(output, "dst").map { it.file }

        assertEquals(
            listOf(
                GitChangedFile(GitChange.COPIED, "dst", "src"),
                GitChangedFile(GitChange.MODIFIED, "src"),
                GitChangedFile(GitChange.TYPE_CHANGED, "src"),
            ),
            files,
        )
    }

    @Test
    fun `a rename or copy cut off before its new path ends the reading, a modification needs only its path`() {
        assertEquals(emptyList(), GitFileHistoryParser.parse(header(a, "One") + "\nR100\u0000old", "f"))
        assertEquals(emptyList(), GitFileHistoryParser.parse(header(a, "One") + "\nC100\u0000old", "f"))
        assertEquals(emptyList(), GitFileHistoryParser.parse(header(a, "One") + "\nM", "f"))
        assertEquals(1, GitFileHistoryParser.parse(header(a, "One") + "\nM\u0000f", "f").size)
    }
}
