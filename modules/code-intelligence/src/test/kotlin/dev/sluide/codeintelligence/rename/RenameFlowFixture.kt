package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitServerHandled
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.symbols.SymbolServer
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText

/**
 * The setup the rename flow tests share: a bound editor of `a.py` over a fake server that prepares and renames, with a
 * fake view for the popups and dialogs and a fake preview of the files.
 */
internal abstract class RenameFlowFixture {
    @TempDir
    lateinit var folder: Path

    internal val spec = PySpec()
    internal lateinit var fx: FxServers
    internal lateinit var server: SymbolServer
    internal lateinit var intelligence: CodeIntelligence
    internal lateinit var editor: FakeCodeEditor
    internal val view = FakeRenameView()

    /** How the preview finds the files; the files asked about. */
    internal var fileStates: Map<Path, RenameFileState> = emptyMap()
    internal val statesAsked = CopyOnWriteArrayList<List<Path>>()

    /** Runs while the preview looks at the files, before it answers. */
    internal var statesHook: () -> Unit = {}

    /** Runs on the test thread before the applier's work is handed back to the FX thread. */
    internal var fxHook: () -> Unit = {}

    internal val text = "def run():\n    return run\n\nrun()\n"
    internal val file get() = folder.resolve("a.py")
    internal val other get() = folder.resolve("b.py")

    internal val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = folder
        }

    @AfterEach
    fun close() {
        fx.onFx { intelligence.close() }
        fx.close()
    }

    /** A bound editor of `a.py` with the caret at [caret], over a server that [prepares] (or not) and [renames]. */
    internal fun setUp(
        caret: Int,
        prepares: Boolean = true,
        renames: Boolean = true,
        docText: String = text,
        newEditor: (Path, String, FileViewHost) -> FakeCodeEditor = ::FakeCodeEditor,
    ) {
        fx = FxServers(folder, spec) { SymbolServer(prepares = prepares, renames = renames) }
        server = fx.fake.awaitRunning(spec) as SymbolServer
        val feature =
            RenameFeature(
                libraryReadOnly = { false },
                view = { view },
                edits = { target ->
                    WorkspaceEdits(target, io = { Thread(it).start() }, fx = { done ->
                        fxHook()
                        fx.onFx { done.run() }
                    })
                },
                states = {
                    RenameFileStates { files, done ->
                        statesAsked += files
                        statesHook()
                        done(fileStates)
                    }
                },
            )
        intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { listOf(feature) } }
        file.writeText(docText)
        editor = newEditor(file, docText, host).apply { this.caret = caret }
        fx.onFx { intelligence.extend(editor) }
    }

    /** Another bound editor of the window: `b.py` with [content]. */
    internal fun bindOther(content: String): FakeCodeEditor {
        other.writeText(content)
        return FakeCodeEditor(other, content, host).also { fx.onFx { intelligence.extend(it) } }
    }

    /** The context menu's Rename… at the caret's place, which does what Shift+F6 does at the caret. */
    internal fun startRename() {
        fx.onFx { renameItem().onAction.handle(null) }
    }

    internal fun renameItem() =
        editor.positionOf(editor.caret).let { editor.menuItems(EditorMenuTarget(it.line, it.column)).single() }

    internal fun range(
        line: Int,
        start: Int,
        end: Int,
    ) = Range(Position(line, start), Position(line, end))

    /** The rename of `run` to [name] in `a.py`: its three places. */
    internal fun ownEdits(name: String) =
        listOf(TextEdit(range(0, 4, 7), name), TextEdit(range(1, 11, 14), name), TextEdit(range(3, 0, 3), name))

    internal fun edit(vararg files: Pair<Path, List<TextEdit>>) =
        WorkspaceEdit(files.associate { (path, edits) -> LspUris.of(path) to edits })

    internal fun failure(message: String) =
        ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

    /** Closes the code intelligence of the editor, as closing its tab does; on the FX thread. */
    internal fun closeBinding() = intelligence.attached.single().close()

    /** Waits until every answer the server sent before reached the FX thread: one more request, answered in order. */
    internal fun serverIdle() = awaitServerHandled(fx.servers.session(spec)) { fx.onFx(it) }
}

/** A [RenameView] that records what it is told; the test answers the name and the preview. */
internal class FakeRenameView : RenameView {
    val notes = mutableListOf<Pair<Int, String>>()
    var asked: Pair<IntRange, String>? = null
    var check: ((String) -> String?)? = null
    private var onName: (String) -> Unit = {}

    /** How often a name was asked for. */
    var askCount = 0
        private set

    /** Runs while the preview shows, before the user answers it. */
    var onConfirm: () -> Unit = {}
    val previews = mutableListOf<RenamePreview>()
    var confirmAnswer = true
    val told = mutableListOf<String>()

    override fun note(
        offset: Int,
        text: String,
    ) {
        notes += offset to text
    }

    override fun askName(
        range: IntRange,
        name: String,
        check: (String) -> String?,
        onName: (String) -> Unit,
    ) {
        askCount++
        asked = range to name
        this.check = check
        this.onName = onName
    }

    /** The user typed [typed] and pressed Enter. */
    fun answer(typed: String) = onName(typed)

    override fun confirm(preview: RenamePreview): Boolean {
        previews += preview
        onConfirm()
        return confirmAnswer
    }

    override fun tell(message: String) {
        told += message
    }

    var closed = false
        private set

    override fun close() {
        closed = true
    }
}

/** An editor that refuses every batch of edits, as a locked one does. */
internal class RefusingEditor(
    file: Path,
    text: String,
    host: FileViewHost,
) : FakeCodeEditor(file, text, host) {
    override fun applyEdits(edits: List<OffsetEdit>) = false
}
