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
}
