package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.TestTimers
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

/**
 * The setup the symbol flow tests share: a bound editor of `a.py` over a [SymbolServer], the search popups as fake
 * views, and the helpers to type, wait and fail.
 */
internal abstract class SymbolFlowBase {
    @TempDir
    lateinit var folder: Path

    internal val spec = PySpec()

    internal lateinit var fx: FxServers

    internal lateinit var server: SymbolServer

    internal lateinit var intelligence: CodeIntelligence

    internal lateinit var editor: FakeCodeEditor

    internal val structureView = FakeSearchView<ShownSymbol>()

    internal val symbolView = FakeSearchView<WorkspaceSymbolRow>()

    internal val notes = CopyOnWriteArrayList<Pair<Int, String>>()

    internal val opened = CopyOnWriteArrayList<SourcePosition>()

    internal val timers = TestTimers()

    internal var focused = true

    internal lateinit var symbols: GoToSymbol

    internal val structures = CopyOnWriteArrayList<FileStructure>()

    internal val text = "class Console:\n    def run(self):\n        pass\n\ndef main():\n    pass\n"

    internal val file get() = folder.resolve("a.py")

    internal val other get() = folder.resolve("pkg/b.py")

    internal val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openFile(
                file: Path,
                line: Int,
                column: Int,
            ): Boolean {
                opened += SourcePosition(file, line, column)
                return true
            }

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = folder
        }

    @AfterEach
    fun close() {
        fx.onFx {
            symbols.close()
            intelligence.close()
        }
        fx.close()
    }

    /** A bound editor of `a.py` with the caret at [caret] over a server with [newServer]'s symbols. */
    internal fun setUp(
        caret: Int,
        bind: Boolean = true,
        newServer: () -> SymbolServer = { SymbolServer() },
    ) {
        fx = FxServers(folder, spec) { newServer() }
        server = fx.fake.awaitRunning(spec) as SymbolServer
        val structure =
            BindingFeature { binding ->
                val shown =
                    FileStructure(binding, navigation(), structureView) { offset, note ->
                        notes +=
                            offset to note
                    }
                structures += shown
                val registered = navigation().registerStructure(binding, shown::show)
                AutoCloseable {
                    registered.close()
                    shown.close()
                }
            }
        intelligence =
            fx.onFx {
                CodeIntelligence(fx.servers, FXCollections.observableSet()) { listOf(structure) }.apply {
                    navigation.isFocused = { focused }
                }
            }
        symbols =
            fx.onFx {
                GoToSymbol(
                    intelligence.navigation,
                    { intelligence.attached.mapNotNull { it.binding } },
                    symbolView,
                    200.milliseconds,
                    timers::schedule,
                )
            }
        file.writeText(text)
        editor = FakeCodeEditor(file, text, host).apply { this.caret = caret }
        if (bind) fx.onFx { intelligence.extend(editor) }
    }

    internal fun navigation() = intelligence.navigation

    internal fun range(
        line: Int,
        start: Int,
        end: Int = start,
    ) = Range(Position(line, start), Position(line, end))

    internal fun symbol(
        name: String,
        from: Int,
        to: Int,
        nameColumn: Int,
        vararg children: DocumentSymbol,
    ) = DocumentSymbol(
        name,
        SymbolKind.Function,
        Range(Position(from, 0), Position(to, 0)),
        range(from, nameColumn, nameColumn + name.length),
        null,
        children.toList(),
    )

    internal val tree =
        listOf(symbol("Console", 0, 3, 6, symbol("run", 1, 3, 8)), symbol("main", 4, 6, 4))

    internal fun search(query: String) {
        fx.onFx { symbolView.type(query) }
        fx.onFx { timers.runDue() }
    }

    internal fun failure(message: String) =
        ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

    internal fun fileOnly(name: String = "main") =
        WorkspaceSymbol(name, SymbolKind.Function, Either.forRight(WorkspaceSymbolLocation(LspUris.of(other))))

    internal fun answerDocumentSymbols() = tree.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

    internal fun located(
        name: String,
        path: Path,
        line: Int,
        kind: SymbolKind = SymbolKind.Class,
    ) = WorkspaceSymbol(
        name,
        kind,
        Either.forLeft(Location(LspUris.of(path), range(line, 4, 4 + name.length))),
    )

    /** A file structure on the binding of the editor of [of] that the binding does not close, over its own [view]. */
    internal fun leakedStructure(
        view: FakeSearchView<ShownSymbol>,
        of: Path = file,
    ): FileStructure =
        fx.onFx {
            FileStructure(
                intelligence.attached.mapNotNull { it.binding }.single { it.file == of },
                navigation(),
                view,
            ) { _, _ -> }
        }

    /** Waits until a go to symbol search, sent after everything before, has its answer: the answers came in order. */
    internal fun awaitSentinel() {
        server.workspaceSymbols = { listOf(located("sentinel", file, 0)) }
        fx.onFx { symbols.open() }
        search("sentinel")
        awaitTrue("the sentinel") { fx.onFx { symbolView.items.isNotEmpty() } }
    }
}
