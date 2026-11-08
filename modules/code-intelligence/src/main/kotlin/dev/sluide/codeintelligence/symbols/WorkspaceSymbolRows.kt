package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.navigation.shownPath
import dev.sluide.codeintelligence.ui.PopupCell
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Go to symbol's decisions (ADR 0149): the server's workspace symbols as rows, their order, the request scheduling.

/** The most rows go to symbol shows. */
internal const val MAX_WORKSPACE_SYMBOLS = 200

/**
 * One workspace symbol: its name, container, kind and file; [position] is where its name starts, `null` when the server
 * sent only the file ([symbol] can then be resolved).
 */
internal data class WorkspaceSymbolRow(
    val name: String,
    val container: String?,
    val kind: SymbolKind?,
    val file: Path,
    val position: Position?,
    val symbol: WorkspaceSymbol? = null,
)

/** The rows of a `workspace/symbol` [result] in the server's order; symbols in no local file are left out. */
internal fun workspaceSymbolRows(
    result: Either<out List<SymbolInformation?>?, out List<WorkspaceSymbol?>?>?,
): List<WorkspaceSymbolRow> {
    if (result == null) return emptyList()
    if (result.isLeft) {
        return result.left.orEmpty().filterNotNull().mapNotNull { info ->
            val location = info.location ?: return@mapNotNull null
            val file = location.uri?.let(LspUris::toPath) ?: return@mapNotNull null
            WorkspaceSymbolRow(
                info.name ?: return@mapNotNull null,
                info.containerName,
                info.kind,
                file,
                location.range?.start,
            )
        }
    }
    return result.right.orEmpty().filterNotNull().mapNotNull { symbol ->
        val location = symbol.location ?: return@mapNotNull null
        val uri = if (location.isLeft) location.left?.uri else location.right?.uri
        val file = uri?.let(LspUris::toPath) ?: return@mapNotNull null
        val position = if (location.isLeft) location.left?.range?.start else null
        WorkspaceSymbolRow(
            symbol.name ?: return@mapNotNull null,
            symbol.containerName,
            symbol.kind,
            file,
            position,
            symbol,
        )
    }
}

/**
 * [rows] for [query] in the order shown: names equal to it first (ignoring case), then those starting with it, then the
 * rest, each in the server's order (ty's answers are fuzzy and unranked); at most [cap].
 */
internal fun rankWorkspaceSymbols(
    rows: List<WorkspaceSymbolRow>,
    query: String,
    cap: Int = MAX_WORKSPACE_SYMBOLS,
): List<WorkspaceSymbolRow> {
    val wanted = query.trim()
    return rows
        .sortedBy { row ->
            when {
                row.name.equals(wanted, ignoreCase = true) -> 0
                row.name.startsWith(wanted, ignoreCase = true) -> 1
                else -> 2
            }
        }.take(cap)
}

/** How a go to symbol row shows: the name, its container and path (relative to [projectFolder]) at the right, icon. */
internal fun workspaceSymbolCell(
    row: WorkspaceSymbolRow,
    projectFolder: Path?,
): PopupCell {
    val where = listOfNotNull(row.container?.takeIf { it.isNotBlank() }, shownPath(row.file, projectFolder))
    return PopupCell(row.name, where.joinToString(" · "), symbolIcon(row.kind))
}

/**
 * The requests of go to symbol: each change of the query asks the server ([ask]) [delay] after the last one, and only
 * the latest question's answer is shown ([show]: rows ranked for it, and the placeholder for none). A blank query asks
 * nothing. FX thread.
 *
 * @param ask asks for the symbols of a query; the callback gets rows or a failure message; closing drops the answer.
 */
internal class SymbolSearch(
    private val ask: (String, (List<WorkspaceSymbolRow>, String?) -> Unit) -> AutoCloseable,
    private val show: (List<WorkspaceSymbolRow>, String) -> Unit,
    private val delay: Duration = 200.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : AutoCloseable {
    private var pending: AutoCloseable? = null
    private var request: AutoCloseable? = null
    private var generation = 0
    private var shown: List<WorkspaceSymbolRow> = emptyList()

    /** The query is now [text]. */
    fun query(text: String) {
        stop()
        val asked = generation
        val query = text.trim()
        if (query.isEmpty()) {
            shown = emptyList()
            show(shown, TYPE)
            return
        }
        // The rows of the last answer stay while the next one travels; "Searching…" shows only without any.
        show(shown, SEARCHING)
        pending =
            schedule(delay) {
                pending = null
                request =
                    ask(query) { rows, failure ->
                        if (asked == generation) {
                            request = null
                            shown = rankWorkspaceSymbols(rows, query)
                            show(shown, failure ?: NONE)
                        }
                    }
            }
    }

    /** Drops the question waiting and the one on its way: an answer arriving anyway is not shown. */
    fun stop() {
        generation++
        pending?.close()
        request?.close()
        pending = null
        request = null
    }

    override fun close() = stop()

    companion object {
        const val TYPE = "Type a symbol name"
        const val SEARCHING = "Searching…"
        const val NONE = "No symbols found"
    }
}
