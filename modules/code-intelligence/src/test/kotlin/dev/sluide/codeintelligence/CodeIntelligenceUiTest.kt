package dev.sluide.codeintelligence

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.projectdata.ProjectDataStore
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.stage.Stage
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
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

        robot.interact { item.fire() }
        assertEquals(listOf(project), revealed)

        fx { intelligence.close() }
        assertNull(fx { lookup("#workspace-status") }, "the line goes with the binding")
    }

    private fun lookup(selector: String) = view?.node?.lookup(selector)

    private fun bannerText() = (lookup(".code-intelligence-banner")?.lookup(".label") as Label).text

    private fun button(name: String) = lookup("#code-intelligence-$name") as Button
}
