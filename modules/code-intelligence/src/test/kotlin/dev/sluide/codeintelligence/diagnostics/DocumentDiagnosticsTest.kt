package dev.sluide.codeintelligence.diagnostics

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
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
    private lateinit var diagnostics: DocumentDiagnostics
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
                diagnostics =
                    DocumentDiagnostics(binding, 300.milliseconds, delays::schedule) { shownCount.incrementAndGet() }
                listOf(diagnostics)
            })
    }

    private fun pullServer(identifier: String? = null) =
        LspTestServer {
            diagnosticProvider = DiagnosticRegistrationOptions(false, false).also { it.identifier = identifier }
        }

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

    /** A diagnostic as lsp4j parses a server's JSON: LSP requires the range, but what the JSON lacks stays null. */
    private fun parsedDiagnostic(range: String?): Diagnostic {
        val json = "{" + (range?.let { "\"range\":$it," } ?: "") + "\"message\":\"m\"}"
        return MessageJsonHandler(emptyMap()).gson.fromJson(json, Diagnostic::class.java)
    }

    private fun pos(character: Int) = "{\"line\":0,\"character\":$character}"

    @Test
    fun `an edit moves the shown diagnostics, so an answer that places them there changes nothing`() {
        bind("print(x)\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 6, 7)))
        awaitTrue("shown") { underlines == listOf(StyledRange(6, 7, "diagnostic-error")) }

        harness.onFx { harness.editor.edit(0, 0, "  ") }
        harness.run(delays)
        awaitTrue("pull after the change") { server.pulls.size == 2 }
        server.pulls[1].second.complete(full("r2", error("x undefined", 0, 8, 9)))
        awaitTrue("answered") { shownCount.get() == 2 }
        assertEquals(
            1,
            harness.onFx { harness.editor.overlayCalls[DIAGNOSTICS_LAYER] },
            "the overlay followed the edit",
        )
    }

    @Test
    fun `an answer with nothing in it changes nothing and is not asked again`() {
        bind("x\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        server.pulls[0].second.complete(null)
        server.client.refreshDiagnostics().get()
        harness.onFx {}
        assertEquals(0, shownCount.get())
        assertEquals(0, delays.scheduled, "nothing to ask again")
        assertEquals(emptyList(), harness.onFx { diagnostics.all })
    }

    @Test
    fun `a pull the server cannot answer because it is not running is not asked again`() {
        bind("x\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        harness.onFx { harness.binding.session.restart() }
        awaitTrue("stopped") { harness.onFx { harness.binding.session.state.value } != ServerState.Running }
        harness.onFx { diagnostics.pull() }
        harness.onFx {}
        assertEquals(0, delays.scheduled)
    }

    @Test
    fun `a server that does not answer pulls is not asked on refresh, after a change or a move`() {
        bind("x\n") { LspTestServer() }
        val server = harness.server
        server.client.refreshDiagnostics().get()
        harness.onFx { harness.editor.edit(0, 0, "a") }
        harness.onFx { harness.editor.fileProperty.set(folder.resolve("b.py")) }
        // A request after the notifications: answered once the client handled what came before.
        server.client.refreshDiagnostics().get()
        harness.onFx {}
        assertEquals(emptyList(), server.pulls.toList())
        assertEquals(0, delays.scheduled, "no pull to wait for")
    }

    @Test
    fun `a change of the server's other features neither asks again nor drops what was pulled`() {
        bind("x = y\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        server.pulls[0].second.complete(full("r1", error("y undefined", 0, 4, 5)))
        awaitTrue("shown") { underlines?.size == 1 }
        val before = shownCount.get()

        val other = Registration("w", "workspace/didChangeWatchedFiles", JsonObject())
        server.client.registerCapability(RegistrationParams(listOf(other))).get()
        awaitTrue("the features changed") {
            harness.onFx {
                harness.binding.session.features
                    .supports("workspace/didChangeWatchedFiles")
            }
        }
        harness.onFx {}
        assertEquals(1, server.pulls.size, "no new pull")
        assertEquals(before, shownCount.get(), "nothing shown again")
        assertEquals(listOf(StyledRange(4, 5, "diagnostic-error")), underlines)
    }

    @Test
    fun `a server that never answered pulls is not asked when other features arrive`() {
        bind("x\n") { LspTestServer() }
        val server = harness.server
        val other = Registration("w", "workspace/didChangeWatchedFiles", JsonObject())
        server.client.registerCapability(RegistrationParams(listOf(other))).get()
        awaitTrue("the features changed") {
            harness.onFx {
                harness.binding.session.features
                    .supports("workspace/didChangeWatchedFiles")
            }
        }
        harness.onFx {}
        assertEquals(emptyList(), server.pulls.toList())
        assertEquals(0, shownCount.get())
    }

    @Test
    fun `a restarted server that answers pulls is asked afresh and what shows is shown once per change`() {
        bind("x\n") { pullServer() }
        val first = harness.server
        awaitTrue("pull after open") { first.pulls.size == 1 }
        first.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown") { underlines?.size == 1 }
        assertEquals(1, shownCount.get())

        harness.onFx { harness.binding.session.restart() }
        awaitTrue("cleared when the server stopped") { underlines == emptyList<StyledRange>() }
        awaitTrue("restarted") { harness.fake.started.size == 2 }
        harness.awaitState(ServerState.Running)
        val second = harness.server
        awaitTrue("pull of the new server") { second.pulls.size == 1 }
        assertNull(second.pulls[0].first.previousResultId, "the new server knows no result id")
        second.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown again") { underlines?.size == 1 }
        assertEquals(
            3,
            shownCount.get(),
            "shown, cleared on the stop, shown again: the start of the new server shows nothing",
        )
    }

    @Test
    fun `the identifier of a pull is the registered one, else the declared one, else none`() {
        val noIdentifier = JsonObject().apply { add("identifier", JsonObject()) }
        val cases =
            listOf(
                Triple("registered beats declared", pullOptions, "ty"),
                Triple("registered without identifier", JsonObject(), "declared"),
                Triple("registered with an identifier that is no string", noIdentifier, "declared"),
                Triple("registered without options", null, "declared"),
                Triple("registered with options that are no object", JsonPrimitive("ty"), "declared"),
            )
        for ((name, options, expected) in cases) {
            bind("x\n") { pullServer("declared") }
            val server = harness.server
            awaitTrue("pull after open") { server.pulls.isNotEmpty() }
            assertEquals("declared", server.pulls[0].first.identifier, "declared by the capabilities")
            val registration = Registration("d", PULL_METHOD, options)
            server.client.registerCapability(RegistrationParams(listOf(registration))).get()
            server.client.refreshDiagnostics().get()
            awaitTrue("pull on refresh") { server.pulls.size == 2 }
            assertEquals(expected, server.pulls[1].first.identifier, name)
            harness.close()
        }

        bind("x\n") { LspTestServer() }
        val registration = Registration("d", PULL_METHOD, JsonObject())
        harness.server.client
            .registerCapability(RegistrationParams(listOf(registration)))
            .get()
        awaitTrue("pull after the registration") { harness.server.pulls.isNotEmpty() }
        assertNull(
            harness.server.pulls[0]
                .first.identifier,
            "neither registered nor declared",
        )
    }

    @Test
    fun `a pushed set with parts left out places what it can and drops what has no place`() {
        bind("héllo wörld\n") { LspTestServer() }
        val server = harness.server
        val sets =
            listOf(
                parsedDiagnostic(null),
                parsedDiagnostic("{\"end\":${pos(3)}}"),
                // No end: empty at the start, widened to the character after it.
                parsedDiagnostic("{\"start\":${pos(1)}}"),
                // An end before the start counts as empty at the start.
                parsedDiagnostic("{\"start\":${pos(7)},\"end\":${pos(2)}}"),
                null,
            )
        @Suppress("UNCHECKED_CAST")
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, sets as List<Diagnostic>))
        awaitTrue("shown") { underlines?.size == 2 }
        assertEquals(
            listOf(StyledRange(1, 2, "diagnostic-error"), StyledRange(7, 8, "diagnostic-error")),
            underlines,
        )
        assertEquals(listOf(1 to 1, 7 to 7), harness.onFx { diagnostics.all.map { it.ownStart to it.ownEnd } })
    }

    @Test
    fun `positions count UTF-16 units, past a surrogate pair and past the end of the line`() {
        val emoji = String(Character.toChars(0x1F600))
        bind("a${emoji}b\ncd\n") { LspTestServer() }
        val server = harness.server
        server.client.publishDiagnostics(
            PublishDiagnosticsParams(uri, listOf(error("b", 0, 3, 4), error("past the line", 1, 1, 99))),
        )
        awaitTrue("shown") { underlines?.size == 2 }
        assertEquals(
            listOf(StyledRange(3, 4, "diagnostic-error"), StyledRange(6, 7, "diagnostic-error")),
            underlines,
        )
    }

    @Test
    fun `closing clears the overlay, drops the pending pull and stops following the editor and the server`() {
        bind("print(x)\n") { LspTestServer() }
        val server = harness.server
        registerPull(server)
        awaitTrue("pull after the registration") { server.pulls.size == 1 }
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 6, 7)))
        awaitTrue("shown") { underlines?.size == 1 }
        harness.onFx { harness.editor.edit(0, 0, "a") }
        assertEquals(1, delays.count, "a pull waits for the debounce")

        harness.onFx { diagnostics.close() }
        assertEquals(emptyList(), underlines, "the underlines are gone")
        assertEquals(0, delays.count, "the pending pull was dropped")
        val ownStart = harness.onFx { diagnostics.all.single().ownStart }
        val shown = shownCount.get()

        harness.onFx { harness.editor.edit(0, 0, "bb") }
        harness.onFx { diagnostics.pull() }
        server.client.refreshDiagnostics().get()
        server.client.unregisterCapability(UnregistrationParams(listOf(Unregistration("d", PULL_METHOD)))).get()
        awaitTrue("the features changed") {
            !harness.onFx {
                harness.binding.session.features
                    .supports(PULL_METHOD)
            }
        }
        harness.onFx {}
        assertEquals(ownStart, harness.onFx { diagnostics.all.single().ownStart }, "edits are not followed any more")
        assertEquals(1, server.pulls.size, "no pull after the close")
        assertEquals(0, delays.count, "nothing waits any more")
        assertEquals(shown, shownCount.get(), "nothing shown")
    }

    @Test
    fun `an answer that arrives after closing, and a push, show nothing`() {
        bind("print(x)\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        harness.onFx { diagnostics.close() }
        val calls = harness.onFx { harness.editor.overlayCalls[DIAGNOSTICS_LAYER] }
        server.pulls[0].second.complete(full("r1", error("x undefined", 0, 6, 7)))
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(error("pushed", 0, 0, 1))))
        server.client.refreshDiagnostics().get()
        harness.onFx {}
        assertEquals(calls, harness.onFx { harness.editor.overlayCalls[DIAGNOSTICS_LAYER] })
        assertEquals(emptyList(), harness.onFx { diagnostics.all })
    }

    @Test
    fun `a push without diagnostics in its JSON clears the pushed ones`() {
        bind("x = y\n") { LspTestServer() }
        val server = harness.server
        server.client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(error("pushed", 0, 0, 1))))
        awaitTrue("shown") { underlines?.size == 1 }
        // lsp4j leaves an explicit null in the list's place; the fake's client proxy would write no field at all.
        val raw = JsonParser.parseString("{\"uri\":\"$uri\",\"diagnostics\":null}")
        server.clientEndpoint.notify("textDocument/publishDiagnostics", raw)
        awaitTrue("cleared") { underlines == emptyList<StyledRange>() }
    }

    @Test
    fun `a pull that is asked for again while the debounce runs takes the debounce's place`() {
        bind("x\n") { pullServer() }
        val server = harness.server
        awaitTrue("pull after open") { server.pulls.size == 1 }
        harness.onFx { harness.editor.edit(0, 0, "a") }
        assertEquals(1, delays.count)
        server.client.refreshDiagnostics().get()
        awaitTrue("pull on refresh") { server.pulls.size == 2 }
        assertEquals(0, delays.count, "the debounce is not asked again after the pull it was for")
    }

    @Test
    fun `a crashed server clears its diagnostics once, and its new process is asked afresh`() {
        bind("x\n") { pullServer() }
        val first = harness.server
        awaitTrue("pull after open") { first.pulls.size == 1 }
        first.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown") { underlines?.size == 1 }
        assertEquals(1, shownCount.get())

        harness.fake.crashLatest()
        awaitTrue("cleared") { underlines == emptyList<StyledRange>() }
        harness.fake.runNextRetry()
        awaitTrue("restarted") { harness.fake.started.size == 2 }
        harness.awaitState(ServerState.Running)
        awaitTrue("asked afresh") { harness.server.pulls.size == 1 }
        assertNull(
            harness.server.pulls[0]
                .first.previousResultId,
        )
        assertEquals(2, shownCount.get(), "shown, cleared by the crash, nothing else")
    }

    @Test
    fun `a server that stopped is cleared once, whatever else its state goes through before it runs`() {
        bind("x\n") { pullServer() }
        val first = harness.server
        awaitTrue("pull after open") { first.pulls.size == 1 }
        first.pulls[0].second.complete(full("r1", error("x undefined", 0, 0, 1)))
        awaitTrue("shown") { underlines?.size == 1 }

        harness.fake.crashLatest()
        awaitTrue("cleared") { underlines == emptyList<StyledRange>() }
        // Restarting on request while the retry waits: Restarting -> Starting -> Running, no running server in between.
        harness.onFx { harness.binding.session.restart() }
        awaitTrue("restarted") { harness.fake.started.size == 2 }
        harness.awaitState(ServerState.Running)
        awaitTrue("asked afresh") { harness.server.pulls.size == 1 }
        assertEquals(2, shownCount.get(), "shown, cleared by the crash, nothing else")
    }
}
