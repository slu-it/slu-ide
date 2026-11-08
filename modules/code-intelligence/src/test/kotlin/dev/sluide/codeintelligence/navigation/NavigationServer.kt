package dev.sluide.codeintelligence.navigation

import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake server that syncs as ty does and answers definitions, references and highlights from what the test sets;
 * [supported] says which of the three it declares.
 */
internal class NavigationServer(
    supported: Set<String> = setOf(DEFINITION, REFERENCES, DOCUMENT_HIGHLIGHT),
) : FakeServer() {
    @Volatile
    var definitionAnswer: (DefinitionParams) -> List<Location> = { emptyList() }

    @Volatile
    var referencesAnswer: (ReferenceParams) -> List<Location> = { emptyList() }

    @Volatile
    var highlightAnswer: (DocumentHighlightParams) -> List<DocumentHighlight> = { emptyList() }

    val definitionRequests = CopyOnWriteArrayList<DefinitionParams>()
    val referenceRequests = CopyOnWriteArrayList<ReferenceParams>()
    val highlightRequests = CopyOnWriteArrayList<DocumentHighlightParams>()

    /** Reference answers held back while [holdReferences] is set. */
    val pendingReferences = CopyOnWriteArrayList<CompletableFuture<List<Location>>>()

    @Volatile
    var holdReferences = false

    init {
        capabilities =
            ServerCapabilities().apply {
                textDocumentSync =
                    Either.forRight(
                        TextDocumentSyncOptions().apply {
                            openClose = true
                            change = TextDocumentSyncKind.Incremental
                            save = Either.forRight(SaveOptions(false))
                        },
                    )
                if (DEFINITION in supported) definitionProvider = Either.forLeft(true)
                if (REFERENCES in supported) referencesProvider = Either.forLeft(true)
                if (DOCUMENT_HIGHLIGHT in supported) documentHighlightProvider = Either.forLeft(true)
            }
    }

    /** Definition answers held back while [holdDefinitions] is set. */
    val pendingDefinitions = CopyOnWriteArrayList<CompletableFuture<Either<List<Location>, List<LocationLink>>>>()

    @Volatile
    var holdDefinitions = false

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        definitionRequests += params
        if (holdDefinitions) {
            return CompletableFuture<Either<List<Location>, List<LocationLink>>>().also { pendingDefinitions += it }
        }
        return CompletableFuture.completedFuture(Either.forLeft(definitionAnswer(params)))
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        referenceRequests += params
        if (holdReferences) return CompletableFuture<List<Location>>().also { pendingReferences += it }
        return CompletableFuture.completedFuture(referencesAnswer(params))
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
        highlightRequests += params
        return CompletableFuture.completedFuture(highlightAnswer(params))
    }
}

/** A location in [file] at [line]:[column], [length] characters long. */
internal fun location(
    file: Path,
    line: Int,
    column: Int,
    length: Int = 1,
) = Location(LspUris.of(file), Range(Position(line, column), Position(line, column + length)))
