package dev.sluide.git

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // Measured with git 2.56.1: `git remote add` takes exactly these (`-x` too, after --end-of-options).
    @ParameterizedTest
    @ValueSource(strings = ["origin", "HEAD", "@", "a.", "\u00e9", "-x", "a/b", "up.stream", "a.b/c.d"])
    fun `takes the remote names git takes`(name: String) {
        assertTrue(GitRefNames.isValidRemoteName(name), name)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "a b", "a..b", "foo.lock", "x/", ".a", "a~b", "a@{b", "a*b", "a:b", "a\\b", "a?b", "a[b", "a^b", "a//b",
            "a/.b", "a/b.lock",
        ],
    )
    fun `refuses the remote names git refuses`(name: String) {
        assertFalse(GitRefNames.isValidRemoteName(name), name)
    }

    @Test
    fun `a remote name conflicts with a remote that is a path prefix of it or has it as one`() {
        assertEquals("a", GitRefNames.remoteNameConflict("a/b", listOf("x", "a")))
        assertEquals("a/b", GitRefNames.remoteNameConflict("a", listOf("x", "a/b")))
        assertEquals("a/b", GitRefNames.remoteNameConflict("a/b/c", listOf("a/b")))
    }

    @Test
    fun `a remote name that only shares characters, or is the same, does not conflict`() {
        assertNull(GitRefNames.remoteNameConflict("ab", listOf("a")))
        assertNull(GitRefNames.remoteNameConflict("a", listOf("ab", "b/a")))
        assertNull(GitRefNames.remoteNameConflict("a", listOf("a")))
        assertNull(GitRefNames.remoteNameConflict("a", emptyList()))
    }

    @Test
    fun `the remote being renamed does not conflict with its new name`() {
        assertEquals("a", GitRefNames.remoteNameConflict("a/b", listOf("a")))
        assertNull(GitRefNames.remoteNameConflict("a/b", listOf("a"), except = "a"))
        assertEquals("a/c", GitRefNames.remoteNameConflict("a", listOf("a/b", "a/c"), except = "a/b"))
    }
}
