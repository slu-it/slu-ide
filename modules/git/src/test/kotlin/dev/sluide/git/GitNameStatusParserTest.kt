package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals

class GitNameStatusParserTest {
    private fun output(vararg fields: String) = fields.joinToString("") { "$it\u0000" }

    @Test
    fun `reads every status, renames and copies with their old path`() {
        val output =
            output(
                "A",
                "added.txt",
                "M",
                "src/main/Modified.kt",
                "D",
                "deleted.txt",
                "R100",
                "old/name.txt",
                "new/name.txt",
                "C075",
                "source.txt",
                "copy.txt",
                "T",
                "link",
            )

        assertEquals(
            listOf(
                GitChangedFile(GitChange.ADDED, "added.txt"),
                GitChangedFile(GitChange.MODIFIED, "src/main/Modified.kt"),
                GitChangedFile(GitChange.DELETED, "deleted.txt"),
                GitChangedFile(GitChange.RENAMED, "new/name.txt", "old/name.txt"),
                GitChangedFile(GitChange.COPIED, "copy.txt", "source.txt"),
                GitChangedFile(GitChange.TYPE_CHANGED, "link"),
            ),
            GitNameStatusParser.parse(output),
        )
    }

    @Test
    fun `paths are taken as they are, with spaces, tabs, newlines and non-ASCII characters`() {
        val output = output("M", "with space/Größe.txt", "R090", "tab\there", "line\nbreak")

        assertEquals(
            listOf(
                GitChangedFile(GitChange.MODIFIED, "with space/Größe.txt"),
                GitChangedFile(GitChange.RENAMED, "line\nbreak", "tab\there"),
            ),
            GitNameStatusParser.parse(output),
        )
    }

    @Test
    fun `unmerged and unknown statuses count as modified`() {
        assertEquals(
            listOf(GitChangedFile(GitChange.MODIFIED, "a"), GitChangedFile(GitChange.MODIFIED, "b")),
            GitNameStatusParser.parse(output("U", "a", "X", "b")),
        )
    }

    @Test
    fun `nothing changed and incomplete records give no files`() {
        assertEquals(emptyList(), GitNameStatusParser.parse(""))
        assertEquals(emptyList(), GitNameStatusParser.parse("M\u0000"))
        assertEquals(
            listOf(GitChangedFile(GitChange.ADDED, "a")),
            GitNameStatusParser.parse(output("A", "a") + "R100\u0000old\u0000"),
        )
    }
}
