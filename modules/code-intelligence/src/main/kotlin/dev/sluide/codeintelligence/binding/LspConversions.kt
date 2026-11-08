package dev.sluide.codeintelligence.binding

import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path

/**
 * A place in a file, as `FileViewHost.openFile(file, line, column)` takes it: 0-based line and UTF-16 column, which
 * are LSP's line and character (ADR 0141).
 */
data class SourcePosition(
    val file: Path,
    val line: Int,
    val column: Int,
)

/** Where [this] starts, or `null` for a URI that is no local file. */
fun Location.toSourcePosition(): SourcePosition? = sourcePosition(uri, range?.start)

/** Where the target of [this] starts: its selection range (the name), else its range; `null` for no local file. */
fun LocationLink.toSourcePosition(): SourcePosition? =
    sourcePosition(targetUri, targetSelectionRange?.start ?: targetRange?.start)

/**
 * The targets of a definition-like answer (`textDocument/definition`, `declaration`, `typeDefinition`): locations or
 * links, in the server's order, without those outside local files and without repeats. `null` is none.
 */
fun Either<out List<Location?>?, out List<LocationLink?>?>?.toSourcePositions(): List<SourcePosition> {
    if (this == null) return emptyList()
    val positions =
        if (isLeft) {
            left.orEmpty().mapNotNull { it?.toSourcePosition() }
        } else {
            right.orEmpty().mapNotNull { it?.toSourcePosition() }
        }
    return positions.distinct()
}

private fun sourcePosition(
    uri: String?,
    start: Position?,
): SourcePosition? {
    val file = uri?.let(LspUris::toPath) ?: return null
    return SourcePosition(file, (start?.line ?: 0).coerceAtLeast(0), (start?.character ?: 0).coerceAtLeast(0))
}
