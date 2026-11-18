package dev.sluide.codeintelligence.binding

import dev.sluide.languageservers.LibraryDocument
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Locations and links of definition-like answers as places to open. */
class LspConversionsTest {
    private val file = Path.of("/p/a.py").toAbsolutePath()
    private val uri = file.toUri().toString()
    private val cached = Path.of("/cache/java.base/String.java").toAbsolutePath()

    private fun range(
        line: Int,
        character: Int,
    ) = Range(Position(line, character), Position(line, character + 3))

    /** A spec that fetches `jdt:` URIs into [cached]. */
    private fun library(uri: String): LibraryDocument? =
        if (uri.startsWith("jdt:")) LibraryDocument("java/classFileContents", mapOf("uri" to uri), cached) else null

    private fun local(
        line: Int,
        column: Int,
    ) = DefinitionTarget.Local(SourcePosition(file, line, column))

    @Test
    fun `a location opens at its start, a link at its selection range`() {
        assertEquals(SourcePosition(file, 2, 4), Location(uri, range(2, 4)).toSourcePosition())
        assertEquals(
            SourcePosition(file, 5, 8),
            LocationLink(uri, range(4, 0), range(5, 8)).toSourcePosition(),
        )
    }

    @Test
    fun `a location outside local files has no place`() {
        assertNull(Location("https://example.com/a.py", range(0, 0)).toSourcePosition())
        assertNull(Location("file://server/share/a.py", range(0, 0)).toSourcePosition())
    }

    @Test
    fun `an answer lists its targets in order, without repeats and with unknown remote ones dropped`() {
        val locations =
            Either.forLeft<List<Location>, List<LocationLink>>(
                listOf(
                    Location(uri, range(1, 0)),
                    Location("untitled:x", range(0, 0)),
                    Location(uri, range(1, 0)),
                    Location(uri, range(0, 2)),
                ),
            )
        assertEquals(listOf(local(1, 0), local(0, 2)), locations.toDefinitionTargets { null })
        val links =
            Either.forRight<List<Location>, List<LocationLink>>(listOf(LocationLink(uri, range(3, 0), range(3, 4))))
        assertEquals(listOf(local(3, 4)), links.toDefinitionTargets { null })
        assertEquals(emptyList(), (null as Either<List<Location>, List<LocationLink>>?).toDefinitionTargets { null })
    }

    @Test
    fun `a target the spec can fetch is kept with its start, in the server's order between local ones`() {
        val locations =
            Either.forLeft<List<Location>, List<LocationLink>>(
                listOf(
                    Location(uri, range(1, 0)),
                    Location("jdt://contents/java.base/String.class", range(30, 7)),
                    Location("untitled:x", range(0, 0)),
                ),
            )
        val links =
            Either.forRight<List<Location>, List<LocationLink>>(
                listOf(LocationLink("jdt://contents/b.class", range(4, 0), range(5, 8))),
            )

        assertEquals(
            listOf(
                local(1, 0),
                DefinitionTarget.Fetched(
                    LibraryDocument(
                        "java/classFileContents",
                        mapOf("uri" to "jdt://contents/java.base/String.class"),
                        cached,
                    ),
                    30,
                    7,
                ),
            ),
            locations.toDefinitionTargets(::library),
        )
        val fetched = links.toDefinitionTargets(::library).single() as DefinitionTarget.Fetched
        assertEquals(SourcePosition(cached, 5, 8), fetched.position)
    }

    @Test
    fun `a negative start of a fetched target is read as the start of the document`() {
        val locations =
            Either.forLeft<List<Location>, List<LocationLink>>(
                listOf(Location("jdt://contents/a.class", Range(Position(-3, -5), Position(0, 0)))),
            )

        val fetched = locations.toDefinitionTargets(::library).single() as DefinitionTarget.Fetched

        assertEquals(SourcePosition(cached, 0, 0), fetched.position)
    }

    @Test
    fun `a file URI is never handed to the spec`() {
        val asked = mutableListOf<String>()
        val locations =
            Either.forLeft<List<Location>, List<LocationLink>>(
                listOf(Location(uri, range(1, 0)), Location("file://server/share/a.py", range(1, 0))),
            )

        locations.toDefinitionTargets { fetched ->
            asked += fetched
            null
        }

        assertEquals(emptyList(), asked)
    }

