package dev.sluide.languageservers

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Document sync over fake servers: ranges from the document's own copy, the sync kinds, the fallback for a change
 * that does not fit, counted opens, moves, saves and the reopen on every new server. A single-thread executor plays
 * the FX thread: every call into the session and every listener runs there.
 */
internal class DocumentSyncTest : DocumentSyncTestBase() {
    @Test
    fun `each change of a batch reaches an incremental server as a range on the text the change before left`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "ab\ncd\n")

        onFx {
            // Replace All style: the editor already shows the batch's end when it reports the changes one by one.
            document.changed(DocumentChange(4, "", "1\n2\n")) { "a\nd\n" }
            // Lower offset, after the first change added lines: its end (7) exists only in the copy after the first.
            document.changed(DocumentChange(1, "b\nc1\n2", "")) { "a\nd\n" }
        }
        session.flush(server)

        assertEquals(listOf("1:1-1:1 \"1\\n2\\n\"", "0:1-2:1 \"\""), server.changes.map { it.describe() })
        assertEquals(listOf(2, 3), server.changes.map { it.textDocument.version })
        assertEquals(
            uri("a.py"),
            server.changes
                .first()
                .textDocument.uri,
        )
        assertEquals("a\nd\n", onFx { document.text })
        assertEquals(3, onFx { document.version })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syncKinds")
    fun `the server's sync kind decides what a change sends`(
        case: String,
        capabilities: ServerCapabilities,
        registered: TextDocumentSyncKind?,
        expected: List<String>,
    ) {
        val session = session({ capabilities })
        val server = session.running()
        if (registered != null) {
            val options = TextDocumentChangeRegistrationOptions(registered)
            server.client
                .registerCapability(RegistrationParams(listOf(Registration("c", "textDocument/didChange", options))))
                .get(5, TimeUnit.SECONDS)
        }
        val document = session.open("a.py", "x = 1\n")

        onFx { document.changed(DocumentChange(4, "1", "22")) { "x = 22\n" } }
        session.flush(server)

        assertEquals(expected, server.changes.map { it.describe() })
        assertEquals(2, onFx { document.version })
    }

    @Test
    fun `a change that does not fit the copy sends the editor's text whole and skips the rest of its batch`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")

        onFx {
            // Claims to remove "X", which the copy does not have at 1: the copy went wrong somewhere.
            document.changed(DocumentChange(1, "X", "Q")) { "aQc!" }
            // The rest of the batch: in the editor's text already.
            document.changed(DocumentChange(3, "", "!")) { "aQc!" }
        }
        onFx { document.changed(DocumentChange(4, "", "?")) { "aQc!?" } }
        session.flush(server)

        assertEquals(listOf("whole \"aQc!\"", "0:4-0:4 \"?\""), server.changes.map { it.describe() })
        assertEquals(listOf(2, 3), server.changes.map { it.textDocument.version })
        assertEquals("aQc!?", onFx { document.text })
    }

    @Test
    fun `a file opened twice is opened and closed on the server once`() {
        val session = session()
        val server = session.running()
        val first = session.open("a.py", "one")
        val second = session.open("a.py", "ignored")

        onFx {
            first.close()
            first.close()
        }
        session.flush(server)
        assertEquals(
            listOf("initialize", "initialized", "didOpen one"),
            server.calls.filter {
                it !=
                    "didChangeConfiguration"
            },
        )
        assertTrue(onFx { session.documents.isOpen(folder.resolve("a.py")) })
        assertEquals("one", onFx { second.text })

        onFx { second.close() }
        session.flush(server)

        assertEquals(listOf(uri("a.py")), server.closes)
        assertFalse(onFx { session.documents.isOpen(folder.resolve("a.py")) })
    }

    @Test
    fun `a closed handle changes nothing, the document's other handle goes on`() {
        val session = session()
        val server = session.running()
        val first = session.open("a.py", "abc")
        val second = session.open("a.py", "abc")
        onFx { first.close() }

        onFx { first.changed(DocumentChange(0, "a", "A")) { "Abc" } }
        session.flush(server)

        assertEquals(emptyList(), server.changes)
        assertEquals("abc" to 1, onFx { second.text to second.version })
    }

    @Test
    fun `a document moved onto an open one closes that one, whose handles then do nothing`() {
        val session = session()
        val server = session.running()
        val moving = session.open("a.py", "moving")
        val stale = session.open("b.py", "stale")

        onFx { moving.moved(folder.resolve("b.py")) }
        onFx {
            stale.changed(DocumentChange(0, "s", "S")) { "Stale" }
            stale.saved()
            stale.close()
        }
        session.flush(server)

        assertEquals(listOf(uri("b.py"), uri("a.py")), server.closes)
        assertEquals(listOf("moving", "stale", "moving"), server.opens.map { it.textDocument.text })
        assertEquals(emptyList(), server.changes)
        assertEquals(emptyList(), server.documentNotifications.filterIsInstance<DidSaveTextDocumentParams>())
        assertTrue(onFx { session.documents.isOpen(folder.resolve("b.py")) })
    }

    @Test
    fun `a moved document is closed under its old URI and opened under the new one with its current text`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")
        onFx { document.changed(DocumentChange(3, "", "d")) { "abcd" } }

        onFx { document.moved(folder.resolve("sub/b.py")) }
        session.flush(server)

        val close = server.documentNotifications[2] as DidCloseTextDocumentParams
        val reopen = server.documentNotifications[3] as DidOpenTextDocumentParams
        assertEquals(uri("a.py"), close.textDocument.uri)
        assertEquals(
            listOf(uri("sub/b.py"), "abcd", "3"),
            reopen.textDocument.let { listOf(it.uri, it.text, "${it.version}") },
        )
        assertEquals(folder.resolve("sub/b.py"), onFx { document.file })
        assertFalse(onFx { session.documents.isOpen(folder.resolve("a.py")) })
        assertTrue(onFx { session.documents.isOpen(folder.resolve("sub/b.py")) })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("saveOptions")
    fun `a save sends didSave as the server asks for it`(
        case: String,
        capabilities: ServerCapabilities,
        registered: TextDocumentSaveRegistrationOptions?,
        expected: List<String?>,
    ) {
        val session = session({ capabilities })
        val server = session.running()
        if (registered != null) {
            server.client
                .registerCapability(RegistrationParams(listOf(Registration("s", "textDocument/didSave", registered))))
                .get(5, TimeUnit.SECONDS)
        }
        val document = session.open("a.py", "saved text")

        onFx { document.saved() }
        session.flush(server)

        assertEquals(
            expected,
            server.documentNotifications.filterIsInstance<DidSaveTextDocumentParams>().map { it.text },
        )
    }

    @Test
    fun `a restarted server gets every open document again with its current text and a new version`() {
        val session = session()
        val first = session.running()
        val document = session.open("a.py", "abc")
        onFx { document.changed(DocumentChange(0, "a", "A")) { "Abc" } }
        session.flush(first)

        val before = connects.get()
        onFx { session.restart() }
        awaitTrue("second server connected") { connects.get() > before }
        val second = starter.last.server
        session.flush(second)

        assertEquals(listOf(1), first.opens.map { it.textDocument.version })
        assertEquals(listOf("Abc" to 3), second.opens.map { it.textDocument.text to it.textDocument.version })
        assertEquals(3, onFx { document.version })
    }

    @Test
    fun `a document opened and changed while the server starts reaches it once, with its current text`() {
        val release = CountDownLatch(1)
        val session = session(beforeStart = { release.await(5, TimeUnit.SECONDS) })
        onFx { session.ensureStarted() }
        awaitTrue("starting") { onFx { session.state.value } == ServerState.Starting }

        val document = session.open("a.py", "abc")
        onFx { document.changed(DocumentChange(3, "", "d")) { "abcd" } }
        release.countDown()

        awaitTrue("connected") { connects.get() == 1 }
        val server = starter.last.server
        session.flush(server)
        assertEquals(listOf("abcd" to 3), server.opens.map { it.textDocument.text to it.textDocument.version })
        assertEquals(emptyList(), server.changes)
    }

    @Test
    fun `a window whose documents are all not served never starts the server, not on edits, requests or restarts`() {
        val session = session(spec = gating)
        val old = session.open("old.py", "x")
        val error = CompletableFuture<Throwable>()

        onFx {
            old.changed(DocumentChange(1, "", "y")) { "xy" }
            old.saved()
            session.ensureStarted()
            session.request<Any>({ CompletableFuture() }, {}, { error.complete(it) })
            session.notify {
                it.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>()))
            }
            session.restart()
            old.moved(folder.resolve("old2.py"))
        }

        assertIs<LanguageServerNotRunningException>(error.get(5, TimeUnit.SECONDS))
        assertEquals(DocumentGate(false), onFx { session.gateOf(folder.resolve("old2.py")) })
        assertEquals(null, onFx { session.gateOf(folder.resolve("old.py")) }, "not open any more")
        onFx { old.close() }
        assertEquals(ServerState.Stopped, onFx { session.state.value })
        assertEquals(emptyList(), starter.processes)
    }

    @Test
    fun `a started gated server restarts also once its last served document closed`() {
        val session = session(spec = gating)
        val served = session.open("a.py", "a")
        awaitTrue("started by the served document") { connects.get() == 1 }
        onFx { served.close() }

        onFx { session.restart() }

        awaitTrue("restarted, for an update of the server") { connects.get() == 2 }
        assertEquals(2, starter.processes.size)
    }

    @Test
    fun `a document its gate does not serve never reaches the server, through edits, saves, moves and restarts`() {
        val session = session(spec = gating, capabilities = { saving(Either.forRight(SaveOptions(true))) })
        val served = session.open("a.py", "a")
        awaitTrue("started by the served document") { connects.get() == 1 }
        val first = starter.last.server
        val old = session.open("old.py", "x")
        onFx {
            old.changed(DocumentChange(1, "", "y")) { "xy" }
            old.saved()
            old.moved(folder.resolve("old2.py"))
            served.changed(DocumentChange(1, "", "b")) { "ab" }
        }
        session.flush(first)

        val before = connects.get()
        onFx { session.restart() }
        awaitTrue("restarted") { connects.get() > before }
        val second = starter.last.server
        onFx {
            // Served under its new name: opened with its current text, the first version it reaches a server with.
            old.moved(folder.resolve("new.py"))
            old.close()
            served.close()
        }
        session.flush(second)

        // The first didOpen went to the starting server and was dropped; the reopen on connect counts one more.
        assertEquals(listOf("open a.py 2", "change a.py"), first.log())
        assertEquals(listOf("open a.py 4", "open new.py 2", "close new.py", "close a.py"), second.log())
        assertEquals(
            "xy",
            second.opens
                .single { it.textDocument.uri == uri("new.py") }
                .textDocument.text,
        )
        first.assertClosesFollowOpens()
        second.assertClosesFollowOpens()
    }

    private fun DidChangeTextDocumentParams.describe() = contentChanges.single().describe()

    private fun TextDocumentContentChangeEvent.describe(): String {
        val quoted = "\"" + text.replace("\n", "\\n") + "\""
        val range = range ?: return "whole $quoted"
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character} $quoted"
    }

    companion object {
        @JvmStatic
        fun syncKinds(): List<Arguments> =
            listOf(
                Arguments.of("incremental", syncing(TextDocumentSyncKind.Incremental), null, listOf("0:4-0:5 \"22\"")),
                Arguments.of("full", syncing(TextDocumentSyncKind.Full), null, listOf("whole \"x = 22\\n\"")),
                Arguments.of("none", syncing(TextDocumentSyncKind.None), null, emptyList<String>()),
                Arguments.of(
                    "incremental in the options",
                    ServerCapabilities().apply {
                        setTextDocumentSync(
                            TextDocumentSyncOptions().apply { change = TextDocumentSyncKind.Incremental },
                        )
                    },
                    null,
                    listOf("0:4-0:5 \"22\""),
                ),
                Arguments.of(
                    "registered incremental over declared full",
                    syncing(TextDocumentSyncKind.Full),
                    TextDocumentSyncKind.Incremental,
                    listOf("0:4-0:5 \"22\""),
                ),
                Arguments.of(
                    "registered full over declared nothing",
                    ServerCapabilities(),
                    TextDocumentSyncKind.Full,
                    listOf("whole \"x = 22\\n\""),
                ),
            )

        @JvmStatic
        private fun saving(save: Either<Boolean, SaveOptions>?) =
            ServerCapabilities().apply {
                setTextDocumentSync(
                    TextDocumentSyncOptions().also {
                        it.change = TextDocumentSyncKind.Full
                        it.save = save
                    },
                )
            }

        @JvmStatic
        fun saveOptions(): List<Arguments> =
            listOf(
                Arguments.of("with text", saving(Either.forRight(SaveOptions(true))), null, listOf("saved text")),
                Arguments.of("without text", saving(Either.forRight(SaveOptions(false))), null, listOf(null)),
                Arguments.of("true", saving(Either.forLeft(true)), null, listOf(null)),
                Arguments.of("false", saving(Either.forLeft(false)), null, emptyList<String?>()),
                Arguments.of("not wanted", saving(null), null, emptyList<String?>()),
                Arguments.of("bare kind incremental", syncing(TextDocumentSyncKind.Incremental), null, listOf(null)),
                Arguments.of("bare kind none", syncing(TextDocumentSyncKind.None), null, emptyList<String?>()),
                Arguments.of(
                    "registered with text over declared nothing",
                    saving(null),
                    TextDocumentSaveRegistrationOptions(true),
                    listOf("saved text"),
                ),
            )
    }
}
