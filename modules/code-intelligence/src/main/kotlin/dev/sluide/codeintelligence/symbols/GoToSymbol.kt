package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.navigation.Navigation
import dev.sluide.codeintelligence.navigation.failure
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.codeintelligence.navigation.unavailable
import dev.sluide.codeintelligence.ui.SearchView
import dev.sluide.codeintelligence.ui.fxDelay
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.WorkspaceSymbolParams
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val WORKSPACE_SYMBOL = "workspace/symbol"
internal const val RESOLVE_WORKSPACE_SYMBOL = "workspaceSymbol/resolve"

/**
 * The bound editor whose server go to symbol asks: the [focused] one's if its server answers `workspace/symbol`, else
 * the first of [bindings] whose server does; `null` if none. A binding whose document the server does not serve is
 * skipped ([navigationBlocker]).
 */
internal fun symbolSource(
    focused: DocumentBinding?,
    bindings: List<DocumentBinding>,
): DocumentBinding? =
    (listOfNotNull(focused) + bindings).firstOrNull { !it.isClosed && navigationBlocker(it, WORKSPACE_SYMBOL) == null }

/**
 * [rows] without repeats: a server that holds a folder twice (ty with a project folder nested in the window folder,
 * ADR 0151) sends each symbol twice; the first of rows equal in file, position, name, container and kind stays (the
 * container tells apart the `run` of two classes sent with their file only).
 */
internal fun distinctSymbols(rows: List<WorkspaceSymbolRow>): List<WorkspaceSymbolRow> =
    rows.distinctBy { listOf(it.file, it.position, it.name, it.container, it.kind) }

/**
 * Go to symbol of one window (ADR 0149): the search popup asks a running server of the window ([symbolSource]) for the
 * symbols matching the typed query (`workspace/symbol`, [SymbolSearch]: debounced, latest wins), ranked by
 * [rankWorkspaceSymbols]. Taking one opens it, from the focused bound editor's caret if there was one when the popup
 * opened (recorded in Back and Forward); a symbol sent with its file only is resolved first if the server offers it,
 * else opened at its first line. Without a server to ask the popup shows a note. FX thread.
 */
internal class GoToSymbol(
    private val navigation: Navigation,
    private val bindings: () -> List<DocumentBinding>,
    private val view: SearchView<WorkspaceSymbolRow>,
    delay: Duration = 200.milliseconds,
    schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : AutoCloseable {
    private val slot = RequestSlot()

    /** The resolve of the symbol taken: dropped when the popup opens again or the window closes. */
    private val resolving = RequestSlot()
    private var source: DocumentBinding? = null
    private var from: DocumentBinding? = null
    private val search =
        SymbolSearch(
            ::ask,
            { rows, placeholder ->
                view.placeholder = placeholder
                view.setItems(rows, rows.firstOrNull())
            },
            delay,
            schedule,
        )

    init {
        view.onQuery = search::query
        view.onChoose = ::choose
        view.onHidden = search::stop
    }

    /** Why go to symbol cannot ask a server now, `null` if it can. */
    fun blocker(): String? {
        if (symbolSource(navigation.focused(), bindings()) != null) return null
        return navigation.focused()?.let { navigationBlocker(it, WORKSPACE_SYMBOL) } ?: NO_SERVER
    }

    /** Opens the popup, or shows why it cannot search; `false` then, or if it has no place to show. */
    fun open(): Boolean {
        resolving.cancel()
        val focused = navigation.focused()
        val found = symbolSource(focused, bindings())
        if (found == null) {
            view.note(unavailable(ACTION, blocker() ?: NO_SERVER))
            return false
        }
        if (view.isShowing && source == found) return true
        source = found
        from = focused
        if (!view.open()) return false
        search.query(view.query)
        return true
    }

    private fun ask(
        query: String,
        done: (List<WorkspaceSymbolRow>, String?) -> Unit,
    ): AutoCloseable {
        val session = source?.session ?: return AutoCloseable {}.also { done(emptyList(), NO_SERVER) }
        slot.send(
            session,
            { it.workspaceService.symbol(WorkspaceSymbolParams(query)) },
            { result -> done(distinctSymbols(workspaceSymbolRows(result)), null) },
            { error -> done(emptyList(), failure(ACTION, error)) },
        )
        return AutoCloseable { slot.cancel() }
    }

    private fun choose(row: WorkspaceSymbolRow) {
        row.position?.let { return go(SourcePosition(row.file, it.line, it.character)) }
        val session = source?.takeUnless { it.isClosed }?.session
        val symbol = row.symbol
        val fileStart = SourcePosition(row.file, 0, 0)
        val canResolve = session?.features?.supports(RESOLVE_WORKSPACE_SYMBOL) == true
        if (session == null || symbol == null || !canResolve) return go(fileStart)
        resolving.send(
            session,
            { it.workspaceService.resolveWorkspaceSymbol(symbol) },
            { resolved ->
                val location = resolved?.location?.takeIf { it.isLeft }?.left
                val file = location?.uri?.let(LspUris::toPath)
                val start = location?.range?.start
                go(if (file != null && start != null) SourcePosition(file, start.line, start.character) else fileStart)
            },
            { go(fileStart) },
        )
    }

    private fun go(target: SourcePosition) = navigation.open(from?.takeUnless { it.isClosed }, target)

    override fun close() {
        search.close()
        slot.cancel()
        resolving.cancel()
        view.close()
    }

    private companion object {
        const val ACTION = "Go to Symbol"
        const val NO_SERVER = "no language server running"
    }
}
