package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ServerCommand
import dev.sluide.projectdata.ProjectDataStore
import javafx.application.Platform
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Navigate menu over a window's code intelligence: a Shortcut+B press in a bound editor goes to the definition
 * once although the menu has the same accelerator, and Back and Forward (the menu item, Shortcut+Alt+Right in the
 * editor) return after the jump, File Structure and Go to Symbol open the search popup whose Enter jumps, and opening
 * the menu enables its items by the focus and the server. The flows are the code-intelligence module's tests. Needs a
 * display: accelerators run only in a scene's key dispatch, after the editor's own bindings, and keys reach a popup
 * only through its owner window.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class NavigateMenuUiTest {
    @TempDir
    lateinit var dir: Path

    private val root = BorderPane()

    /** Another control of the window that can take the focus. */
    private val elsewhere by lazy(LazyThreadSafetyMode.NONE) { TextField() }
    private lateinit var servers: FakeLanguageServers
    private lateinit var intelligence: CodeIntelligence
    private var view: FileView? = null
    private val definitionRequests = AtomicInteger()
    private val symbolRequests = AtomicInteger()

    /**
     * Declares definitions, answered with `def run` in the file, and document and workspace symbols: `run` and
     * `main`.
     */
    private inner class Server : FakeServer() {
        init {
            capabilities =
                ServerCapabilities().apply {
                    setTextDocumentSync(TextDocumentSyncKind.Incremental)
                    definitionProvider = Either.forLeft(true)
                    documentSymbolProvider = Either.forLeft(true)
                    workspaceSymbolProvider = Either.forLeft(true)
                }
        }

        private val run = Range(Position(0, 4), Position(0, 7))
        private val main = Range(Position(5, 4), Position(5, 8))

        override fun documentSymbol(
            params: DocumentSymbolParams,
        ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
            symbolRequests.incrementAndGet()
            val symbols =
                listOf(
                    DocumentSymbol("run", SymbolKind.Function, Range(Position(0, 0), Position(2, 0)), run),
                    DocumentSymbol("main", SymbolKind.Function, Range(Position(5, 0), Position(6, 0)), main),
                )
            return CompletableFuture.completedFuture(symbols.map { Either.forRight(it) })
        }

        override fun symbol(
            params: WorkspaceSymbolParams,
        ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
            val symbol = WorkspaceSymbol("main", SymbolKind.Function, Either.forLeft(Location(LspUris.of(file), main)))
            return CompletableFuture.completedFuture(Either.forRight(listOf(symbol)))
        }

        override fun definition(
            params: DefinitionParams,
        ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
            definitionRequests.incrementAndGet()
            val location = Location(LspUris.of(file), Range(Position(0, 4), Position(0, 7)))
            return CompletableFuture.completedFuture(Either.forLeft(listOf(location)))
        }
    }

    private val spec =
        object : LanguageServerSpec {
            override val id = "python"
            override val name = "Python"

            override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

            override fun languageId(file: Path) = "python"

            override fun command() = ServerCommand(Path.of("fake-server"))
        }

    private val text = "def run():\n    pass\n\nrun()\n\ndef main():\n    pass\n"
    private val file get() = dir.resolve("a.py")

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(root, 600.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        fx {
            view?.dispose()
            intelligence.close()
        }
        servers.close()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    /** The window: the Navigate menu over a Python editor bound to the fake server, the caret on the call of `run`. */
    private fun setUp(robot: FxRobot): ExtensibleCodeEditor {
        servers = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { Server() }
        servers.awaitRunning(spec)
        val prompts = InstallPrompts(ProjectDataStore(dir.resolve("p"), dir.resolve("shared.json"), emptyList()))
        intelligence = fx { CodeIntelligence(servers.servers, prompts) }
        file.writeText(text)
        var editor: ExtensibleCodeEditor? = null
        val services =
            WindowServices().apply {
                register(
                    CodeEditorExtensions::class.java,
                    CodeEditorExtensions().apply {
                        add(
                            CodeEditorExtension {
                                editor = it
                                null
                            },
                        )
                        add(intelligence)
                    },
                )
            }
        val opened = fx { PythonFileViewer().open(file) }
        view = opened
        robot.interact {
            root.top = MenuBar(navigateMenu(intelligence, root.scene.window))
            root.bottom = elsewhere
            root.center = opened.node
            opened.host =
                object : FileViewHost {
                    override fun openFile(file: Path) = false

                    override fun openWebPage(uri: URI) {}

                    override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
                }
        }
        val bound = checkNotNull(editor)
        robot.interact {
            area().requestFocus()
            bound.moveCaret(23)
        }
        return bound
    }

    private fun area(): Node = checkNotNull(view?.node?.lookup(".styled-text-area"))

    /** The search popup's field while it shows. */
    private fun searchField(): TextField? =
        fx {
            Window
                .getWindows()
                .filterIsInstance<Popup>()
                .filter { it.isShowing }
                .firstNotNullOfOrNull { it.scene.lookup(".search-popup-field") as? TextField }
        }

    /** A key [type] event as the window dispatches it to the focused editor: a popup shown gets it first. */
    private fun key(
        robot: FxRobot,
        type: EventType<KeyEvent>,
        code: KeyCode,
        character: String = "",
    ) {
        robot.interact { Event.fireEvent(area(), KeyEvent(type, character, "", code, false, false, false, false)) }
    }

    private fun menu(): Menu = fx { (root.top as MenuBar).menus.single() }

    /** A key press as it reaches the focused editor: through the scene, whose accelerators come after the editor. */
    private fun press(
        robot: FxRobot,
        code: KeyCode,
        alt: Boolean = false,
    ) {
        val mac = "mac" in System.getProperty("os.name").lowercase()
        robot.interact {
            Event.fireEvent(area(), KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, alt, mac))
        }
    }

    /** Waits until the server has handled every request sent before: one more request, answered in order. */
    private fun serverIdle() {
        val answered = CountDownLatch(1)
        val params = HoverParams(TextDocumentIdentifier(LspUris.of(file)), Position(0, 0))
        fx {
            servers.servers.session(spec).request(
                { it.textDocumentService.hover(params) },
                { answered.countDown() },
                { answered.countDown() },
            )
        }
        assertTrue(answered.await(5, TimeUnit.SECONDS), "the server answered")
    }

    @Test
    fun `Shortcut+B in a bound editor goes to the definition once although the Navigate menu has the same key`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        assertEquals(
            listOf("Back", "Forward", null, "Go to Definition", "Find Usages", "File Structure", "Go to Symbol…"),
            menu().items.map { it.text },
        )
        press(robot, KeyCode.B)
        serverIdle()
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(4, fx { editor.caretOffset })
        assertEquals(1, definitionRequests.get(), "one request: the editor took the key, the accelerator did not run")
    }

    @Test
    fun `Navigate - Back returns to where the jump started, and Shortcut+Alt+Right in the editor goes forward`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        val back = menu().items.single { it.text == "Back" }
        assertTrue(fx { back.isDisable }, "nothing to go back to yet")
        robot.interact { intelligence.goToDefinition() }
        serverIdle()
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(4, fx { editor.caretOffset })

        assertFalse(fx { back.isDisable })
        robot.interact { back.fire() }
        assertEquals(23, fx { editor.caretOffset })
        press(robot, KeyCode.RIGHT, alt = true)
        assertEquals(4, fx { editor.caretOffset })
    }

    @Test
    fun `Navigate - File Structure lists the file's symbols in the search popup, and Enter on one jumps to it`(
        robot: FxRobot,
    ) {
        val editor = setUp(robot)
        val structure = menu().items.single { it.text == "File Structure" }
        robot.interact { structure.fire() }
        // The symbols arrive from the server.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { searchField() != null && listSize() == 2 }
        key(robot, KeyEvent.KEY_PRESSED, KeyCode.DOWN)
        key(robot, KeyEvent.KEY_PRESSED, KeyCode.ENTER)
        assertEquals(text.indexOf("main"), fx { editor.caretOffset })
        assertTrue(searchField() == null, "the popup closed")
    }

    @Test
    fun `Shortcut+F12 in a bound editor shows the file structure once although the Navigate menu has the same key`(
        robot: FxRobot,
    ) {
        setUp(robot)
        press(robot, KeyCode.F12)
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { searchField() != null && listSize() == 2 }
        serverIdle()
        assertEquals(1, symbolRequests.get(), "one request: the editor took the key, the accelerator did not run")
    }

    @Test
    fun `Navigate - Go to Symbol searches the typed name, and Enter on the match jumps to it`(robot: FxRobot) {
        val editor = setUp(robot)
        val symbol = menu().items.single { it.text == "Go to Symbol…" }
        robot.interact { symbol.fire() }
        val field = checkNotNull(searchField()) { "the search popup shows" }
        for (character in "main") key(robot, KeyEvent.KEY_TYPED, KeyCode.UNDEFINED, character.toString())
        assertEquals("main", fx { field.text })
        // The search waits 200 ms after the last key, then the server answers.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { listSize() == 1 }
        key(robot, KeyEvent.KEY_PRESSED, KeyCode.ENTER)
        assertEquals(text.indexOf("main"), fx { editor.caretOffset })
        assertTrue(fx { intelligence.history.canGoBack.get() }, "the jump is recorded")
    }

    /** The number of rows in the shown search popup's list. */
    private fun listSize(): Int =
        fx {
            Window
                .getWindows()
                .filterIsInstance<Popup>()
                .filter { it.isShowing }
                .firstNotNullOfOrNull { it.scene.lookup(".caret-popup-list") as? ListView<*> }
                ?.items
                ?.size ?: -1
        }

    @Test
    fun `opening the menu enables the editor's items only for a focused editor whose server answers them`(
        robot: FxRobot,
    ) {
        setUp(robot)
        val names = listOf("Go to Definition", "Find Usages", "File Structure", "Go to Symbol…")
        val items = names.map { name -> menu().items.single { it.text == name } }
        robot.interact { menu().onShowing.handle(null) }
        // The server declares definitions and symbols, not references.
        assertEquals(listOf(false, true, false, false), items.map { it.isDisable })

        robot.interact {
            elsewhere.requestFocus()
            menu().onShowing.handle(null)
        }
        assertEquals(
            listOf(true, true, true, false),
            items.map { it.isDisable },
            "no editor with code intelligence focused; Go to Symbol asks the running server",
        )

        robot.interact {
            area().requestFocus()
            menu().onShowing.handle(null)
        }
        assertEquals(listOf(false, true, false, false), items.map { it.isDisable })
    }
}