    // LSP 3.17 requires uri and range of a Location and the ranges of a LocationLink, so a missing one is a lenient
    // server's answer; the code opens such a place at the start of the file instead of dropping it.
    private fun linkWith(
        uri: String,
        range: Range? = null,
        selection: Range? = null,
    ) = LocationLink().also {
        it.targetUri = uri
        if (range != null) it.targetRange = range
        if (selection != null) it.targetSelectionRange = selection
    }

    @Test
    fun `a link without a selection range starts at its range, one without either at the start of the file`() {
        assertEquals(SourcePosition(file, 4, 2), linkWith(uri, range(4, 2)).toSourcePosition())
        assertEquals(SourcePosition(file, 0, 0), linkWith(uri).toSourcePosition())
        assertEquals(SourcePosition(file, 6, 1), linkWith(uri, range(4, 0), range(6, 1)).toSourcePosition())
    }

    @Test
    fun `a selection range without a start falls back to the range`() {
        // Range() has no start: a lenient server's range object without one.
        val noStart = Range()
        val link = linkWith(uri, range(4, 2), noStart)

        assertEquals(SourcePosition(file, 4, 2), link.toSourcePosition())
        assertEquals(
            listOf(local(4, 2)),
            Either.forRight<List<Location>, List<LocationLink>>(listOf(link)).toDefinitionTargets { null },
        )
    }

    @Test
    fun `a location without a range or a URI`() {
        assertEquals(SourcePosition(file, 0, 0), Location().also { it.uri = uri }.toSourcePosition())
        assertNull(Location().also { it.range = range(1, 1) }.toSourcePosition())
        assertNull(LocationLink().toSourcePosition())
    }

    @Test
    fun `a negative position starts at 0`() {
        val negative = Range(Position(-2, -7), Position(0, 0))

        assertEquals(SourcePosition(file, 0, 0), Location(uri, negative).toSourcePosition())
        assertEquals(
            SourcePosition(file, 0, 3),
            Location(uri, Range(Position(-2, 3), Position(0, 0))).toSourcePosition(),
        )
        assertEquals(
            SourcePosition(file, 3, 0),
            Location(uri, Range(Position(3, -2), Position(4, 0))).toSourcePosition(),
        )
    }

    @Test
    fun `an answer with an empty side, null entries or entries without a URI or range`() {
        val noUri = Location().also { it.range = range(1, 1) }
        val noRange = Location().also { it.uri = uri }
        assertEquals(emptyList(), Either.forLeft<List<Location>, List<LocationLink>>(null).toDefinitionTargets { null })
        assertEquals(
            emptyList(),
            Either.forRight<List<Location>, List<LocationLink>>(null).toDefinitionTargets { null },
        )
        assertEquals(
            listOf(local(0, 0)),
            Either
                .forLeft<List<Location?>, List<LocationLink?>>(listOf(null, noUri, noRange))
                .toDefinitionTargets { null },
        )
        assertEquals(
            listOf(local(0, 0), local(4, 2), local(6, 1)),
            Either
                .forRight<List<Location?>, List<LocationLink?>>(
                    listOf(
                        null,
                        LocationLink().also { it.targetRange = range(1, 1) },
                        linkWith(uri),
                        linkWith(uri, range(4, 2)),
                        linkWith(uri, range(4, 0), range(6, 1)),
                    ),
                ).toDefinitionTargets { null },
        )
    }

    @Test
    fun `a fetched target without a range starts at the start of the document`() {
        val bare = Location().also { it.uri = "jdt://contents/a.class" }
        val link = linkWith("jdt://contents/b.class", range(4, 2))
        val onlyLink = linkWith("jdt://contents/c.class", range(4, 2), range(9, 5))

        val left = Either.forLeft<List<Location>, List<LocationLink>>(listOf(bare))
        val right = Either.forRight<List<Location>, List<LocationLink>>(listOf(link, onlyLink))

        assertEquals(
            SourcePosition(cached, 0, 0),
            (left.toDefinitionTargets(::library).single() as DefinitionTarget.Fetched).position,
        )
        assertEquals(
            listOf(SourcePosition(cached, 4, 2), SourcePosition(cached, 9, 5)),
            right.toDefinitionTargets(::library).map { (it as DefinitionTarget.Fetched).position },
        )
        assertEquals(
            SourcePosition(cached, 0, 3),
            (
                Either
                    .forLeft<List<Location>, List<LocationLink>>(
                        listOf(Location("jdt://contents/d.class", Range(Position(-1, 3), Position(0, 0)))),
                    ).toDefinitionTargets(::library)
                    .single() as DefinitionTarget.Fetched
            ).position,
        )
    }
}
