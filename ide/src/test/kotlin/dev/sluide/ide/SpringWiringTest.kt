package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.codecs.Codecs
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.codereview.Reviews
import dev.sluide.coloring.Coloring
import dev.sluide.coloring.SavedColors
import dev.sluide.commandruns.ArgumentHistories
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.converting.Converting
import dev.sluide.converting.FormatFamily
import dev.sluide.databases.Databases
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.settings.DatabasesSettings
import dev.sluide.databases.settings.DatabasesSettingsPage
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.diagnostics.Diagnostics
import dev.sluide.docker.Docker
import dev.sluide.docker.DockerSession
import dev.sluide.docker.compose.ComposeFileViewer
import dev.sluide.docker.dockerfile.DockerBuildStore
import dev.sluide.docker.dockerfile.DockerfileBuildViewer
import dev.sluide.docker.settings.DockerSettings
import dev.sluide.docker.settings.DockerSettingsPage
import dev.sluide.dockersandbox.DockerSandbox
import dev.sluide.ebooks.EpubFileViewer
import dev.sluide.editorarea.EditorArea
import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettingsPage
import dev.sluide.encryptedfiles.ui.EncryptedFileViewer
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.ExplorerSettings
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.SoftWrap
import dev.sluide.fileviewers.languages.IgnoreFileViewer
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.library.LibraryCodeExtension
import dev.sluide.fileviewers.settings.EditorSettingsPage
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.games.BlockBreaker
import dev.sluide.games.Doubles
import dev.sluide.games.Minesweeper
import dev.sluide.games.MiniGolf
import dev.sluide.games.OrbitGuard
import dev.sluide.games.PipePuzzle
import dev.sluide.games.Solitaire
import dev.sluide.games.Sudoku
import dev.sluide.games.WordGuess
import dev.sluide.games.blockbreaker.BlockBreakerPreferences
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.doubles.DoublesPreferences
import dev.sluide.games.minesweeper.MinesweeperPreferences
import dev.sluide.games.minigolf.MiniGolfPreferences
import dev.sluide.games.orbitguard.OrbitGuardPreferences
import dev.sluide.games.pipepuzzle.PipePuzzlePreferences
import dev.sluide.games.solitaire.SolitairePreferences
import dev.sluide.games.sudoku.SudokuPreferences
import dev.sluide.games.wordguess.WordGuessPreferences
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitFileComparison
import dev.sluide.git.GitLog
import dev.sluide.git.GitMerges
import dev.sluide.git.GitPulls
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitReviews
import dev.sluide.git.GitRewords
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitTrustCheck
import dev.sluide.git.RemoteSourceCache
import dev.sluide.git.cli.GitCli
import dev.sluide.gitlog.GitLogView
import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.trust.GradleTrustCheck
import dev.sluide.gradle.view.GradleGroovyFileViewer
import dev.sluide.gradle.view.GradleKotlinFileViewer
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpEnvironmentFileViewer
import dev.sluide.httpclient.HttpFileViewer
import dev.sluide.httpclient.send.SavedPassphrases
import dev.sluide.ide.codeintelligence.CodeIntelligenceWiring
import dev.sluide.ide.git.GitReviewSource
import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.media.MediaFileViewer
import dev.sluide.media.MediaPreferences
import dev.sluide.media.PlaybackCoordinator
import dev.sluide.previews.json.JsonFileViewer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.yaml.YamlFileViewer
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.projects.LegacyRecentProjects
import dev.sluide.projects.ProjectPicker
import dev.sluide.projects.RecentProjects
import dev.sluide.projecttrust.LegacyTrustDecisions
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.python.TySpec
import dev.sluide.python.TyUpdates
import dev.sluide.python.settings.PythonSettingsPage
import dev.sluide.scratches.ScratchFiles
import dev.sluide.secrets.Secrets
import dev.sluide.secrets.settings.SecretsSettingsPage
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsPages
import dev.sluide.settings.SettingsStore
import dev.sluide.settings.ThemeSettings
import dev.sluide.settings.ThemedWindows
import dev.sluide.settings.pages.AppearancePage
import dev.sluide.settings.pages.FontsPage
import dev.sluide.shellscripts.ShellScriptFileViewer
import dev.sluide.speech.ReadAloud
import dev.sluide.speech.SpeechPreferences
import dev.sluide.speech.engine.SpeechEngines
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.install.ModelStore
import dev.sluide.speech.kokoro.KokoroEngine
import dev.sluide.speech.settings.SpeechSettingsPage
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollPage
import dev.sluide.terminal.settings.TerminalScrollSettings
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.Todos
import dev.sluide.translation.DownloadSizes
import dev.sluide.translation.Translate
import dev.sluide.translation.TranslationPreferences
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.TranslationEngine
import dev.sluide.translation.opusmt.OpusMtEngine
import dev.sluide.translation.settings.TranslationSettingsPage
import dev.sluide.typescript.TypeScriptSpec
import dev.sluide.typescript.TypeScriptUpdates
import dev.sluide.typescript.settings.TypeScriptSettingsPage
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.MethodParameter
import java.nio.file.Path
import kotlin.io.path.writeBytes
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
                        ExplorerClipboard::class.java,
                        ExplorerSettings::class.java,
                        EditorArea::class.java,
                        FileViewers::class.java,
                        DefaultTextFileViewer::class.java,
                        PythonFileViewer::class.java,
                        MarkdownFileViewer::class.java,
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
                        GitFileComparison::class.java,
                        GitBranches::class.java,
                        GitCommits::class.java,
                        GitRebases::class.java,
                        GitMerges::class.java,
                        GitPulls::class.java,
                        GitRewords::class.java,
                        GitRemotes::class.java,
                        RemoteSourceCache::class.java,
                        GitPush::class.java,
                        GitReviews::class.java,
                        GitReviewSource::class.java,
                        GitLogView::class.java,
                        ProjectWindows::class.java,
                        SystemBrowser::class.java,
                        HttpClientSession::class.java,
                        HttpFileViewer::class.java,
                        HttpEnvironmentFileViewer::class.java,
                        SavedPassphrases::class.java,
                        ProjectDataStore::class.java,
                        RecentProjects::class.java,
                        LegacyRecentProjects::class.java,
                        ProjectPicker::class.java,
                        GitTrustCheck::class.java,
                        GradleTrustCheck::class.java,
                        GradleSession::class.java,
                        CommandRunSession::class.java,
                        ArgumentHistories::class.java,
                        GradleKotlinFileViewer::class.java,
                        GradleGroovyFileViewer::class.java,
                        ShellScriptFileViewer::class.java,
                        TrustedProjects::class.java,
                        LegacyTrustDecisions::class.java,
                        ScratchFiles::class.java,
                        Todos::class.java,
                        SavedColors::class.java,
                        DockerSettings::class.java,
                        DockerSettingsPage::class.java,
                        ComposeFileViewer::class.java,
                        DockerfileBuildViewer::class.java,
                        DockerBuildStore::class.java,
                        ConnectionStore::class.java,
                        PasswordCache::class.java,
                        QueryHistory::class.java,
                        DatabasesSettings::class.java,
                        DatabasesSettingsPage::class.java,
                        Databases::class.java,
                        Secrets::class.java,
                        Diagnostics::class.java,
                        SecretsSettingsPage::class.java,
                        MediaPreferences::class.java,
                        SoftWrap::class.java,
                        PlaybackCoordinator::class.java,
                        MediaFileViewer::class.java,
                        EpubFileViewer::class.java,
                        SpeechEngines::class.java,
                        KokoroEngine::class.java,
                        SpeechPreferences::class.java,
                        MinesweeperPreferences::class.java,
                        GameZoomPreferences::class.java,
                        WordGuessPreferences::class.java,
                        DoublesPreferences::class.java,
                        SudokuPreferences::class.java,
                        SolitairePreferences::class.java,
                        OrbitGuardPreferences::class.java,
                        BlockBreakerPreferences::class.java,
                        MiniGolfPreferences::class.java,
                        PipePuzzlePreferences::class.java,
                        ModelStore::class.java,
                        ModelInstaller::class.java,
                        SpeechSettingsPage::class.java,
                        OpusMtEngine::class.java,
                        TranslationEngine::class.java,
                        DownloadSizes::class.java,
                        LanguageDetector::class.java,
                        dev.sluide.localmodels.ModelInstaller::class.java,
                        TranslationPreferences::class.java,
                        TranslationSettingsPage::class.java,
                        TySpec::class.java,
                        TyUpdates::class.java,
                        PythonSettingsPage::class.java,
                        // Counted, not created: the spec needs TypeScriptInstaller and TypeScriptSettings, whose creation
                        // reads settings.properties. Its logic is in TypeScriptSpecTest.
                        TypeScriptSpec::class.java,
                        TypeScriptUpdates::class.java,
                        TypeScriptSettingsPage::class.java,
                        InstallPrompts::class.java,
                        CodeIntelligenceWiring::class.java,
                        EditorSettingsPage::class.java,
                        LibraryCodeExtension::class.java,
                        LibraryCodeSettings::class.java,
                        EncryptedFiles::class.java,
                        EncryptedFileViewer::class.java,
                        EncryptedFilesSettings::class.java,
                        EncryptedFilesSettingsPage::class.java,
                    )
                for (type in types) {
                    assertEquals(1, context.getBeanNamesForType(type).size, "bean of type ${type.simpleName}")
                }
                // The store is created with its migration beans (no cycle); creating beans does no I/O, so nothing
                // reads or writes ~/.sluide here (the migration runs on the store's first access).
                context.getBean(RecentProjects::class.java)
                context.getBean(TrustedProjects::class.java)
                // Two constructors: Spring takes the @Autowired one; creating it does not touch the secret store.
                context.getBean(Secrets::class.java)
                // Two constructors as well; creating it reads nothing (shared.json is read on first use).
                context.getBean(MediaPreferences::class.java)
                context.getBean(SoftWrap::class.java)
                // Takes the SettingsStore; reads settings.properties on first use only.
                context.getBean(LibraryCodeSettings::class.java)
                // The cheap speech beans; KokoroEngine and the speech settings page are left out (they read the
                // user's settings and the model), covered by the real-app check.
                context.getBean(ModelStore::class.java)
                context.getBean(ModelInstaller::class.java)
                context.getBean(SpeechPreferences::class.java)
                context.getBean(MinesweeperPreferences::class.java)
                context.getBean(GameZoomPreferences::class.java)
                context.getBean(WordGuessPreferences::class.java)
                context.getBean(DoublesPreferences::class.java)
                context.getBean(SudokuPreferences::class.java)
                context.getBean(SpeechEngines::class.java)
                // The translation beans are cheap: the engine loads a model on first use, Lingua on the first detection.
                context.getBean(OpusMtEngine::class.java)
                context.getBean(LanguageDetector::class.java)
                context.getBean(dev.sluide.localmodels.ModelInstaller::class.java)
                context.getBean(TranslationPreferences::class.java)
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
                        Reviews::class.java,
                        HttpClientSession::class.java,
                        GradleSession::class.java,
                        CommandRunSession::class.java,
                        ProjectTrust::class.java,
                        BackgroundTasks::class.java,
                        TodoPanel::class.java,
                        Coloring::class.java,
                        Codecs::class.java,
                        Converting::class.java,
                        ReadAloud::class.java,
                        Translate::class.java,
                        DockerSession::class.java,
                        Docker::class.java,
                        Databases::class.java,
                        Minesweeper::class.java,
                        WordGuess::class.java,
                        Doubles::class.java,
                        Sudoku::class.java,
                        Solitaire::class.java,
                        OrbitGuard::class.java,
                        BlockBreaker::class.java,
                        MiniGolf::class.java,
                        PipePuzzle::class.java,
                        CodeIntelligenceWiring::class.java,
                    )
                val scoped =
                    context.beanFactory.beanDefinitionNames.filter {
                        context.beanFactory.getBeanDefinition(it).scope == WINDOW_SCOPE
                    }
                assertEquals(
                    windowTypes.map { context.getBeanNamesForType(it).single() }.toSet(),
                    scoped.toSet(),
                )
                // The Converting tool gets the format families of all modules through its generic list parameter.
                val familiesParameter =
                    MethodParameter(
                        Converting::class.java.getConstructor(List::class.java, FontSettings::class.java),
                        0,
                    )
                val families =
                    context.autowireCapableBeanFactory.resolveDependency(
                        DependencyDescriptor(familiesParameter, true),
                        null,
                    )
                assertEquals(
                    "Data",
                    (families as List<*>).map { (it as FormatFamily<*>).name }.first(),
                    "the built-in family first",
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
                // Compose files get the Compose view by name and name pattern, other YAML files the YAML preview
                // (ADR 0119).
                val compose =
                    listOf("compose.yaml", "docker-compose.yml", "docker-compose.prod.yml", "compose.override.yaml")
                for (name in compose + "other.yml") dir.resolve(name).writeText("services: {}\n")
                for (name in compose) assertIs<ComposeFileViewer>(fileViewers.viewerFor(dir.resolve(name)), name)
                assertIs<YamlFileViewer>(fileViewers.viewerFor(dir.resolve("other.yml")))
                // Dockerfiles get the Build view; Dockerfile.<x> loses to the viewer for .x.
                val dockerfiles = listOf("Dockerfile", "Containerfile", "Dockerfile.dev", "app.Dockerfile")
                for (name in dockerfiles + "Dockerfile.md" + "Dockerfile.dockerignore") {
                    dir.resolve(name).writeText("FROM alpine\n")
                }
                for (name in dockerfiles) {
                    assertIs<DockerfileBuildViewer>(
                        fileViewers.viewerFor(dir.resolve(name)),
                        name,
                    )
                }
                assertIs<MarkdownFileViewer>(fileViewers.viewerFor(dir.resolve("Dockerfile.md")))
                assertIs<IgnoreFileViewer>(fileViewers.viewerFor(dir.resolve("Dockerfile.dockerignore")))
                // Encrypted files get their viewer by the last extension, which shows the inner type's content viewer
                // (ADR 0162); the file is binary, so no text viewer may take it.
                dir.resolve("notes.md.sluenc").writeBytes(byteArrayOf(0, 1, 2, -1))
                assertIs<EncryptedFileViewer>(fileViewers.viewerFor(dir.resolve("notes.md.sluenc")))
                assertIs<MarkdownFileViewer>(fileViewers.contentViewerFor("notes.md"))
                // An EPUB is a zip: only the ebook reader may take it, not the text viewer.
                dir.resolve("book.epub").writeBytes(byteArrayOf(80, 75, 3, 4, 0))
                assertIs<EpubFileViewer>(fileViewers.viewerFor(dir.resolve("book.epub")))
            }
    }
}
