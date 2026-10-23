package dev.sluide.git.cli

import dev.sluide.git.GitIgnoreLineUse
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure parts of [ignoreRules], without git. */
class GitIgnoreRulesParsingTest {
    @Test
    fun `check-ignore records are split into source, line, pattern and path`() {
        val output = ".gitignore\u00001\u0000*.log\u0000a b.log\u0000sub/.gitignore\u00002\u0000!x/\u0000sub/x/\u0000"

        assertEquals(
            listOf(
                IgnoreMatch(".gitignore", 1, "*.log", "a b.log"),
                IgnoreMatch("sub/.gitignore", 2, "!x/", "sub/x/"),
            ),
            checkIgnoreMatches(output),
        )
        assertEquals(emptyList(), checkIgnoreMatches(""))
    }

    @Test
    fun `status candidates are the untracked and ignored entries`() {
        val output =
            "1 .M N... 100644 100644 100644 abc abc changed.txt\u0000" +
                "2 R. N... 100644 100644 100644 abc abc R100 new.txt\u0000? old name\u0000" +
                "u UU N... 100644 100644 100644 100644 abc abc abc conflicted.log\u0000" +
                "? new.txt\u0000! build/\u0000! a.log\u0000"

        assertEquals(listOf("new.txt", "build/", "a.log"), statusCandidates(output, overflowed = false))
    }

    @Test
    fun `a cut-off output keeps its complete records only`() {
        assertEquals(listOf("a.log"), statusCandidates("! a.log\u0000! b.l", overflowed = true))
        assertEquals(listOf("a.log", "b.log"), statusCandidates("! a.log\u0000! b.log\u0000", overflowed = true))
        assertEquals(listOf("a", "b"), nulRecords("a\u0000b\u0000", overflowed = false))
        assertEquals(listOf("a"), nulRecords("a\u0000b", overflowed = true))
    }

    @Test
    fun `paths go into chunks below the limit, in their order`() {
        val chunks = inputChunks(listOf("aaa", "bb", "c", "d".repeat(20), "ee"), maxBytes = 7)

        assertEquals(listOf("aaa\u0000bb\u0000", "c\u0000ee\u0000"), chunks.map { it.toString(Charsets.UTF_8) })
        assertEquals(listOf(3), inputChunks(listOf("ü"), maxBytes = 3).map { it.size }, "counted in UTF-8 bytes")
        assertEquals(emptyList(), inputChunks(emptyList(), maxBytes = 7))
    }

    @Test
    fun `uses count by line and kind, with ten examples relative to the folder`() {
        val untracked =
            (1..12).map { IgnoreMatch("sub/.gitignore", 1, "*.log", "sub/$it.log") } +
                IgnoreMatch("sub/.gitignore", 3, "!keep.log", "sub/keep.log")
        val tracked = listOf(IgnoreMatch("sub/.gitignore", 1, "*.log", "sub/t.log"))

        assertEquals(
            mapOf(
                1 to GitIgnoreLineUse(12, 0, 1, (1..10).map { "$it.log" }, listOf("t.log")),
                3 to GitIgnoreLineUse(0, 1, 0, listOf("keep.log"), emptyList()),
            ),
            lineUses(untracked, tracked, "sub/"),
        )
    }
}
