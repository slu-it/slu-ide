package dev.sluide.git.parse

import dev.sluide.git.GitHunk
import kotlin.test.Test
import kotlin.test.assertEquals

class GitDiffParserTest {
    @Test
    fun `reads changed, added and removed blocks as 0-based line ranges`() {
        val output =
            """
            diff --git a/Main.kt b/Main.kt
            index 3b18e51..8c7e5a6 100644
            --- a/Main.kt
            +++ b/Main.kt
            @@ -2 +2 @@ fun main() {
            -    println("a")
            +    println("b")
            @@ -5,0 +6,2 @@ fun other() {
            +    one()
            +    two()
            @@ -9,3 +11,0 @@
            -x
            -y
            -z
            @@ -20,2 +18,4 @@
            -@@ -1 +1 @@ is just content here
            -b
            +c
            +d
            +e
            +f
            \ No newline at end of file
            """.trimIndent()

        assertEquals(
            listOf(
                GitHunk(1..1, 1..1),
                GitHunk(5 until 5, 5..6),
                GitHunk(8..10, 11 until 11),
                GitHunk(19..20, 17..20),
            ),
            GitDiffParser.parse(output),
        )
    }

    @Test
    fun `a block at the very start has the line before it as 0`() {
        assertEquals(
            listOf(GitHunk(0 until 0, 0..0), GitHunk(0..1, 0 until 0)),
            GitDiffParser.parse("@@ -0,0 +1 @@\n+new\n@@ -1,2 +0,0 @@\n-a\n-b\n"),
        )
    }

    @Test
    fun `no differences give no hunks`() {
        assertEquals(emptyList(), GitDiffParser.parse(""))
    }
}
