package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.completion.MatchKind
import dev.sluide.codeintelligence.completion.kindLook
import dev.sluide.codeintelligence.completion.matchKind
import dev.sluide.codeintelligence.completion.secondaryText
import dev.sluide.codeintelligence.ui.PopupCell
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.kordamp.ikonli.javafx.FontIcon

// The file structure's decisions (ADR 0149): the server's symbols as rows, filtering, the symbol at the caret.

/** The deepest nesting the file structure shows; deeper symbols are left out, so hostile answers cannot recurse far. */
internal const val MAX_SYMBOL_DEPTH = 32

/** One symbol of a file: [selection] is its name, [range] all of it; [depth] its nesting (0 at the top). */
internal data class SymbolRow(
    val name: String,
    val detail: String?,
    val kind: SymbolKind?,
    val depth: Int,
    val selection: Range,
    val range: Range,
)

/**
 * The rows of a `textDocument/documentSymbol` [result] in document order: hierarchical `DocumentSymbol`s depth first
 * with their nesting (at most [MAX_SYMBOL_DEPTH] deep), flat `SymbolInformation`s at depth 0 with their container as
 * detail. Symbols without a name or range are left out.
 */
internal fun symbolRows(result: List<Either<SymbolInformation, DocumentSymbol>?>?): List<SymbolRow> {
    val rows = mutableListOf<SymbolRow>()
    // Iterative, children in order: a stack of (symbol, depth), pushed in reverse.
    val stack = ArrayDeque<Pair<DocumentSymbol, Int>>()
    for (entry in result.orEmpty()) {
        if (entry == null) continue
        if (entry.isLeft) {
            val info = entry.left ?: continue
            val range = info.location?.range ?: continue
            val name = info.name ?: continue
            rows += SymbolRow(name, info.containerName, info.kind, 0, range, range)
            continue
        }
        stack.addLast((entry.right ?: continue) to 0)
        while (stack.isNotEmpty()) {
            val (symbol, depth) = stack.removeLast()
            val range = symbol.range ?: continue
            val name = symbol.name ?: continue
            rows += SymbolRow(name, symbol.detail, symbol.kind, depth, symbol.selectionRange ?: range, range)
            if (depth + 1 < MAX_SYMBOL_DEPTH) {
                for (child in symbol.children.orEmpty().asReversed()) {
                    if (child != null) stack.addLast(child to depth + 1)
                }
            }
        }
    }
    return rows
}

/** A row as the search popup shows it, with its [indent]. */
internal data class ShownSymbol(
    val row: SymbolRow,
    val indent: Int,
)

/**
 * The rows shown for [query]: all of them, indented by their nesting, when it is blank; else those whose name matches
 * it ([matchKind]: ignoring case, prefix matches first, then part starts, then subsequences; document order within
 * each), not indented.
 */
internal fun shownSymbols(
    rows: List<SymbolRow>,
    query: String,
): List<ShownSymbol> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return rows.map { ShownSymbol(it, it.depth) }
    return rows
        .mapNotNull { row -> matchKind(wanted, row.name)?.let { it to row } }
        .sortedBy { (kind: MatchKind, _) -> kind }
        .map { ShownSymbol(it.second, 0) }
}

/** The innermost symbol whose range holds [position] (the caret), `null` if none does. */
internal fun symbolAt(
    rows: List<SymbolRow>,
    position: Position,
): SymbolRow? =
    rows
        .filter { contains(it.range, position) }
        .maxWithOrNull(compareBy<SymbolRow>({ it.range.start.line }, { it.range.start.character }, { it.depth }))

private fun contains(
    range: Range,
    position: Position,
): Boolean = !before(position, range.start) && !before(range.end, position)

private fun before(
    a: Position,
    b: Position,
): Boolean = a.line < b.line || (a.line == b.line && a.character < b.character)

/** The kind of completion item whose look ([kindLook]) a symbol of [kind] takes. */
private val SYMBOL_LOOKS: Map<SymbolKind, CompletionItemKind> =
    mapOf(
        SymbolKind.File to CompletionItemKind.File,
        SymbolKind.Module to CompletionItemKind.Module,
        SymbolKind.Namespace to CompletionItemKind.Module,
        SymbolKind.Package to CompletionItemKind.Module,
        SymbolKind.Class to CompletionItemKind.Class,
        SymbolKind.Method to CompletionItemKind.Method,
        SymbolKind.Property to CompletionItemKind.Property,
        SymbolKind.Field to CompletionItemKind.Field,
        SymbolKind.Constructor to CompletionItemKind.Constructor,
        SymbolKind.Enum to CompletionItemKind.Enum,
        SymbolKind.Interface to CompletionItemKind.Interface,
        SymbolKind.Function to CompletionItemKind.Function,
        SymbolKind.Variable to CompletionItemKind.Variable,
        SymbolKind.Constant to CompletionItemKind.Constant,
        SymbolKind.String to CompletionItemKind.Value,
        SymbolKind.Number to CompletionItemKind.Value,
        SymbolKind.Boolean to CompletionItemKind.Value,
        SymbolKind.Array to CompletionItemKind.Value,
        SymbolKind.Object to CompletionItemKind.Value,
        SymbolKind.Key to CompletionItemKind.Property,
        SymbolKind.Null to CompletionItemKind.Value,
        SymbolKind.EnumMember to CompletionItemKind.EnumMember,
        SymbolKind.Struct to CompletionItemKind.Class,
        SymbolKind.Event to CompletionItemKind.Event,
        SymbolKind.Operator to CompletionItemKind.Operator,
        SymbolKind.TypeParameter to CompletionItemKind.TypeParameter,
    )

/** The icon of a symbol of [kind], as completion shows that kind; a new node per call, `null` without a kind. */
internal fun symbolIcon(kind: SymbolKind?): FontIcon? =
    kind?.let(SYMBOL_LOOKS::get)?.let(::kindLook)?.let { look ->
        FontIcon(look.icon).apply { styleClass += look.group.styleClass }
    }

/** How a file structure row shows: name, detail (one line, shortened) at the right, kind icon, indentation. */
internal fun symbolCell(shown: ShownSymbol): PopupCell =
    PopupCell(shown.row.name, secondaryText(shown.row.detail, null), symbolIcon(shown.row.kind), shown.indent)
