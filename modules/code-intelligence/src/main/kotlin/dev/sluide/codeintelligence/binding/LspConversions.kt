package dev.sluide.codeintelligence.binding

import dev.sluide.languageservers.LibraryDocument
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

/** A target of a definition-like answer: a place in a local file, or a document of the server to fetch first. */
sealed interface DefinitionTarget {
    /** A place in a local file. */
    data class Local(
        val position: SourcePosition,
    ) : DefinitionTarget

    /**
     * A place in a document the server names by a non-file URI, which [document] says how to fetch (ADR 0179); once
     * its text is in [LibraryDocument.file], the place is [line], [column] there.
     */
    data class Fetched(
        val document: LibraryDocument,
        val line: Int,
        val column: Int,
    ) : DefinitionTarget {
        /** The place once the document's text is written to its file. */
        val position: SourcePosition get() = SourcePosition(document.file, line, column)
    }
}

/** Where [this] starts, or `null` for a URI that is no local file. */
fun Location.toSourcePosition(): SourcePosition? = sourcePosition(uri, range?.start)

/** Where the target of [this] starts: its selection range (the name), else its range; `null` for no local file. */
fun LocationLink.toSourcePosition(): SourcePosition? =
    sourcePosition(targetUri, targetSelectionRange?.start ?: targetRange?.start)

/**
 * The targets of a definition-like answer (`textDocument/definition`, `declaration`, `typeDefinition`): locations or
 * links, in the server's order, without repeats. A target with a local file URI is a [DefinitionTarget.Local] (a link
 * starts at its selection range, the name, else its range); one with another URI is a [DefinitionTarget.Fetched] if
 * [libraryDocument] knows how to fetch it, else it is dropped. `null` is none.
 */
fun Either<out List<Location?>?, out List<LocationLink?>?>?.toDefinitionTargets(
    libraryDocument: (uri: String) -> LibraryDocument?,
): List<DefinitionTarget> {
    if (this == null) return emptyList()
    val targets =
        if (isLeft) {
            left.orEmpty().mapNotNull {
                it?.let { location ->
                    targetOf(location.uri, location.range?.start, libraryDocument)
                }
            }
        } else {
            right.orEmpty().mapNotNull { link ->
                link?.let {
                    targetOf(
                        it.targetUri,
                        it.targetSelectionRange?.start ?: it.targetRange?.start,
                        libraryDocument,
                    )
                }
            }
        }
    return targets.distinct()
}

private fun targetOf(
    uri: String?,
    start: Position?,
    libraryDocument: (String) -> LibraryDocument?,
): DefinitionTarget? {
    if (uri == null) return null
    sourcePosition(uri, start)?.let { return DefinitionTarget.Local(it) }
    // A file: URI that is no local file (a network share) is not a library document either.
    if (uri.startsWith("file:", ignoreCase = true)) return null
    val document = libraryDocument(uri) ?: return null
    return DefinitionTarget.Fetched(
        document,
        (start?.line ?: 0).coerceAtLeast(0),
        (start?.character ?: 0).coerceAtLeast(0),
    )
}

private fun sourcePosition(
    uri: String?,
    start: Position?,
): SourcePosition? {
    val file = uri?.let(LspUris::toPath) ?: return null
    return SourcePosition(file, (start?.line ?: 0).coerceAtLeast(0), (start?.character ?: 0).coerceAtLeast(0))
}
