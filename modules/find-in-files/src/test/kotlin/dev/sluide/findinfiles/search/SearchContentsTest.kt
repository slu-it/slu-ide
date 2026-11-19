package dev.sluide.findinfiles.search

import dev.sluide.textediting.SearchOptions
import dev.sluide.textediting.TextSearch
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchContentsTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val list: (Path) -> List<Path> = { folder -> Files.list(folder).use { s -> s.sorted().toList() } }

    private val found = mutableListOf<FileHits>()

    private fun search(
        query: String,
        options: SearchOptions = SearchOptions(),
        roots: List<Path> = listOf(root),
        mask: String = "",
        unsaved: Map<Path, String> = emptyMap(),
        limits: SearchLimits = SearchLimits(),
        scope: Path? = null,
        entries: (Path) -> List<Path> = list,
        cancelled: () -> Boolean = { false },
    ): SearchOutcome? =
        searchContents(SearchRequest(roots, query, options, mask, unsaved, limits, scope), entries, cancelled) {
            found +=
                it
        }

    private fun done(outcome: SearchOutcome?) = assertIs<SearchOutcome.Done>(outcome)

    private fun names() = found.map { it.root.relativize(it.file).joinToString("/") }

    private fun file(
        relative: String,
        text: String = "needle",
    ): Path = root.resolve(relative).also { it.parent.createDirectories() }.apply { writeText(text) }

    private fun link(
        relative: String,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(root.resolve(relative), target)
        } catch (_: IOException) {
            assumeTrue(false, "cannot create symbolic links")
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "cannot create symbolic links")
        }
    }

    @Test
    fun `files are searched depth-first in the listed order, roots in the given order`() {
        file("b.txt")
        file("a/y/z.txt")
        file("a/x.txt")
        file("c.txt", "no match")
        val second = root.resolve("second-root")
        file("second-root/s.txt")
        val firstOnly: (Path) -> List<Path> = { folder -> list(folder).filter { it != second } }

        val outcome = search("needle", roots = listOf(root, second), entries = firstOnly)

        assertEquals(listOf("a/x.txt", "a/y/z.txt", "b.txt", "s.txt"), names())
        assertEquals(listOf(root, root, root, second), found.map { it.root })
        assertEquals(SearchOutcome.Done(4, 4, 0, totalCapped = false, visitCapped = false), outcome)
    }

    @Test
    fun `the listing decides what is searched`() {
        file("generated/needle.txt")
        file("src/needle.txt")
        val hidingGenerated: (Path) -> List<Path> = { folder ->
            list(folder).filter { it.fileName.toString() != "generated" }
        }

        search("needle", entries = hidingGenerated)

        assertEquals(listOf("src/needle.txt"), names())
    }

    @Test
    fun `git folders are skipped, dotfiles are searched`() {
        file(".git/config")
        file(".env")

        search("needle")

        assertEquals(listOf(".env"), names())
    }

    @Test
    fun `a linked folder is not followed, a linked file is searched`() {
        outside.resolve("inside.txt").writeText("needle")
        link("folder-link", outside)
        link("file-link.txt", outside.resolve("inside.txt"))

        search("needle")

        assertEquals(listOf("file-link.txt"), names())
    }

    @Test
    fun `binary files are skipped without counting them`() {
        root.resolve("binary.bin").writeBytes(byteArrayOf(0, 1, 2) + "needle".toByteArray())

        val outcome = done(search("needle"))

        assertTrue(found.isEmpty())
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `a Windows-1252 file is decoded like in the editor`() {
        root.resolve("latin.txt").writeBytes("café needle".toByteArray(Charsets.ISO_8859_1))

        search("needle")

        assertEquals(
            "café needle",
            found
                .single()
                .lines
                .single()
                .snippet,
        )
    }

    @Test
    fun `CRLF line breaks give the editor's lines and columns`() {
        file("crlf.txt", "first\r\nsecond\r\n  the needle\r\n")

        search("needle")

        val hit = found.single().lines.single()
        assertEquals(2, hit.line)
        assertEquals(6, hit.column)
        assertEquals("the needle", hit.snippet)
    }

    @Test
    fun `files over the size limit are skipped and counted`() {
        file("big.txt", "needle" + "x".repeat(95))
        file("fits.txt", "needle" + "x".repeat(94))

        val outcome = done(search("needle", limits = SearchLimits(maxBytes = 100)))

        assertEquals(listOf("fits.txt"), names())
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `the unsaved buffer is searched instead of the file`() {
        val edited = file("edited.txt", "old needle")
        val stale = file("stale.txt", "needle on disk")
        val big = file("big.txt", "small")
        val unsaved =
            mapOf(
                edited.toAbsolutePath().normalize() to "first\nnew needle",
                stale.toAbsolutePath().normalize() to "nothing left",
                big.toAbsolutePath().normalize() to "needle" + "x".repeat(95),
            )

        val outcome = done(search("needle", unsaved = unsaved, limits = SearchLimits(maxBytes = 100)))

        assertEquals(listOf("edited.txt"), names())
        assertEquals(LineHit(1, 4, "new needle", listOf(4 until 10)), found.single().lines.single())
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `a file whose search runs out of time is skipped and counted`() {
        file("slow.txt", "a".repeat(40) + "c")
        file("fast.txt", "aab")

        val outcome = done(search("(a+)+\\1b", SearchOptions(regex = true)))

        assertEquals(listOf("fast.txt"), names())
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `the mask decides which files are read`() {
        file("Main.kt")
        file("MainTest.kt")
        file("notes.md")

        search("needle", mask = "*.kt, !*Test.kt")

        assertEquals(listOf("Main.kt"), names())
    }

    @Test
    fun `a scoped walk covers only the scope but keeps the root for masks and headers`() {
        file("docs/a.md")
        file("docs/adr/x.md")
        file("src/b.md")

        search("needle", scope = root.resolve("docs"), mask = "!docs/adr/*")

        assertEquals(listOf("docs/a.md"), names())
        assertEquals(root, found.single().root)
    }

    @Test
    fun `a scope under the second root reports that root`() {
        val other = root.resolve("other")
        file("other/docs/a.md")

        search("needle", roots = listOf(root.resolve("first"), other), scope = other.resolve("docs"))

        assertEquals(other, found.single().root)
    }

    @Test
    fun `a leading slash anchors a glob at the root, not at the scope`() {
        file("docs/a.md")
        file("docs/adr/x.md")

        search("needle", scope = root.resolve("docs"), mask = "/a.md")
        assertEquals(emptyList(), names())

        search("needle", scope = root.resolve("docs"), mask = "/docs/a.md")
        assertEquals(listOf("docs/a.md"), names())
    }

    @Test
    fun `a scope outside every root is walked as a root of its own`() {
        file("a.md")
        val elsewhere = outside.resolve("o.md").apply { writeText("needle") }

        search("needle", scope = outside)

        assertEquals(listOf(elsewhere), found.map { it.file })
        assertEquals(outside.toAbsolutePath().normalize(), found.single().root)
    }

    @Test
    fun `more matches than the per-file limit are cut and marked`() {
        file("many.txt", "needle\n".repeat(4))
        file("exact.txt", "needle\n".repeat(3))

        val outcome = done(search("needle", limits = SearchLimits(perFile = 3)))

        assertEquals(listOf("exact.txt" to false, "many.txt" to true), names().zip(found.map { it.capped }))
        assertEquals(listOf(3, 3), found.map { it.matchCount })
        assertEquals(listOf(0, 1, 2), found[1].lines.map { it.line })
        assertEquals(6, outcome.matches)
    }

    @Test
    fun `the total limit stops the walk and cuts the file that reaches it`() {
        file("a.txt", "needle needle needle")
        file("b.txt", "needle needle needle")
        file("c.txt", "needle")

        val outcome = done(search("needle", limits = SearchLimits(total = 4, perFile = 3)))

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertEquals(listOf(3 to false, 1 to true), found.map { it.matchCount to it.capped })
        assertEquals(SearchOutcome.Done(4, 2, 0, totalCapped = true, visitCapped = false), outcome)
    }

    @Test
    fun `visiting too many entries stops the walk with what was found`() {
        file("a.txt")
        file("b/c.txt")
        file("d.txt")

        val outcome = done(search("needle", limits = SearchLimits(maxVisited = 3)))

        // Visited: a.txt, b, b/c.txt; d.txt would be the fourth.
        assertEquals(listOf("a.txt", "b/c.txt"), names())
        assertTrue(outcome.visitCapped)
    }

    @Test
    fun `an invalid regex gives its message and searches nothing`() {
        file("a.txt", "(")
        val expected = assertNotNull(TextSearch.find("x", "(", SearchOptions(regex = true)).error)

        val outcome = search("(", SearchOptions(regex = true))

        assertEquals(SearchOutcome.Invalid(expected), outcome)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a match over several lines shows at its start line, highlighted to its end`() {
        file("multi.txt", "zero\n  begin here\nend there\n")

        search("here\\nend", SearchOptions(regex = true))

        assertEquals(listOf(LineHit(1, 8, "begin here", listOf(6 until 10))), found.single().lines)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("options")
    fun `the options are the find bar's`(
        options: SearchOptions,
        columns: List<Int>,
    ) {
        file("words.txt", "Needle needles needle")

        search("needle", options)

        assertEquals(
            columns,
            found
                .flatMap { it.lines }
                .single()
                .highlights
                .map { it.first },
        )
    }

    @Test
    fun `cancelling is asked for every 256th visited entry, not for each`() {
        repeat(600) { file("skipped-$it.txt") }
        var asked = 0

        val outcome = done(search("needle", mask = "*.none", cancelled = { asked++.let { false } }))

        assertEquals(0, outcome.files)
        assertEquals(2, asked, "at the 256th and the 512th entry")
    }

    @Test
    fun `fewer than 256 entries are visited without asking`() {
        repeat(255) { file("skipped-$it.txt") }
        var asked = 0

        done(search("needle", mask = "*.none", cancelled = { asked++.let { false } }))

        assertEquals(0, asked)
    }

    @Test
    fun `cancelling is noticed at a folder, and before a file is read`() {
        root.resolve("empty").createDirectories()
        assertNull(search("needle", cancelled = { true }))

        root.resolve("empty").toFile().delete()
        file("needle.txt")
        assertNull(search("needle", cancelled = { true }))
    }

    @Test
    fun `a root that cannot be listed has no files`() {
        file("needle.txt")

        val outcome = done(search("needle", entries = { throw IOException("denied") }))

        assertEquals(0, outcome.files)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `anything but a regular file is ignored, not read and not counted`() {
        val device = Path.of("/dev/zero")
        assumeTrue(Files.exists(device), "no /dev/zero here")
        val listing: (Path) -> List<Path> = { folder -> if (folder == root) listOf(device) else emptyList() }

        val outcome = done(search("needle", entries = listing, limits = SearchLimits(maxBytes = 100)))

        assertEquals(0, outcome.skipped, "an endless device is not read until it is too large")
        assertEquals(0, outcome.files)
    }

    @Test
    fun `an entry without a file name is no git folder`() {
        // Only a root path has no file name; a listing never returns one, but the walk must not depend on that.
        val top = Path.of("/").toAbsolutePath().root
        val listing: (Path) -> List<Path> = { folder -> if (folder == root) listOf(top) else emptyList() }

        val outcome = done(search("needle", entries = listing))

        assertEquals(0, outcome.files)
    }

    @Test
    fun `cancelling ends the walk with null`() {
        file("a/needle.txt")
        file("b/needle.txt")
        var calls = 0

        val outcome = search("needle", cancelled = { ++calls > 1 })

        assertNull(outcome)
        assertTrue(found.size < 2)
    }

    @Test
    fun `cancelling is noticed in a folder of files the mask leaves out`() {
        repeat(300) { file("skipped-$it.txt") }

        assertNull(search("needle", mask = "*.none", cancelled = { true }))
    }

    @Test
    fun `a file that cannot be read is unreadable`() {
        assertEquals(FileText.Unreadable, readText(root.resolve("missing.txt"), emptyMap(), 100))
    }

    @Test
    fun `a file that cannot be read is skipped silently and the walk goes on`() {
        val gone = file("a-gone.txt")
        file("b-found.txt")
        file("d-found.txt")
        val listing: (Path) -> List<Path> = { folder ->
            list(folder) + if (folder == root) listOf(root.resolve("c-listed-but-missing.txt")) else emptyList()
        }

        // The check right before a file is read deletes the first one, so its read fails.
        val outcome =
            done(search("needle", entries = listing, cancelled = { Files.deleteIfExists(gone).let { false } }))

        assertEquals(listOf("b-found.txt", "d-found.txt"), names())
        assertEquals(0, outcome.skipped)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    fun `a folder that cannot be listed is skipped`(failure: Exception) {
        file("bad/needle.txt")
        file("good/needle.txt")
        val failing: (Path) -> List<Path> = { folder ->
            if (folder.fileName.toString() == "bad") throw failure else list(folder)
        }

        search("needle", entries = failing)

        assertEquals(listOf("good/needle.txt"), names())
    }

    @Test
    fun `matches on one line become one hit with every match highlighted`() {
        val text = "first\n\t  a needle and a needle\nlast needle"
        val matches = TextSearch.find(text, "needle", SearchOptions()).matches

        val hits = lineHits(text, matches)

        assertEquals(
            listOf(
                LineHit(1, 5, "a needle and a needle", listOf(2 until 8, 15 until 21)),
                LineHit(2, 5, "last needle", listOf(5 until 11)),
            ),
            hits,
        )
    }

    @Test
    fun `a long line is cut around its first match`() {
        val before = "b".repeat(300)
        val after = "a".repeat(300)
        val text = "    $before needle$after"

        val hit = lineHits(text, TextSearch.find(text, "needle", SearchOptions()).matches).single()

        val firstAt = 4 + 300 + 1
        assertEquals(firstAt, hit.column)
        assertEquals("…" + before.takeLast(SNIPPET_CONTEXT - 1) + " needle" + "a".repeat(154) + "…", hit.snippet)
        assertEquals(listOf(SNIPPET_CONTEXT + 1 until SNIPPET_CONTEXT + 7), hit.highlights)
        assertEquals("needle", hit.snippet.substring(hit.highlights.single()))
    }

    @Test
    fun `a match near the end of a long line keeps the end of the line, highlights are clipped`() {
        val text = "x".repeat(400) + "needle needle"
        val matches = TextSearch.find(text, "needle", SearchOptions()).matches

        val hit = lineHits(text, matches).single()

        assertEquals("…" + text.takeLast(SNIPPET_LENGTH), hit.snippet)
        assertEquals(listOf("needle", "needle"), hit.highlights.map { hit.snippet.substring(it) })
    }

    @Test
    fun `a match running past the cut is highlighted up to the cut`() {
        val text = "y".repeat(10) + "z".repeat(300)
        val matches = TextSearch.find(text, "y+z+", SearchOptions(regex = true)).matches

        val hit = lineHits(text, matches).single()

        assertEquals(text.take(SNIPPET_LENGTH) + "…", hit.snippet)
        assertEquals(listOf(0 until SNIPPET_LENGTH), hit.highlights)
    }

    @Test
    fun `a match in the indentation keeps the indentation from it on`() {
        val text = "    x = 1"
        val matches = TextSearch.find(text, "\\s+x", SearchOptions(regex = true)).matches

        assertEquals(listOf(LineHit(0, 0, "    x = 1", listOf(0 until 5))), lineHits(text, matches))
    }

    companion object {
        @JvmStatic
        fun failures(): Stream<Exception> =
            Stream.of(IOException("denied"), UncheckedIOException(IOException("denied")), SecurityException("denied"))

        @JvmStatic
        fun options(): Stream<Arguments> =
            Stream.of(
                Arguments.of(SearchOptions(), listOf(0, 7, 15)),
                Arguments.of(SearchOptions(matchCase = true), listOf(7, 15)),
                Arguments.of(SearchOptions(wholeWord = true), listOf(0, 15)),
                Arguments.of(SearchOptions(matchCase = true, wholeWord = true), listOf(15)),
            )
    }
}
