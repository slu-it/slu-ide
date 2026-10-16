package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRefNamesTest {
    @Test
    fun `takes ordinary branch names, with folders and non-ASCII characters`() {
        listOf(
            "main",
            "feature/a-1",
            "team/fix/x_y",
            "v1.0",
            "a./b",
            "a.lock.b",
            "@x",
            "x@y",
            "Gr\u00fc\u00dfe",
            "HEADS",
        ).forEach { assertTrue(GitRefNames.isValidBranchName(it), it) }
    }

    @Test
    fun `refuses what git check-ref-format refuses`() {
        listOf(
            "",
            "@",
            "a b",
            "a\tb",
            "a\u007fb",
            "a\u0001b",
            "a~1",
            "a^",
            "a:b",
            "a?",
            "a*",
            "a[b",
            "a\\b",
            "a..b",
            "a@{1}",
            ".hidden",
            "a/.b",
            "a.lock",
            "a/b.lock/c",
            "/a",
            "a/",
            "a//b",
            "a.",
        ).forEach {
            assertFalse(GitRefNames.isValidBranchName(it), it)
            assertFalse(GitRefNames.isValidTagName(it), it)
        }
    }

    @Test
    fun `branch names must not start with a dash or be HEAD, tag names may`() {
        assertFalse(GitRefNames.isValidBranchName("-x"))
        assertFalse(GitRefNames.isValidBranchName("HEAD"))
        assertTrue(GitRefNames.isValidTagName("-x"))
        assertTrue(GitRefNames.isValidTagName("HEAD"))
    }
}
