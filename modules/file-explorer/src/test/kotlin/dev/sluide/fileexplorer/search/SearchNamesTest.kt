package dev.sluide.fileexplorer.search

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchNamesTest {
    @TempDir
    lateinit var root: Path

    private val list: (Path) -> List<Path> = { folder -> Files.list(folder).use { s -> s.sorted().toList() } }

    private fun search(
        text: String,
        roots: List<Path> = listOf(root),
        entries: (Path) -> List<Path> = list,
        limit: Int = 100,
        maxVisited: Int = 200_000,
        cancelled: () -> Boolean = { false },
    ) = searchNames(roots, NameQuery(text), entries, limit, maxVisited, cancelled)

    private fun names(result: SearchResult?) = assertNotNull(result).matches.map { root.relativize(it).toString() }

    private fun file(relative: String) = root.resolve(relative).also { it.parent.createDirectories() }.createFile()

    @Test
    fun `shallow matches come before deep ones`() {
        file("a/deep/target-deep.txt")
        file("b/target-mid.txt")
        file("target-top.txt")

        assertEquals(
            listOf("target-top.txt", "b/target-mid.txt", "a/deep/target-deep.txt"),
            names(search("target")),
        )
    }

    @Test
    fun `roots are searched in order and the root itself never matches`() {
        val other = root.resolve("other").createDirectories()
        val first = root.resolve("first-match").createDirectories()
        first.resolve("match-1.txt").createFile()
        other.resolve("match-2.txt").createFile()

        val result = assertNotNull(search("match", roots = listOf(first, other)))

        assertEquals(listOf("match-1.txt", "match-2.txt"), result.matches.map { it.fileName.toString() })
    }

    @Test
    fun `a path query is relative to the root the entry was found under`() {
        val first = root.resolve("first").createDirectories()
        val other = root.resolve("other").createDirectories()
        first
            .resolve("src")
            .createDirectories()
            .resolve("App.kt")
            .createFile()
        other
            .resolve("src")
            .createDirectories()
            .resolve("App.kt")
            .createFile()

        val result = assertNotNull(search("/src/App", roots = listOf(first, other)))

        assertEquals(listOf(first.resolve("src/App.kt"), other.resolve("src/App.kt")), result.matches)
        // Relative to the first root the second one's file is "../other/src/App.kt"; only that path contains this text.
        assertEquals(emptyList(), assertNotNull(search("other/src/App", roots = listOf(first, other))).matches)
    }

    @Test
    fun `a path query matches on the relative path`() {
        file("src/main/App.kt")
        file("src/test/App.kt")

        assertEquals(listOf("src/main/App.kt"), names(search("main/App")))
    }

    @Test
    fun `git entries are neither matched nor searched, like in the tree`() {
        file(".git/objects/needle.txt")
        file("sub/.git")
        file("src/needle.txt")

        assertEquals(listOf("src/needle.txt"), names(search("needle")))
        assertEquals(emptyList(), names(search(".git")))
    }

    @Test
    fun `a link to a parent folder is matched by name but not followed`() {
        file("sub/needle.txt")
        try {
            Files.createSymbolicLink(root.resolve("sub/needle-loop"), root)
        } catch (_: IOException) {
            assumeTrue(false, "cannot create symbolic links")
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "cannot create symbolic links")
        }

        assertEquals(listOf("sub/needle-loop", "sub/needle.txt"), names(search("needle")))
    }

    @Test
    fun `reaching the limit stops with exactly that many matches`() {
        repeat(5) { file("hit-$it.txt") }

        val result = assertNotNull(search("hit", limit = 3))

        assertEquals(3, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `a search below the limit is not truncated`() {
        repeat(2) { file("hit-$it.txt") }

        assertFalse(assertNotNull(search("hit", limit = 3)).truncated)
    }

    @Test
    fun `visiting too many entries truncates with what was found`() {
        file("a-hit.txt")
        repeat(5) { file("z-$it.txt") }

        val result = assertNotNull(search("hit", maxVisited = 3))

        assertEquals(1, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `no more entries are visited than the maximum, so a match after it is not collected`() {
        repeat(4) { file("hit-$it.txt") }

        val result = assertNotNull(search("hit", maxVisited = 3))

        assertEquals(listOf("hit-0.txt", "hit-1.txt", "hit-2.txt"), names(result))
        assertTrue(result.truncated)
    }

    @Test
    fun `cancelling after the first folder returns null`() {
        file("sub/needle.txt")
        var calls = 0

        val result = search("needle", cancelled = { ++calls > 1 })

        assertNull(result)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `an unreadable folder is skipped and the others are searched`(failure: Exception) {
        file("bad/needle-bad.txt")
        file("good/needle-good.txt")
        val failing: (Path) -> List<Path> = { folder ->
            if (folder.fileName.toString() == "bad") throw failure else list(folder)
        }

        assertEquals(listOf("good/needle-good.txt"), names(search("needle", entries = failing)))
    }

    @Test
    fun `a folder the entries function leaves out is not searched`() {
        file("hidden/needle-hidden.txt")
        file("shown/needle-shown.txt")
        val filtered: (Path) -> List<Path> = { folder -> list(folder).filter { it.fileName.toString() != "hidden" } }

        assertEquals(listOf("shown/needle-shown.txt"), names(search("needle", entries = filtered)))
    }

    companion object {
        @JvmStatic
        fun failures(): List<Exception> =
            listOf(
                IOException("denied"),
                UncheckedIOException(IOException("denied")),
                SecurityException("denied"),
            )
    }
}
