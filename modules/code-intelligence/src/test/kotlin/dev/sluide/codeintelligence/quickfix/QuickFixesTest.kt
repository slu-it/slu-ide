package dev.sluide.codeintelligence.quickfix

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.diagnostics.DIAGNOSTICS_LAYER
import dev.sluide.codeintelligence.diagnostics.DocumentDiagnostics
import dev.sluide.codeintelligence.diagnostics.LspTestServer
import dev.sluide.codeintelligence.edits.BindingEditTarget
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRegistrationOptions
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Quick fixes over a fake server: asked with the diagnostics at the caret where they are now, the edit applied as one
 * undo step, resolved first when the action has neither edit nor command, the command run after the edit and not when
 * the edit failed; refused after the text changed.
 */
class QuickFixesTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<LspTestServer>
    private var quickFixes: QuickFixes? = null
    private val told = CopyOnWriteArrayList<String>()

    @AfterEach
    fun close() = harness.close()

    private fun bind(
        text: String,
        configure: ServerCapabilities.() -> Unit = { setCodeActionProvider(true) },
    ) {
        harness =
            BoundEditorHarness(
                folder,
                text,
                {
                    LspTestServer {
                        diagnosticProvider = DiagnosticRegistrationOptions(false, false)
                        configure()
                    }
                },
                { binding ->
                    val diagnostics = DocumentDiagnostics(binding, 300.milliseconds, ManualDelays()::schedule) {}
                    val edits = WorkspaceEdits(BindingEditTarget(binding), io = Runnable::run, fx = Runnable::run)
                    val fixes = QuickFixes(binding, { diagnostics.all }, edits).apply { tell = { told += it } }
                    quickFixes = fixes
                    listOf(fixes, diagnostics)
                },
            )
    }

    private val uri get() = LspUris.of(harness.onFx { harness.binding.file })

    private fun insert(text: String) = TextEdit(Range(Position(0, 0), Position(0, 0)), text)

    /** Asks for the quick fixes at [offset] and waits for them. */
    private fun ask(offset: Int): List<QuickFix> {
        var fixes: List<QuickFix>? = null
        harness.onFx { quickFixes?.ask(offset, offset) { found, _ -> fixes = found } }
        awaitTrue("fixes") { fixes != null }
        return checkNotNull(fixes)
    }

    private fun choose(fix: QuickFix) = harness.onFx { quickFixes?.choose(fix) }

    @Test
    fun `a quick fix is asked with the diagnostics where they are now and its edit applied as one undo step`() {
        bind("p = Path()\n")
        val server = harness.server
        awaitTrue("pull") { server.pulls.size == 1 }
        val undefined =
            Diagnostic(Range(Position(0, 4), Position(0, 8)), "Path undefined").apply {
                severity =
                    DiagnosticSeverity.Error
            }
        server.pulls[0].second.complete(
            DocumentDiagnosticReport(RelatedFullDocumentDiagnosticReport(listOf(undefined))),
        )
        awaitTrue("shown") { harness.onFx { harness.editor.layers[DIAGNOSTICS_LAYER] }?.size == 1 }
        // Moves the diagnostic by two characters; the server's next answer is not awaited.
        harness.onFx { harness.editor.edit(0, 0, "  ") }
        server.codeActions =
            listOf(
                Either.forRight(
                    CodeAction("import pathlib.Path").apply {
                        isPreferred = true
                        edit =
                            WorkspaceEdit(
                                mapOf(
                                    uri to
                                        listOf(
                                            insert("from pathlib import Path\n"),
                                            TextEdit(Range(Position(0, 6), Position(0, 10)), "Path"),
                                        ),
                                ),
                            )
                    },
                ),
            )

        val fix = ask(10).single()
        val sent =
            server.codeActionRequests
                .single()
                .context.diagnostics
                .single()
        assertEquals("Path undefined", sent.message.left)
        assertEquals(Range(Position(0, 6), Position(0, 10)), sent.range, "the range in the text as it is now")
        choose(fix)

        val editor = harness.editor
        assertEquals(1, editor.appliedBatches.size, "one undo step")
        assertEquals(
            listOf(OffsetEdit(0, 0, "from pathlib import Path\n"), OffsetEdit(6, 10, "Path")),
            editor.appliedBatches[0],
        )
        assertEquals("from pathlib import Path\n  p = Path()\n", editor.currentText)
    }

    @Test
    fun `an action without edit and command is resolved first, then its edit applied and its command run`() {
        bind("x\n") { codeActionProvider = Either.forRight(CodeActionOptions().apply { resolveProvider = true }) }
        val server = harness.server
        server.codeActions = listOf(Either.forRight(CodeAction("bare")))
        val fileUri = uri
        server.resolve = { action ->
            CodeAction(action.title).apply {
                edit = WorkspaceEdit(mapOf(fileUri to listOf(insert("# "))))
                command = Command("Tidy", "x.tidy")
            }
        }

        choose(ask(0).single())

        awaitTrue("the command") { server.commands.isNotEmpty() }
        assertEquals("# x\n", harness.editor.currentText)
        val order =
            server.calls.filter {
                it == "resolveCodeAction" || it == "didChange" ||
                    it.startsWith("executeCommand")
            }
        assertEquals(listOf("resolveCodeAction", "didChange", "executeCommand x.tidy"), order, "resolve, edit, command")
    }

    @Test
    fun `the command of a fix whose edit is refused is not run, and a resolve without answer says so`() {
        bind("x\n") { codeActionProvider = Either.forRight(CodeActionOptions().apply { resolveProvider = true }) }
        val server = harness.server
        val stale =
            TextDocumentEdit(VersionedTextDocumentIdentifier(uri, 99), listOf(Either.forLeft(insert("# "))))
        server.codeActions =
            listOf(
                Either.forRight(
                    CodeAction("stale").apply {
                        edit = WorkspaceEdit(listOf(Either.forLeft(stale)))
                        command = Command("Tidy", "x.tidy")
                    },
                ),
            )
        choose(ask(0).single())
        assertEquals(listOf("Quick fix not applied: a.py changed since the server made this edit."), told.toList())

        server.codeActions = listOf(Either.forRight(CodeAction("unresolvable")))
        server.resolve = { null }
        choose(ask(0).single())
        awaitTrue("told") { told.size == 2 }
        assertEquals("The server did not resolve the quick fix.", told[1])
        // The requests after the refused fix were answered: a command sent then would have arrived before them.
        assertTrue(server.commands.isEmpty(), "no command after a refused edit")
        assertEquals("x\n", harness.editor.currentText)
    }

    @Test
    fun `a quick fix chosen after the text changed is refused`() {
        bind("p = Path()\n")
        harness.server.codeActions =
            listOf(
                Either.forRight(CodeAction("import").apply { edit = WorkspaceEdit(mapOf(uri to listOf(insert("x")))) }),
            )
        val fix = ask(0).single()
        harness.onFx { harness.editor.edit(0, 0, "#") }
        choose(fix)
        assertEquals(listOf("The text changed. Ask for the quick fixes again."), told.toList())
        assertEquals(emptyList(), harness.editor.appliedBatches)
    }
}
