package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class GitBlameIgnoreRevsHighlightingTest {
    private val revs =
        Highlighted(
            """
            |# Reformat with ktlint
            |3f2a9c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f
            |a1b2c3d # bump versions
            |not-a-hash
            |
            """.trimMargin(),
            RSyntaxHighlighter.blameIgnoreRevs,
        )

    @Test
    fun `commit hashes are numbers`() {
        assertEquals(setOf(TokenStyle.NUMBER), revs.stylesOf("3f2a9c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a3f"))
        assertEquals(setOf(TokenStyle.NUMBER), revs.stylesOf("a1b2c3d"))
        assertEquals(emptySet(), revs.stylesOf("not-a-hash"))
    }

    @Test
    fun `comments, also after a hash`() {
        assertEquals(setOf(TokenStyle.COMMENT), revs.stylesOf("# Reformat with ktlint"))
        assertEquals(setOf(TokenStyle.COMMENT), revs.stylesOf("# bump versions"))
    }

    @Test
    fun `spans are well-formed`() = revs.assertWellFormed()
}
