package dev.sluide.git.parse

import dev.sluide.git.GitRevisionResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRevisionParserTest {
    private fun commitOf(hash: String) = GitRevisionResolution.Commit(hash)

    @Test
    fun `parses found, missing and ambiguous lines and refuses cut-off output`() {
        val hash = "a".repeat(40)
        val output =
            "$hash commit 230\n$hash commit 230\n" +
                "abcd^{commit} missing\nabcd ambiguous\n" +
                // An unknown revision that looks like what git says: only the end of the line is git's.
                "$hash commit 1^{commit} missing\n$hash commit 1 missing\n" +
                "tree^{commit} missing\n${"b".repeat(40)} tree 33\n" +
                ":/Fix^{commit} missing\n$hash commit 230\n"

        assertEquals(
            listOf(
                commitOf(hash),
                GitRevisionResolution.Ambiguous,
                GitRevisionResolution.Missing,
                GitRevisionResolution.Missing,
                commitOf(hash),
            ),
            GitRevisionParser.parse(output, 5),
        )
        assertNull(GitRevisionParser.parse("$hash commit 230\n", 1), "cut off")
        assertNull(GitRevisionParser.parse("fatal: something\nelse\n", 1))
    }

    @Test
    fun `a line that is neither missing nor ambiguous is no answer, either one is`() {
        val none = "x".repeat(40)

        assertNull(GitRevisionParser.parse("$none garbled\n$none missing\n", 1))
        assertNull(GitRevisionParser.parse("$none\n$none missing\n", 1))
        assertEquals(
            listOf(GitRevisionResolution.Missing),
            GitRevisionParser.parse("$none missing\n$none missing\n", 1),
        )
        assertEquals(
            listOf(GitRevisionResolution.Missing),
            GitRevisionParser.parse("$none ambiguous\n$none missing\n", 1),
        )
        assertEquals(
            listOf(GitRevisionResolution.Ambiguous),
            GitRevisionParser.parse("$none missing\n$none ambiguous\n", 1),
        )
    }
}
