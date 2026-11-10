package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.navigation.DEFINITION
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.codeintelligence.symbols.SymbolServer
import dev.sluide.codeintelligence.symbols.WORKSPACE_SYMBOL
import dev.sluide.codeintelligence.symbols.symbolSource
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ServerState
import javafx.collections.FXCollections
import javafx.scene.control.MenuItem
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The document binding of an editor over a fake server: open, every change (also a batch, whose text the editor shows
 * whole while it tells the changes one by one), save, moves and renames in and out of the language, close; the
 * features attach to each binding and close with it; positions convert between LSP and the editor.
 */
class DocumentBindingTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var server: FakeServer

    @AfterEach
    fun close() = fx.close()

    /** Code intelligence with [features] over a running server. */
    private fun intelligence(features: List<BindingFeature> = emptyList()): CodeIntelligence {
        fx = FxServers(folder, spec)
        server = fx.fake.awaitRunning(spec)
        return fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { features } }
    }

    private val opens get() = server.documentNotifications.filterIsInstance<DidOpenTextDocumentParams>()
    private val changes get() = server.documentNotifications.filterIsInstance<DidChangeTextDocumentParams>()
    private val closes
        get() = server.documentNotifications.filterIsInstance<DidCloseTextDocumentParams>().map { it.textDocument.uri }

    private fun uri(name: String) = LspUris.of(folder.resolve(name))

    @Test
    fun `an editor of a served file opens it, sends every change and save in order, and closes it when disposed`() {
        val intelligence = intelligence()
        val editor = FakeCodeEditor(folder.resolve("a.py"), "x = 1\ny = 2\n")
        val handle = fx.onFx { intelligence.extend(editor) }
        awaitTrue("didOpen") { opens.isNotEmpty() }
        assertEquals("x = 1\ny = 2\n", opens.single().textDocument.text)
        assertEquals(uri("a.py"), opens.single().textDocument.uri)

        fx.onFx { editor.edit(4, 5, "10") }
        // Two changes told after both are applied, the second in the text the first left.
        fx.onFx { editor.batch(listOf(Triple(0, 1, "xx"), Triple(8, 9, "yy"))) }
        awaitTrue("three changes") { changes.size == 3 }
        assertEquals(editor.currentText, serverText(opens.single().textDocument.text, changes))
        assertEquals(Range(Position(1, 0), Position(1, 1)), changes[2].contentChanges.single().range)

        fx.onFx { editor.save() }
        awaitTrue("didSave") { server.documentNotifications.last() is DidSaveTextDocumentParams }

        fx.onFx { handle?.close() }
        awaitTrue("didClose") { closes == listOf(uri("a.py")) }
        assertEquals(0, editor.subscribed, "no listener left")
    }

    @Test
    fun `request features attach while the document is served and close when it is not, always-on ones stay`() {
        val answer = AtomicReference(DocumentGate(false))
        val gating = PySpec(gateAnswer = answer)
        fx = FxServers(folder, gating)
        val events = mutableListOf<String>()
        val features =
            listOf(
                BindingFeature {
                    events += "banner attached"
                    AutoCloseable { events += "banner closed" }
                },
                ServedFeatures(
                    listOf(
                        BindingFeature {
                            events += "a attached"
                            AutoCloseable { events += "a closed" }
                        },
                        BindingFeature {
                            events += "b attached"
                            AutoCloseable { events += "b closed" }
                        },
                    ),
                ),
            )
        val intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { features } }
        val editor = FakeCodeEditor(folder.resolve("a.py"), "x")
        fx.onFx { intelligence.extend(editor) }
        val binding = checkNotNull(fx.onFx { intelligence.attached.single().binding })
        awaitTrue("answered") { fx.onFx { fx.servers.session(gating).gateOf(binding.file) } != null }
        assertEquals(false, fx.onFx { binding.served.value })
        assertEquals(listOf("banner attached"), fx.onFx { events.toList() })

        answer.set(DocumentGate.SERVED)
        fx.onFx { editor.fileProperty.set(folder.resolve("b.py")) }
        awaitTrue("served") { fx.onFx { binding.served.value } }
        assertEquals(listOf("banner attached", "a attached", "b attached"), fx.onFx { events.toList() })

        answer.set(DocumentGate(false))
        fx.onFx { editor.fileProperty.set(folder.resolve("c.py")) }
        awaitTrue("not served") { !fx.onFx { binding.served.value } }
        assertEquals(
            listOf("banner attached", "a attached", "b attached", "b closed", "a closed"),
            fx.onFx { events.toList() },
        )

        answer.set(DocumentGate.SERVED)
        fx.onFx { editor.fileProperty.set(folder.resolve("d.py")) }
        awaitTrue("served again") { fx.onFx { binding.served.value } }
        assertEquals(listOf("a attached", "b attached"), fx.onFx { events.takeLast(2) })

        fx.onFx { intelligence.close() }
        assertEquals(listOf("b closed", "a closed", "banner closed"), fx.onFx { events.takeLast(3) })
    }

    @Test
    fun `a document not served has a blocker naming the language and is no go to symbol source, a served one is`() {
        val gating =
            PySpec(gateByFile = {
                if (it.fileName.toString() ==
                    "a.py"
                ) {
                    DocumentGate.SERVED
                } else {
                    DocumentGate(false)
                }
            })
        fx = FxServers(folder, gating, newServer = { SymbolServer() })
        val intelligence = fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { emptyList() } }
        val editorA = FakeCodeEditor(folder.resolve("a.py"), "x")
        val editorB = FakeCodeEditor(folder.resolve("b.py"), "y")
        fx.onFx {
            intelligence.extend(editorA)
            intelligence.extend(editorB)
        }
        val (a, b) = fx.onFx { intelligence.attached.map { checkNotNull(it.binding) } }
        awaitTrue("served and running") { fx.onFx { a.served.value && a.session.state.value == ServerState.Running } }
        awaitTrue("b answered") { fx.onFx { fx.servers.session(gating).gateOf(b.file) } != null }

        assertEquals(
            "not served by the Python language server",
            fx.onFx { navigationBlocker(b, DEFINITION) },
        )
        assertNull(fx.onFx { navigationBlocker(a, WORKSPACE_SYMBOL) }, "the server runs and a is served")
        // Only the gate keeps b out: its server runs and supports workspace symbols.
        assertEquals(a, fx.onFx { symbolSource(b, listOf(b, a)) })
    }

    @Test
    fun `a spec that does not gate is served from the start and its features attach at once`() {
        val attached = mutableListOf<String>()
        val intelligence =
            intelligence(
                listOf(
                    ServedFeatures(
                        listOf(
                            BindingFeature {
                                attached += "a"
                                null
                            },
                        ),
                    ),
                ),
            )
        val editor = FakeCodeEditor(folder.resolve("a.py"), "x")
        fx.onFx { intelligence.extend(editor) }
        assertEquals(listOf("a"), fx.onFx { attached.toList() })
        assertEquals(
            true,
            fx.onFx {
                intelligence.attached
                    .single()
                    .binding
                    ?.served
                    ?.value
            },
        )
    }

    @Test
    fun `renaming binds and unbinds by language, and a move within it moves the document`() {
        val attached = mutableListOf<Path>()
        val closed = mutableListOf<Path>()
        val intelligence =
            intelligence(
                listOf(
                    BindingFeature { binding ->
                        val file = binding.file
                        attached.add(file)
                        AutoCloseable { closed.add(file) }
                    },
                ),
            )
        val editor = FakeCodeEditor(folder.resolve("notes.txt"), "text")
        fx.onFx { intelligence.extend(editor) }
        assertNull(fx.onFx { intelligence.attached.single().binding }, "a text file is not bound")

        fx.onFx { editor.fileProperty.set(folder.resolve("a.py")) }
        awaitTrue("didOpen") { opens.size == 1 }
        fx.onFx { editor.fileProperty.set(folder.resolve("sub/b.py")) }
        awaitTrue("moved") { opens.size == 2 }
        assertEquals(listOf(uri("a.py")), closes)
        assertEquals(uri("sub/b.py"), opens.last().textDocument.uri)
        assertEquals(listOf(folder.resolve("a.py")), attached, "a move keeps the binding and its features")

        fx.onFx { editor.fileProperty.set(folder.resolve("b.txt")) }
        awaitTrue("unbound") { closes.size == 2 }
        assertNull(fx.onFx { intelligence.attached.single().binding })
        assertEquals(listOf(folder.resolve("a.py")), closed)

        fx.onFx { editor.fileProperty.set(folder.resolve("c.py")) }
        awaitTrue("bound again") { opens.size == 3 }
        assertEquals(2, attached.size)
    }

    @Test
    fun `the context menu shows the features' items only while the editor is bound`() {
        val intelligence =
            intelligence(listOf(BindingFeature { it.addContextMenuItems { listOf(MenuItem("Go")) } }))
        val editor = FakeCodeEditor(folder.resolve("a.py"), "")
        fx.onFx { intelligence.extend(editor) }
        assertEquals(listOf("Go"), fx.onFx { editor.menuItems().map { it.text } })

        fx.onFx { editor.fileProperty.set(folder.resolve("a.txt")) }
        assertEquals(emptyList(), fx.onFx { editor.menuItems() })
        fx.onFx { editor.fileProperty.set(folder.resolve("a.py")) }
        assertEquals(listOf("Go"), fx.onFx { editor.menuItems().map { it.text } }, "once per editor, not per binding")
    }

    @Test
    fun `a feature that throws is left out and the others attach`() {
        val attached = mutableListOf<String>()
        val intelligence =
            intelligence(
                listOf(
                    BindingFeature { error("broken") },
                    BindingFeature {
                        attached += "second"
                        null
                    },
                ),
            )
        fx.onFx { intelligence.extend(FakeCodeEditor(folder.resolve("a.py"), "")) }
        assertEquals(listOf("second"), attached)
    }

    @Test
    fun `positions convert on the document between LSP and editor offsets`() {
        val intelligence = intelligence()
        val editor = FakeCodeEditor(folder.resolve("a.py"), "ab\n😀cd\n")
        fx.onFx { intelligence.extend(editor) }
        val binding = assertNotNull(fx.onFx { intelligence.attached.single().binding })
        fx.onFx {
            assertEquals(Position(1, 3), binding.positionOf(6), "a character outside the BMP counts twice")
            assertEquals(6, binding.offsetOf(Position(1, 3)))
            assertEquals(2, binding.offsetOf(Position(0, 99)), "past the line's end: its end")
            assertEquals(3 until 7, binding.offsetsOf(Range(Position(1, 0), Position(1, 4))))
            assertEquals(
                4,
                binding.offsetsOf(Range(Position(1, 1), Position(1, 1))).first,
                "an empty range keeps its offset",
            )
            assertEquals(
                OffsetEdit(3, 7, "x"),
                binding.editOf(TextEdit(Range(Position(1, 0), Position(1, 4)), "x")),
            )
            assertEquals(OffsetEdit(2, 2, "!"), binding.editOf(TextEdit(Range(Position(0, 2), Position(0, 2)), "!")))
            assertEquals(Range(Position(0, 1), Position(1, 1)), binding.rangeOf(1, 4))
            assertEquals(uri("a.py"), binding.positionParams(0).textDocument.uri)
        }
    }

    /** The text the server has after applying [changes] (incremental) to [opened]. */
    private fun serverText(
        opened: String,
        changes: List<DidChangeTextDocumentParams>,
    ): String {
        var text = opened
        for (event in changes.flatMap { it.contentChanges }) {
            val start = offsetIn(text, event.range.start)
            val end = offsetIn(text, event.range.end)
            text = text.substring(0, start) + event.text + text.substring(end)
        }
        return text
    }

    private fun offsetIn(
        text: String,
        position: Position,
    ): Int {
        val lineStart =
            (0 until position.line).fold(0) { start, _ -> text.indexOf('\n', start) + 1 }
        return lineStart + position.character
    }
}
