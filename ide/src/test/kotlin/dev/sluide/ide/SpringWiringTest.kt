package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.json.JsonFileViewer
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitLog
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.git.cli.GitCli
import dev.sluide.gitlog.GitLogView
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpEnvironmentFileViewer
import dev.sluide.httpclient.HttpFileViewer
import dev.sluide.projects.ProjectPicker
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.scratches.ScratchFiles
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.settings.pages.AppearancePage
import dev.sluide.settings.pages.FontsPage
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollPage
import dev.sluide.terminal.settings.TerminalScrollSettings
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies component scanning across modules. Beans stay lazy because JavaFX controls cannot be
 * instantiated without a running toolkit (and thus a display).
 */
class SpringWiringTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `context registers the main window and beans from all modules`() {
        SpringApplicationBuilder(SluIdeConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .lazyInitialization(true)
            .run()
            .use { context ->
                val types =
                    listOf(
                        MainWindow::class.java,
                        ProjectService::class.java,
                        FileExplorerView::class.java,
                        EditorArea::class.java,
                        FileViewers::class.java,
                        DefaultTextFileViewer::class.java,
                        PythonFileViewer::class.java,
                        FileWatcher::class.java,
                        Terminals::class.java,
                        FontSettings::class.java,
                        SettingsStore::class.java,
                        SettingsPages::class.java,
                        FontsPage::class.java,
                        ThemeSettings::class.java,
                        ThemedWindows::class.java,
                        AppearancePage::class.java,
                        TerminalScrollSettings::class.java,
                        TerminalScrollPage::class.java,
                        GitCli::class.java,
                        GitStatusService::class.java,
                        GitLog::class.java,
                        GitBranches::class.java,
                        GitCommits::class.java,
                        GitRebases::class.java,
                        GitRemotes::class.java,
                        GitPush::class.java,
                        GitLogView::class.java,
                        ProjectWindows::class.java,
                        SystemBrowser::class.java,
                        HttpClientSession::class.java,
                        HttpFileViewer::class.java,
                        HttpEnvironmentFileViewer::class.java,
                        RecentProjects::class.java,
                        ProjectPicker::class.java,
                        GitTrustCheck::class.java,
                        TrustedProjects::class.java,
                        ScratchFiles::class.java,
                    )
                for (type in types) {
                    assertEquals(1, context.getBeanNamesForType(type).size, "bean of type ${type.simpleName}")
                }
                // Every window has its own instance of these (ADR 0014).
                val windowTypes =
                    listOf(
                        MainWindow::class.java,
                        ProjectService::class.java,
                        FileExplorerView::class.java,
                        EditorArea::class.java,
                        Terminals::class.java,
                        DockerSandbox::class.java,
                        GitStatusService::class.java,
                        GitLogView::class.java,
                        HttpClientSession::class.java,
                        ProjectTrust::class.java,
                        BackgroundTasks::class.java,
                    )
                val scoped =
                    context.beanFactory.beanDefinitionNames.filter {
                        context.beanFactory.getBeanDefinition(it).scope == WINDOW_SCOPE
                    }
                assertEquals(
                    windowTypes.map { context.getBeanNamesForType(it).single() }.toSet(),
                    scoped.toSet(),
                )
                // Needs no JavaFX toolkit, so the viewer chain can be created for real.
                val viewers = context.getBean(FileViewers::class.java).viewers
                assertEquals(context.getBeansOfType(FileViewer::class.java).size, viewers.size, "all viewers are asked")
                assertIs<DefaultTextFileViewer>(viewers.last())
                // The HTTP client's environment files get its editor, other JSON files the JSON viewer (ADR 0057).
                val fileViewers = context.getBean(FileViewers::class.java)
                for (name in listOf("http-client.env.json", "http-client.private.env.json", "other.json")) {
                    dir.resolve(name).writeText("{}")
                }
                assertIs<HttpEnvironmentFileViewer>(fileViewers.viewerFor(dir.resolve("http-client.env.json")))
                assertIs<HttpEnvironmentFileViewer>(fileViewers.viewerFor(dir.resolve("http-client.private.env.json")))
                assertIs<JsonFileViewer>(fileViewers.viewerFor(dir.resolve("other.json")))
            }
    }
}
