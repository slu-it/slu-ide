package dev.sluide.codeintelligence.diagnostics

import com.google.gson.JsonObject
import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.fileviewers.StyledRange
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRegistrationOptions
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.RelatedUnchangedDocumentDiagnosticReport
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The diagnostics of a bound editor over a fake server: pulls after open, after the debounce of changes, on refresh,
 * after a move and after a restart, dropping answers for an older text and asking again when the server cancelled;
 * pushed sets for an older version dropped; cleared when the server stops or stops answering pulls; shown as the
 * following overlay, which is not set again for the same ranges.
 */
class DocumentDiagnosticsTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<LspTestServer>
    private val delays = ManualDelays()
    private val shownCount = AtomicInteger()

    @AfterEach
    fun close() = harness.close()

    private fun bind(
        text: String,
        newServer: () -> LspTestServer,
    ) {
        harness =
            BoundEditorHarness(folder, text, newServer, { binding ->
                listOf(
                    DocumentDiagnostics(binding, 300.milliseconds, delays::schedule) { shownCount.incrementAndGet() },
                )
            })
    }

    private fun pullServer() = LspTestServer { diagnosticProvider = DiagnosticRegistrationOptions(false, false) }

    private fun error(
        message: String,
        line: Int,
        start: Int,
        end: Int,
    ) = Diagnostic(Range(Position(line, start), Position(line, end)), message).apply {
        severity = DiagnosticSeverity.Error
    }

    private fun full(
        resultId: String,
        vararg items: Diagnostic,
    ): DocumentDiagnosticReport {
        val report = RelatedFullDocumentDiagnosticReport(items.toList())
        report.resultId = resultId
        return DocumentDiagnosticReport(report)
    }

    private val underlines get() = harness.onFx { harness.editor.layers[DIAGNOSTICS_LAYER] }

    private val uri get() = LspUris.of(harness.onFx { harness.binding.file })

    private val pullOptions = JsonObject().apply { addProperty("identifier", "ty") }

    private fun registerPull(server: LspTestServer) =
        server.client.registerCapability(RegistrationParams(listOf(Registration("d", PULL_METHOD, pullOptions)))).get()

    @Test
    fun `pulls after open, once after a burst of changes with the last result id, and on refresh`() {
        bind("print(x)\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        assertNull(server.pulls[0].first.previousResultId)
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 6, 7)))
        awaitTrue("shown") { underlines == listOf(StyledRange(6, 7, "diagnostic-error")) }

        server.client.refreshDiagnostics()
        awaitTrue("pull on refresh") { server.pulls.size == 2 }
        assertEquals("r1", server.pulls[1].first.previousResultId)
        server.pulls[1].second.complete(DocumentDiagnosticReport(RelatedUnchangedDocumentDiagnosticReport("r1")))
        awaitTrue("answered") { shownCount.get() == 2 }
        assertEquals(1, harness.onFx { harness.editor.overlayCalls[DIAGNOSTICS_LAYER] }, "the same ranges again")

        harness.onFx { harness.editor.edit(0, 0, "a") }
        harness.onFx { harness.editor.edit(1, 1, "b") }
        assertEquals(1, delays.count, "the second change replaced the first one's pull")
        assertEquals(2, server.pulls.size)
        harness.run(delays)
        awaitTrue("pull after the changes") { server.pulls.size == 3 }
    }

    @Test
    fun `an answer for an older text is dropped and asked again`() {
        bind("print(x)\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        harness.onFx { harness.editor.edit(0, 0, "  ") }
        assertEquals(1, delays.scheduled)
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 6, 7)))
        awaitTrue("asked again after the debounce") { delays.scheduled == 2 }
        assertNull(underlines, "dropped: its range is for the text before the change")

        harness.run(delays)
        awaitTrue("asked again") { server.pulls.size == 2 }
        server.pulls[1].second.complete(full("r2", error("x undefined", 0, 8, 9)))
        awaitTrue("shown") { underlines == listOf(StyledRange(8, 9, "diagnostic-error")) }
    }

    @Test
    fun `a pull the server cancelled or that met a modified text is asked again, a failed one not`() {
        bind("x\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        server.pulls[0].second.completeExceptionally(failure(ResponseErrorCode.ServerCancelled))
        awaitTrue("asked again after the debounce") { delays.scheduled == 1 }
        harness.run(delays)
        awaitTrue("pull again") { server.pulls.size == 2 }
        server.pulls[1].second.completeExceptionally(failure(ResponseErrorCode.ContentModified))
        awaitTrue("asked again after the debounce") { delays.scheduled == 2 }
        harness.run(delays)
        awaitTrue("pull again") { server.pulls.size == 3 }

        server.pulls[2].second.completeExceptionally(failure(ResponseErrorCode.InternalError))
        // A request after the answer: handled once the client handled the answer; its pull is not delayed.
        server.client.refreshDiagnostics().get()
        awaitTrue("pull on refresh") { server.pulls.size == 4 }
        assertEquals(2, delays.scheduled, "a failure is not asked again")
    }

    private fun failure(code: ResponseErrorCode) = ResponseErrorException(ResponseError(code, "no", null))

    @Test
    fun `pushed diagnostics show at once, and a set for an older version is dropped`() {
        bind("import os\n") { LspTestServer() }
        val server = harness.server
        val version = harness.onFx { harness.binding.document.version }
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(error("unused", 0, 7, 9)), version))
        awaitTrue("shown") { underlines == listOf(StyledRange(7, 9, "diagnostic-error")) }

        harness.onFx { harness.editor.edit(0, 0, "#") }
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList(), version + 1))
        awaitTrue("the current version's set") { underlines == emptyList<StyledRange>() }
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(error("old", 0, 0, 1)), version))
        // A request after the notification: answered once the client handled what came before.
        server.client.refreshDiagnostics().get()
        harness.onFx {}
        assertEquals(emptyList(), underlines, "the older version's set is dropped")
    }

    @Test
    fun `a pull registered later is asked with its identifier, a stopped server's diagnostics go, with their id`() {
        bind("x\n") { LspTestServer() }
        val first = harness.server
        registerPull(first)
        awaitTrue("pull after the registration") { first.pulls.size == 1 }
        assertEquals("ty", first.pulls[0].first.identifier)
        first.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown") { underlines?.size == 1 }

        harness.onFx { harness.binding.session.restart() }
        awaitTrue("cleared when the server stopped") { underlines == emptyList<StyledRange>() }
        awaitTrue("restarted") { harness.fake.started.size == 2 }
        harness.awaitState(ServerState.Running)
        val second = harness.server
        registerPull(second)
        awaitTrue("pull of the new server") { second.pulls.size == 1 }
        assertNull(second.pulls[0].first.previousResultId)
    }

    @Test
    fun `a server that stops answering pulls takes the pulled diagnostics, the pushed ones stay`() {
        bind("x = y\n") { LspTestServer() }
        val server = harness.server
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(error("pushed", 0, 0, 1))))
        registerPull(server)
        awaitTrue("pull") { server.pulls.size == 1 }
        server.pulls[0].second.complete(full("r1", error("y undefined", 0, 4, 5)))
        awaitTrue("both shown") { underlines?.size == 2 }

        server.client.unregisterCapability(UnregistrationParams(listOf(Unregistration("d", PULL_METHOD)))).get()
        awaitTrue("the pulled one gone") { underlines == listOf(StyledRange(0, 1, "diagnostic-error")) }
    }

    @Test
    fun `a moved file starts anew, diagnostics gone, pulled under its new name, the old name's pushes ignored`() {
        bind("x\n") { pullServer() }
        val server = harness.server
        val oldUri = uri
        awaitTrue("pull after open") { server.pulls.size == 1 }
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown") { underlines?.size == 1 }

        harness.onFx { harness.editor.fileProperty.set(folder.resolve("b.py")) }
        awaitTrue("pulled under the new name") { server.pulls.size == 2 }
        assertEquals(
            LspUris.of(folder.resolve("b.py")),
            server.pulls[1]
                .first.textDocument.uri,
        )
        assertNull(server.pulls[1].first.previousResultId)
        assertEquals(emptyList(), underlines)

        server.client.publishDiagnostics(PublishDiagnosticsParams(oldUri, listOf(error("old name", 0, 0, 1))))
        server.client.refreshDiagnostics().get()
        harness.onFx {}
        assertEquals(emptyList(), underlines, "a push for the old name")
    }
}
