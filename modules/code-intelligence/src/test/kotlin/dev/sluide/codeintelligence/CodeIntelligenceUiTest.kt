package dev.sluide.codeintelligence

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.projectdata.ProjectDataStore
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.stage.Stage
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The code intelligence as a code view's extension (ADR 0145): a Python file opened in a real code view reaches the
 * server and its edits follow; the banner's buttons reach the installer. The binding's and the banner's rules are
 * `DocumentBindingTest`'s and `ServerBannerModelTest`'s. Needs a display: the code view's extensions, changes and
 * banners exist only in a real view.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class CodeIntelligenceUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private var servers: FakeLanguageServers? = null
    private var intelligence: CodeIntelligence? = null

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        closeView()
        fx { intelligence?.close() }
        servers?.close()
    }

    private fun intelligence(spec: PySpec): CodeIntelligence {
        val fake = FakeLanguageServers(listOf(spec), dir, dispatch = Platform::runLater) { syncingServer() }
        servers = fake
        val prompts = InstallPrompts(ProjectDataStore(dir.resolve("p"), dir.resolve("shared.json"), emptyList()))
        return fx { CodeIntelligence(fake.servers, prompts) { false } }.also { intelligence = it }
    }

    @Test
    fun `a Python file opened in a code view is opened on the server, and its edits follow`(robot: FxRobot) {
        val spec = PySpec()
        val intelligence = intelligence(spec)
        val editor = open(robot, dir.resolve("main.py"), "print(1)\n", listOf(intelligence))
        val server = checkNotNull(servers).awaitRunning(spec)
        awaitTrue("didOpen") { server.documentNotifications.any { it is DidOpenTextDocumentParams } }
        val opened = server.documentNotifications.filterIsInstance<DidOpenTextDocumentParams>().last()
        assertEquals("print(1)\n", opened.textDocument.text)

        robot.interact { editor.applyEdits(listOf(OffsetEdit(6, 7, "2"))) }
        awaitTrue("didChange") { server.documentNotifications.any { it is DidChangeTextDocumentParams } }
        val change = server.documentNotifications.filterIsInstance<DidChangeTextDocumentParams>().single()
        assertEquals("2", change.contentChanges.single().text)
    }

    @Test
    fun `the banner's buttons install with the window's tasks, put the question off, and show the progress`(
        robot: FxRobot,
    ) {
        val installer = FakeInstaller()
        val intelligence = intelligence(PySpec(installer))
        val tasks = BackgroundTasks.forTests()
        open(robot, dir.resolve("main.py"), "", listOf(intelligence), BackgroundTasks::class.java to tasks)
        assertEquals("Code intelligence for Python needs ty (14 MB download).", fx { bannerText() })

        robot.interact { button("install").fire() }
        assertEquals(listOf<BackgroundTasks?>(tasks), installer.installs)
        robot.interact { installer.availability.set(ServerAvailability.Installing(0.25)) }
        assertEquals(0.25, fx { (lookup("#code-intelligence-progress") as ProgressBar).progress })
        robot.interact { button("cancel").fire() }
        assertEquals(1, installer.cancels)

        robot.interact { installer.availability.set(ServerAvailability.Failed("offline")) }
        assertEquals("ty could not be installed: offline", fx { bannerText() })
        robot.interact { button("not-now").fire() }
        assertNull(fx { lookup(".code-intelligence-banner") })
    }

    @Test
    fun `a bound editor shows the project status line and a click reveals its folder, until the binding ends`(
        robot: FxRobot,
    ) {
        val project = dir.resolve("backend")
        val spec = PySpec(status = { _, _ -> WorkspaceStatus("Python project: backend", project, "tooltip") })
        val intelligence = intelligence(spec)
        val revealed = mutableListOf<Path>()
        fx { intelligence.revealInExplorer = { revealed.add(it) } }
        projectFolder = dir
        open(robot, dir.resolve("main.py"), "print(1)\n", listOf(intelligence))
        checkNotNull(servers).awaitRunning(spec)
        awaitTrue("status line") { fx { lookup("#workspace-status") != null } }

        val item = fx { lookup("#workspace-status") as Hyperlink }
        assertEquals("Python project: backend", fx { item.text })
        // The muted look comes only from the line's stylesheet, so this fails if the stylesheet does not reach it.
        assertEquals(0.7, fx { item.opacity }, 0.001)

        robot.interact { item.fire() }
        assertEquals(listOf(project), revealed)

        fx { intelligence.close() }
        assertNull(fx { lookup("#workspace-status") }, "the line goes with the binding")
    }

    @Test
    fun `a bound editor shows the environment banner and its Run button opens the problem's command in a terminal`(
        robot: FxRobot,
    ) {
        val project = dir.resolve("backend")
        val problem =
            EnvironmentProblem(
                project,
                "No virtual environment for backend.",
                ProblemAction("Run uv sync", "uv sync (backend)", "uv sync"),
            )
        val spec = PySpec(problem = { _, _ -> problem })
        val intelligence = intelligence(spec)
        val runs = mutableListOf<Triple<Path, String, String>>()
        fx { intelligence.runInTerminal = { folder, name, command -> runs += Triple(folder, name, command) } }
        projectFolder = dir
        open(robot, dir.resolve("main.py"), "print(1)\n", listOf(intelligence))
        checkNotNull(servers).awaitRunning(spec)
        awaitTrue("environment banner") { fx { lookup("#code-intelligence-environment") != null } }

        val banner = fx { lookup("#code-intelligence-environment") }
        assertEquals("No virtual environment for backend.", fx { (banner?.lookup(".label") as Label).text })
        assertEquals("Run uv sync", fx { (lookup("#code-intelligence-environment-run") as Button).text })

        robot.interact { (lookup("#code-intelligence-environment-run") as Button).fire() }
        assertEquals(listOf(Triple(project, "uv sync (backend)", "uv sync")), runs)
    }

    @Test
    fun `a bound editor shows its config file's problems as links that open the file, and Not Now hides them`(
        robot: FxRobot,
    ) {
        val config = dir.resolve("tsconfig.json")
        val spec =
            PySpec(
                gateAnswer = AtomicReference(DocumentGate.SERVED),
                configs = { _, _ -> listOf(config) },
                keeps = { it.fileName.toString() == "tsconfig.json" },
            )
        val intelligence = intelligence(spec)
        projectFolder = dir
        open(robot, dir.resolve("main.py"), "print(1)\n", listOf(intelligence))
        val server = checkNotNull(servers).awaitRunning(spec)
        val problems =
            listOf(
                Diagnostic(Range(Position(2, 4), Position(2, 8)), "Option 'x' has been removed."),
                Diagnostic(Range(Position(0, 0), Position(0, 1)), "Bad value"),
            )
        server.client.publishDiagnostics(PublishDiagnosticsParams(LspUris.of(config), problems))
        awaitTrue("banner") { fx { lookup(".code-intelligence-config-problems") != null } }

        val banner = fx { lookup(".code-intelligence-config-problems") }
        assertEquals("tsconfig.json has 2 problems for Python:", fx { (banner?.lookup(".label") as Label).text })
        val links = fx { banner?.lookupAll(".hyperlink")?.map { (it as Hyperlink).text } }
        assertEquals(listOf("1:1  Bad value", "3:5  Option 'x' has been removed."), links)

        robot.interact { (banner?.lookupAll(".hyperlink")?.last() as Hyperlink).fire() }
        assertEquals(listOf(Triple(config, 2, 4)), openedAt)

        robot.interact { (lookup(".code-intelligence-config-not-now") as Button).fire() }
        assertNull(fx { lookup(".code-intelligence-config-problems") })
    }

    @Test
    fun `a document the server does not serve shows the reason only, while a served one beside it shows its banners`(
        robot: FxRobot,
    ) {
        val reason = EnvironmentProblem(dir, "This project uses TypeScript 5.", null)
        val config = dir.resolve("tsconfig.json")
        val installer = FakeInstaller()
        val spec =
            PySpec(
                installer,
                status = { _, _ -> WorkspaceStatus("Python project: backend", dir, null) },
                gateByFile = {
                    if (it.fileName.toString() ==
                        "a.py"
                    ) {
                        DocumentGate.SERVED
                    } else {
                        DocumentGate(false, reason = reason)
                    }
                },
                configs = { _, _ -> listOf(config) },
                keeps = { it.fileName.toString() == "tsconfig.json" },
            )
        val intelligence = intelligence(spec)
        projectFolder = dir
        val host = fx { host(listOf(intelligence)) }
        // The served document is a view-less editor beside the real one: the server runs for it, so what the
        // not-served editor lacks is the gate's doing alone.
        val served = FakeCodeEditor(dir.resolve("a.py"), "x", host)
        fx { intelligence.extend(served) }
        open(robot, dir.resolve("b.py"), "import os\nos.pa", listOf(intelligence))
        awaitTrue("reason banner") { fx { lookup("#code-intelligence-environment") != null } }
        assertEquals(
            "This project uses TypeScript 5.",
            fx { (lookup("#code-intelligence-environment")?.lookup(".label") as Label).text },
        )

        // The server is not installed: the served document asks for it, the other does not.
        awaitTrue("install banner of the served one") {
            fx {
                served.banners.any {
                    it.lookup("#code-intelligence-install") !=
                        null
                }
            }
        }
        assertNull(fx { lookup("#code-intelligence-install") }, "no install prompt for a document not served")

        robot.interact { installer.availability.set(ServerAvailability.Ready) }
        fx { installer.commandChanged() }
        checkNotNull(servers).awaitRunning(spec)
        val push =
            PublishDiagnosticsParams(
                LspUris.of(config),
                listOf(Diagnostic(Range(Position(0, 0), Position(0, 1)), "Bad")),
            )
        awaitTrue("status line of the served one") { fx { served.statusItems.isNotEmpty() } }
        awaitTrue("config banner of the served one") {
            // Pushed again until it shows: the install may have restarted the server the first push went to.
            checkNotNull(servers)
                .started
                .last()
                .client
                .publishDiagnostics(push)
            fx { served.banners.any { "code-intelligence-config-problems" in it.styleClass } }
        }
        assertNull(fx { lookup("#workspace-status") })
        assertNull(fx { lookup(".code-intelligence-config-problems") })

        fx {
            val binding = checkNotNull(intelligence.attached.last().binding)
            assertEquals(emptyList(), binding.hovers.providers, "no hover provider")
            assertEquals(emptyList(), binding.menuItems(EditorMenuTarget(0, 0)), "no context menu item")
        }
    }

    private fun lookup(selector: String) = view?.node?.lookup(selector)

    private fun bannerText() = (lookup(".code-intelligence-banner")?.lookup(".label") as Label).text

    private fun button(name: String) = lookup("#code-intelligence-$name") as Button
}
