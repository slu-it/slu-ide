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
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
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
 * editor) return after the jump, and opening the menu enables its items by the focus and the server. The flows are the
 * code-intelligence module's tests. Needs a display: accelerators
 * run only in a scene's key dispatch, after the editor's own bindings.
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

    /** Declares definitions and answers each with `def run` in the file. */
    private inner class Server : FakeServer() {
        init {
            capabilities =
                ServerCapabilities().apply {
                    setTextDocumentSync(TextDocumentSyncKind.Incremental)
                    definitionProvider = Either.forLeft(true)
                }
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

    private val text = "def run():\n    pass\n\nrun()\n"
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
            root.top = MenuBar(navigateMenu(intelligence))
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
        assertEquals(listOf("Back", "Forward", null, "Go to Definition", "Find Usages"), menu().items.map { it.text })
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
    fun `opening the menu enables Go to Definition and Find Usages only for a focused editor whose server answers them`(
        robot: FxRobot,
    ) {
        setUp(robot)
        val items = menu().items.filter { it.text == "Go to Definition" || it.text == "Find Usages" }
        robot.interact { menu().onShowing.handle(null) }
        // The server declares definitions, not references.
        assertEquals(listOf(false, true), items.map { it.isDisable })

        robot.interact {
            elsewhere.requestFocus()
            menu().onShowing.handle(null)
        }
        assertEquals(listOf(true, true), items.map { it.isDisable }, "no editor with code intelligence focused")

        robot.interact {
            area().requestFocus()
            menu().onShowing.handle(null)
        }
        assertEquals(listOf(false, true), items.map { it.isDisable })
    }
}
