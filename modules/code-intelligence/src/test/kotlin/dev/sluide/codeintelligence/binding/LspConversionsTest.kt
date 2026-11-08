package dev.sluide.codeintelligence.binding

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

    private fun range(
        line: Int,
        character: Int,
    ) = Range(Position(line, character), Position(line, character + 3))

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
    fun `an answer lists its targets in order, without repeats and remote ones`() {
        val locations =
            Either.forLeft<List<Location>, List<LocationLink>>(
                listOf(
                    Location(uri, range(1, 0)),
                    Location("untitled:x", range(0, 0)),
                    Location(uri, range(1, 0)),
                    Location(uri, range(0, 2)),
                ),
            )
        assertEquals(listOf(SourcePosition(file, 1, 0), SourcePosition(file, 0, 2)), locations.toSourcePositions())
        val links =
            Either.forRight<List<Location>, List<LocationLink>>(listOf(LocationLink(uri, range(3, 0), range(3, 4))))
        assertEquals(listOf(SourcePosition(file, 3, 4)), links.toSourcePositions())
        assertEquals(emptyList(), (null as Either<List<Location>, List<LocationLink>>?).toSourcePositions())
    }
}
