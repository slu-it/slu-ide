package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.symbols.SymbolServer
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rename in a bound editor over a fake server, with the popups and dialogs as a fake view: where the name is (prepare
 * range, placeholder, default, refusal, the word at the caret), names that do nothing, an edit of the file alone
 * applied at once with the caret in the new name, other files shown in a preview first, refusals, names the language
 * does not allow, and a text changed meanwhile: while the server prepares or answers, the preview looks at the files or
 * shows, in this file or another bound one.
 */
class RenameFlowTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var server: SymbolServer
    private lateinit var intelligence: CodeIntelligence
    private lateinit var editor: FakeCodeEditor
    private val view = FakeRenameView()

    /** How the preview finds the files; the files asked about. */
    private var fileStates: Map<Path, RenameFileState> = emptyMap()
    private val statesAsked = CopyOnWriteArrayList<List<Path>>()

    /** Runs while the preview looks at the files, before it answers. */
    private var statesHook: () -> Unit = {}

    private val text = "def run():\n    return run\n\nrun()\n"
    private val file get() = folder.resolve("a.py")
    private val other get() = folder.resolve("b.py")

    private val host =
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
    private fun setUp(
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
                    WorkspaceEdits(target, io = { Thread(it).start() }, fx = { fx.onFx { it.run() } })
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
    private fun bindOther(content: String): FakeCodeEditor {
        other.writeText(content)
        return FakeCodeEditor(other, content, host).also { fx.onFx { intelligence.extend(it) } }
    }

    /** The context menu's Rename… at the caret's place, which does what Shift+F6 does at the caret. */
    private fun startRename() {
        fx.onFx { renameItem().onAction.handle(null) }
    }

    private fun renameItem() =
        editor.positionOf(editor.caret).let { editor.menuItems(EditorMenuTarget(it.line, it.column)).single() }

    private fun range(
        line: Int,
        start: Int,
        end: Int,
    ) = Range(Position(line, start), Position(line, end))

    /** The rename of `run` to [name] in `a.py`: its three places. */
    private fun ownEdits(name: String) =
        listOf(TextEdit(range(0, 4, 7), name), TextEdit(range(1, 11, 14), name), TextEdit(range(3, 0, 3), name))

    private fun edit(vararg files: Pair<Path, List<TextEdit>>) =
        WorkspaceEdit(files.associate { (path, edits) -> LspUris.of(path) to edits })

    @Test
    fun `a prepared range and placeholder are offered, and a rename of the file alone applies at once in one step`() {
        setUp(caret = 22)
        server.prepareAnswer = { Either3.forSecond(PrepareRenameResult(range(1, 11, 14), "run")) }
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        awaitTrue("asked for the name") { fx.onFx { view.asked != null } }
        assertEquals(Position(1, 11), server.prepareRequests.single().position)
        assertEquals((22 until 25) to "run", fx.onFx { view.asked })

        fx.onFx { view.answer("start") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def start():\n    return start\n\nstart()\n" }
        assertEquals("start", server.renameRequests.single().newName)
        assertEquals(1, fx.onFx { editor.appliedEdits.size }, "one undo step")
        assertEquals(24, fx.onFx { editor.caret }, "the caret in the new name, as far in as before")
        assertEquals(emptyList(), statesAsked, "no preview for the file alone")
    }

    @Test
    fun `a prepared range without a placeholder offers its text, and the default behaviour the word at the caret`() {
        setUp(caret = 26)
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 14)) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        assertEquals((22 until 25) to "run", fx.onFx { view.asked })

        fx.onFx { view.asked = null }
        server.prepareAnswer = { Either3.forThird(PrepareRenameDefaultBehavior(true)) }
        fx.onFx { editor.caret = 2 }
        startRename()
        awaitTrue("asked again") { fx.onFx { view.asked != null } }
        assertEquals((0 until 3) to "def", fx.onFx { view.asked })
    }

    @Test
    fun `a server that cannot rename there says so at the caret and asks for no name`() {
        setUp(caret = 22)
        startRename()
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Cannot rename this"), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `without prepareRename the word at the caret is offered, also when the caret is right after it`() {
        setUp(caret = 25, prepares = false)
        startRename()
        assertEquals((22 until 25) to "run", fx.onFx { view.asked })
        assertEquals(emptyList(), server.prepareRequests)
    }

    @Test
    fun `an empty or unchanged name asks the server nothing`() {
        setUp(caret = 22, prepares = false)
        for (typed in listOf("", "   ", "run", " run ")) {
            startRename()
            fx.onFx { view.answer(typed) }
        }
        fx.idle()
        assertEquals(emptyList(), server.renameRequests)
    }

    @Test
    fun `a server without rename gets no request, the note and the menu item say why`() {
        setUp(caret = 22, renames = false)
        val item = fx.onFx { renameItem() }
        assertEquals("Rename… (not supported)", item.text)
        assertTrue(item.isDisable)
        startRename()
        assertEquals(listOf(22 to "Rename is not available (not supported)"), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
        assertEquals(emptyList(), server.renameRequests)
    }

    @Test
    fun `a text changed while the server answers the rename drops its edit`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = {
            fx.onFx { editor.edit(0, 0, "# x\n") }
            edit(file to ownEdits(it.newName))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals("# x\n$text", fx.onFx { editor.text }, "no edit at the shifted places")
    }

    @Test
    fun `a text changed while the server prepares drops its answer and says so`() {
        setUp(caret = 22)
        server.prepareAnswer = {
            fx.onFx { editor.edit(0, 0, "# x\n") }
            Either3.forFirst(range(3, 0, 3))
        }
        startRename()
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked }, "no name asked for at the old place")
    }

    @Test
    fun `a text changed while the preview looks at the files drops the rename`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        statesHook = { editor.edit(0, 0, "# x\n") }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals(emptyList(), fx.onFx { view.previews }, "no preview")
        assertEquals("run()\n", other.readText())
    }

    @Test
    fun `another bound file changed while the server answers drops the rename`() {
        setUp(caret = 22, prepares = false)
        val second = bindOther("from a import run\n")
        server.renameAnswer = {
            fx.onFx { second.edit(0, 0, "# y\n") }
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals(emptyList(), fx.onFx { view.previews })
        assertEquals("# y\nfrom a import run\n", fx.onFx { second.text })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `another bound file changed while the preview shows drops the rename`() {
        setUp(caret = 22, prepares = false)
        val second = bindOther("from a import run\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.OPEN)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        view.onConfirm = { second.edit(0, 0, "# y\n") }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(1, fx.onFx { view.previews.size })
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals("# y\nfrom a import run\n", fx.onFx { second.text })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a name the language does not allow is refused while typed, an allowed one is taken`() {
        setUp(caret = 22, prepares = false)
        startRename()
        val check = fx.onFx { checkNotNull(view.check) }
        assertEquals("Not a valid name: foo bar", check("foo bar"))
        assertEquals("Not a valid name: foo bar", check(" foo bar "))
        assertNull(check("start"))
        assertNull(check("run"), "unchanged: closes and does nothing")
        assertNull(check(""), "empty: closes and does nothing")
    }

    @Test
    fun `a text changed while the name is typed drops the rename`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        fx.onFx { editor.edit(0, 0, "# x\n") }
        fx.onFx { view.answer("start") }
        fx.idle()
        assertEquals(emptyList(), server.renameRequests)
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
    }

    @Test
    fun `other files are shown in a preview first, and apply only when confirmed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("from a import run\nrun()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer = {
            edit(
                file to ownEdits(it.newName),
                other to listOf(TextEdit(range(0, 14, 17), it.newName), TextEdit(range(1, 0, 3), it.newName)),
            )
        }
        view.confirmAnswer = false
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("preview") { fx.onFx { view.previews.isNotEmpty() } }
        val preview = fx.onFx { view.previews.single() }
        assertEquals("Rename “run” to “start”?", preview.header)
        assertEquals("5 changes in 2 files", preview.summary)
        assertEquals(listOf(PreviewRow("a.py", 3, null), PreviewRow("b.py", 2, "written to disk")), preview.rows)
        assertEquals(text, fx.onFx { editor.text }, "cancelled: nothing changes")

        view.confirmAnswer = true
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("written") { other.readText() == "from a import start\nstart()\n" }
        awaitTrue("renamed in the editor") { fx.onFx { editor.text }.startsWith("def start():") }
        assertEquals(listOf(listOf(file, other), listOf(file, other)), statesAsked)
    }

    @Test
    fun `a preview that names a refusal applies nothing even when confirmed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.OUTSIDE)
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        view.confirmAnswer = true
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("preview") { fx.onFx { view.previews.isNotEmpty() } }
        assertEquals(
            "The rename cannot be applied: b.py is outside the project.",
            fx.onFx { view.previews.single().refusal },
        )
        fx.idle()
        assertEquals(text, fx.onFx { editor.text })
        assertEquals("run()\n", other.readText())
    }

    @Test
    fun `an edit the applier refuses is told and changes nothing`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = {
            WorkspaceEdit(listOf(Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile(LspUris.of(other)))))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(
            listOf("Rename not applied: Creating, renaming and deleting files is not supported yet."),
            fx.onFx { view.told },
        )
        assertEquals(text, fx.onFx { editor.text })
    }

    private fun failure(message: String) =
        ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

    /** Closes the code intelligence of the editor, as closing its tab does; on the FX thread. */
    private fun closeBinding() = intelligence.attached.single().close()

    /** Waits until every answer the server sent before reached the FX thread: one more request, answered in order. */
    private fun serverIdle() {
        val answered = CountDownLatch(1)
        val params = HoverParams(TextDocumentIdentifier(LspUris.of(file)), Position(0, 0))
        fx.onFx {
            fx.servers.session(spec).request(
                { it.textDocumentService.hover(params) },
                { answered.countDown() },
                { answered.countDown() },
            )
        }
        assertTrue(answered.await(5, TimeUnit.SECONDS), "the server answered")
        fx.idle()
    }

    @Test
    fun `a prepareRename the server fails says why at the name, unless the text changed`() {
        setUp(caret = 22)
        server.prepareAnswer = { throw failure("no index") }
        startRename()
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Cannot rename this: no index"), fx.onFx { view.notes })

        server.prepareAnswer = { throw failure("") }
        startRename()
        awaitTrue("second note") { fx.onFx { view.notes.size == 2 } }
        assertEquals(22 to "Cannot rename this", fx.onFx { view.notes[1] })

        server.prepareAnswer = {
            fx.onFx { editor.edit(0, 0, "# x\n") }
            throw failure("late")
        }
        fx.onFx { editor.caret = 26 }
        startRename()
        awaitTrue("third note") { fx.onFx { view.notes.size == 3 } }
        assertEquals(26 to "The text changed. Rename again.", fx.onFx { view.notes[2] })
    }

    @Test
    fun `a prepareRename failing after the editor closed shows nothing`() {
        setUp(caret = 22)
        server.prepareAnswer = {
            fx.onFx { closeBinding() }
            throw failure("no index")
        }
        startRename()
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.notes })
    }

    @Test
    fun `a prepareRename answered after the editor closed asks for no name`() {
        setUp(caret = 22)
        server.prepareAnswer = {
            fx.onFx { closeBinding() }
            Either3.forFirst(range(1, 11, 14))
        }
        startRename()
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `a rename the server fails is told, one it answers nothing or no file to says nothing to rename`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = { throw failure("no index") }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(listOf("Rename failed: no index"), fx.onFx { view.told })

        server.renameAnswer = { null }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Nothing to rename"), fx.onFx { view.notes })

        server.renameAnswer = { WorkspaceEdit(emptyMap()) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("second note") { fx.onFx { view.notes.size == 2 } }
        assertEquals(22 to "Nothing to rename", fx.onFx { view.notes[1] })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a rename answered after the editor closed changes and shows nothing`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = {
            fx.onFx { closeBinding() }
            edit(file to ownEdits(it.newName))
        }
        startRename()
        fx.onFx { view.answer("start") }
        serverIdle()
        assertEquals(text, fx.onFx { editor.text })
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertEquals(emptyList(), fx.onFx { view.told })
    }

    @Test
    fun `an editor closed while the preview looks at the files drops the rename`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        statesHook = { closeBinding() }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("states asked") { statesAsked.isNotEmpty() }
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.previews }, "no preview of a closed editor")
        assertEquals("run()\n", other.readText())
    }

    @Test
    fun `an editor closed while the preview shows applies nothing, though it was confirmed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        view.onConfirm = { closeBinding() }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("previewed") { fx.onFx { view.previews.isNotEmpty() } }
        serverIdle()
        assertEquals("run()\n", other.readText())
        assertEquals(text, fx.onFx { editor.text })
        assertEquals(emptyList(), fx.onFx { view.notes })
    }

    @Test
    fun `a file bound while the server answered drops the rename like one that changed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("from a import run\n")
        server.renameAnswer = {
            bindOther("from a import run\n")
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a new rename drops the answer of the one on its way`() {
        setUp(caret = 22, prepares = false)
        val release = CountDownLatch(1)
        server.renameAnswer = {
            check(release.await(5, TimeUnit.SECONDS)) { "not released" }
            edit(file to ownEdits(it.newName))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("on its way") { server.renameRequests.size == 1 }
        startRename()
        release.countDown()
        serverIdle()
        assertEquals(text, fx.onFx { editor.text }, "the first one's edit is not applied")
        assertEquals(2, fx.onFx { view.askCount })
    }

    @Test
    fun `a rename started after the editor closed does nothing`() {
        setUp(caret = 22)
        val item = fx.onFx { renameItem() }
        fx.onFx { closeBinding() }
        fx.onFx { item.onAction.handle(null) }
        serverIdle()
        assertEquals(emptyList(), server.prepareRequests)
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `the name is asked at its start from its end, at the caret inside it, between names at the caret`() {
        setUp(caret = 25)
        server.prepareAnswer = { Either3.forThird(PrepareRenameDefaultBehavior(true)) }
        startRename()
        awaitTrue("end") { server.prepareRequests.size == 1 }
        assertEquals(Position(1, 11), server.prepareRequests[0].position)

        fx.onFx { editor.caret = 24 }
        startRename()
        awaitTrue("inside") { server.prepareRequests.size == 2 }
        assertEquals(Position(1, 13), server.prepareRequests[1].position)

        fx.onFx { editor.caret = 26 }
        startRename()
        awaitTrue("between") { server.prepareRequests.size == 3 }
        assertEquals(Position(2, 0), server.prepareRequests[2].position)
        awaitTrue("no name") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(26 to "Cannot rename this"), fx.onFx { view.notes })
    }

    @Test
    fun `a placeholder is offered when it differs, an empty one is not, and an empty range is refused`() {
        setUp(caret = 22)
        server.prepareAnswer = { Either3.forSecond(PrepareRenameResult(range(1, 11, 14), "runner")) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        assertEquals((22 until 25) to "runner", fx.onFx { view.asked })

        fx.onFx { view.asked = null }
        server.prepareAnswer = { Either3.forSecond(PrepareRenameResult(range(1, 11, 14), "")) }
        startRename()
        awaitTrue("asked again") { fx.onFx { view.asked != null } }
        assertEquals((22 until 25) to "run", fx.onFx { view.asked })

        fx.onFx { view.asked = null }
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 11)) }
        startRename()
        awaitTrue("refused") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Cannot rename this"), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `a name that ends the document is renamed`() {
        setUp(caret = 7, prepares = false, docText = "x = run")
        startRename()
        assertEquals((4 until 7) to "run", fx.onFx { view.asked })
    }

    @Test
    fun `the rename is asked at the name's place, the caret keeps its place in the new name`() {
        setUp(caret = 24, prepares = false)
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def start():\n    return start\n\nstart()\n" }
        assertEquals(Position(1, 13), server.renameRequests.single().position)
        assertEquals(listOf(26), fx.onFx { editor.caretMoves }, "two characters into the new name, moved once")
    }

    @Test
    fun `a caret outside the renamed name goes to its start or end`() {
        setUp(caret = 28)
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 14)) }
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        fx.onFx { view.answer("start") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def start():\n    return start\n\nstart()\n" }
        assertEquals(listOf(27), fx.onFx { editor.caretMoves }, "after the name's start, as far in as its length")
    }

    @Test
    fun `a caret before the renamed name goes to its start`() {
        setUp(caret = 0)
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 14)) }
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        fx.onFx { view.answer("go") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def go():\n    return go\n\ngo()\n" }
        assertEquals(listOf(21), fx.onFx { editor.caretMoves })
    }

    @Test
    fun `other files only are renamed without moving the caret`() {
        setUp(caret = 22, prepares = false)
        other.writeText("from a import run\n")
        fileStates = mapOf(other to RenameFileState.CLOSED)
        server.renameAnswer = { edit(other to listOf(TextEdit(range(0, 14, 17), it.newName))) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("written") { other.readText() == "from a import start\n" }
        assertEquals(emptyList(), fx.onFx { editor.caretMoves })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `with several files the caret moves once, for this editor only`() {
        setUp(caret = 22, prepares = false)
        val second = bindOther("from a import run\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.OPEN)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("go") }
        awaitTrue("both renamed") { fx.onFx { second.text } == "from a import go\n" }
        awaitTrue("own renamed") { fx.onFx { editor.text }.startsWith("def go") }
        assertEquals(listOf(22 - 1), fx.onFx { editor.caretMoves }, "3 characters became 2 before the name")
        assertEquals(emptyList(), fx.onFx { second.caretMoves })
    }

    @Test
    fun `an editor that refuses its edits keeps its caret and the failure is told`() {
        setUp(caret = 22, prepares = false, newEditor = ::RefusingEditor)
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(emptyList(), fx.onFx { editor.caretMoves })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a file the preview did not find refuses the whole rename, nothing is applied`() {
        setUp(caret = 22, prepares = false)
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(listOf("Rename not applied: b.py: does not exist."), fx.onFx { view.told })
        assertEquals(text, fx.onFx { editor.text })
    }
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

    override fun close() {}
}

/** An editor that refuses every batch of edits, as a locked one does. */
internal class RefusingEditor(
    file: Path,
    text: String,
    host: FileViewHost,
) : FakeCodeEditor(file, text, host) {
    override fun applyEdits(edits: List<OffsetEdit>) = false
}
