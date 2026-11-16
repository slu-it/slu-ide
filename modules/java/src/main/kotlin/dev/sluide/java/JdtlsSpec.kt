package dev.sluide.java

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.trust.MavenTrustCheck
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LibraryDocument
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.SessionAccess
import dev.sluide.languageservers.SessionExtension
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.librarycode.LibraryCode
import dev.sluide.settings.ProjectSettingsOpeners
import javafx.application.Platform
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = System.getLogger(JdtlsSpec::class.java.name)

/**
 * Java through jdtls (ADR 0179): one server per window with a data folder per project, a workspace folder per build
 * root announced as its files open, the settings jdtls needs, one restart with the Lombok agent, `jdt:` library
 * classes in the cache, and the notification for build files jdtls does not watch ([JavaSession]).
 */
@Component
class JdtlsSpec internal constructor(
    override val installer: JdtlsInstaller,
    private val settings: JavaSettings,
    private val projectSettings: JavaProjectSettings,
    private val workspaceUse: JavaWorkspaceUse,
    private val javaHomes: JavaHomes,
    private val workspaces: JavaWorkspaces,
    private val projectSettingsOpeners: ProjectSettingsOpeners,
    private val outputIgnoreChecks: OutputIgnoreChecks,
    private val classesRoot: Path,
    private val dispatch: (Runnable) -> Unit,
    private val debounceMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : LanguageServerSpec {
    @Autowired
    constructor(
        installer: JdtlsInstaller,
        settings: JavaSettings,
        projectSettings: JavaProjectSettings,
        workspaceUse: JavaWorkspaceUse,
        projectSettingsOpeners: ProjectSettingsOpeners,
        outputIgnoreChecks: OutputIgnoreChecks,
    ) : this(
        installer,
        settings,
        projectSettings,
        workspaceUse,
        JavaHomes(),
        JavaWorkspaces(),
        projectSettingsOpeners,
        outputIgnoreChecks,
        LibraryCode.JAVA_CLASSES_ROOT,
        Platform::runLater,
        DEBOUNCE_MILLIS,
    )

    override val id = "java"

    override val name = "Java"

    override fun matches(file: Path): Boolean = file.fileName?.toString()?.endsWith(".java", ignoreCase = true) == true

    override fun languageId(file: Path) = "java"

    override val workspaceFolderMode = WorkspaceFolderMode.ROOTS_ONLY

    override val runsProjectCode = true

    override val trustCheckIds: List<String> = listOf("gradle", MavenTrustCheck.ID)

    /** Only whether jdtls is available: the real command is built per window by [command]. */
    override fun command(): ServerCommand? = installer.command()

    /**
     * The command of the window's jdtls: Thoreau's java with the window's data folder and a copy of jdtls's config,
     * the Gradle init script written beside them, the memory setting and, once the window restarted for Lombok, the
     * agent. Null when jdtls is not installed or not usable. Off the FX thread.
     */
    override fun command(projectFolder: Path): ServerCommand? {
        // First: the page said "Cleaned" when it asked, whether or not jdtls can start.
        states[key(projectFolder)]?.let { cleanBeforeStart(projectFolder, it) }
        val home = installer.executable()?.let(JdtlsLaunch::home) ?: return null
        val config = JdtlsLaunch.configFolder(home) ?: return null
        val prepared =
            try {
                workspaces.prepare(projectFolder, config, home.fileName.toString()).also {
                    GradleInitScript.writeInto(it.folder)
                }
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "jdtls's workspace of $projectFolder could not be prepared", e)
                return null
            }
        val lombok = installer.lombokJar().takeIf { states[key(projectFolder)]?.lombokRestarted?.get() == true }
        return JdtlsLaunch.command(home, prepared.config, prepared.data, settings.currentMaxMemoryMb(), lombok)
    }

    /** Whether a jdtls of some window uses the workspace folder of [windowFolder]; any thread. */
    fun workspaceRuns(windowFolder: Path): Boolean = workspaceUse.isInUse(workspaces.folderFor(windowFolder))

    /**
     * Clean Java Workspace while jdtls runs: the next start of the window's server deletes the data folder, and the
     * server restarts now. The lifecycle calls [command] for the new start only after the old process ended (ADR 0142),
     * so nothing uses the folder then; FX thread.
     */
    fun cleanRunning(windowFolder: Path) {
        stateOf(windowFolder).cleanPending.set(true)
        session(windowFolder)?.restart()
    }

    /**
     * Clean Java Workspace while no jdtls runs: deletes the data folder; off the FX thread. A window that closed just
     * now may still have its jdtls exiting for a few seconds (known limit, ADR 0179).
     */
    @Throws(IOException::class)
    fun cleanStopped(windowFolder: Path) = workspaces.deleteData(windowFolder)

    /**
     * The delete a pending clean does before a start; the old process of the lifecycle is gone by then. The flag is
     * taken before the delete, so a Clean requested meanwhile stays pending for the next start. A failed delete is only
     * logged: jdtls starts on what is left.
     */
    private fun cleanBeforeStart(
        projectFolder: Path,
        state: JavaWindowState,
    ) {
        if (!state.cleanPending.compareAndSet(true, false)) return
        try {
            workspaces.deleteData(projectFolder)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "jdtls's data of $projectFolder could not be deleted", e)
        }
    }

    override fun workspaceFolder(
        file: Path,
        projectFolder: Path,
    ): Path? {
        val state = stateOf(projectFolder)
        val absolute = file.toAbsolutePath().normalize()
        val build = JavaBuilds.buildRoot(absolute, projectFolder)
        if (build == null) {
            state.kotlinNear.remove(absolute)
            state.fileRoots.remove(absolute)
            return null
        }
        state.tools[build.root] = build.tool
        state.fileRoots[absolute] = build.root
        state.kotlinNear[absolute] = JavaBuilds.kotlinNear(absolute, build.root)
        return build.root
    }

    override fun affectsWorkspaceFolders(change: Path): Boolean {
        val absolute = change.toAbsolutePath().normalize()
        val buildFile = JavaBuilds.isBuildFile(absolute)
        if (buildFile) {
            // Every window's session: each ignores paths outside the roots it announced.
            sessions.values.forEach { it.buildFileChanged(absolute) }
        }
        return buildFile
    }

    /** The server's `settings`, also for a JDK change; resolves the JDK, which blocks: off the FX thread. */
    private fun settingsOf(windowFolder: Path): Map<String, Any?> {
        val resolved = javaHomes.resolve(projectSettings.jdk(windowFolder))
        stateOf(windowFolder).jdk = resolved
        val script = workspaces.folderFor(windowFolder).resolve(GradleInitScript.FILE_NAME)
        return JdtlsSettings.of(resolved?.jdk, script)
    }

    override fun initializationOptions(root: Path): Any? =
        mapOf(
            "extendedClientCapabilities" to
                mapOf("classFileContentsSupport" to true, "progressReportProvider" to false),
            "settings" to settingsOf(root),
        )

    /** jdtls asks only for `java.format.*`, which Thoreau does not configure. */
    override fun configuration(item: ConfigurationItem): Any? = null

    override fun libraryDocument(uri: String): LibraryDocument? = JdtLibraryDocuments.of(uri, classesRoot)

    override fun workspaceStatus(
        file: Path,
        folder: Path?,
        projectFolder: Path,
    ): WorkspaceStatus? {
        val state = states[key(projectFolder)]
        val jdk = state?.jdk
        val jdkLines = jdkLines(jdk)
        if (folder == null) {
            val text = jdk?.let { "No Java build · JDK ${it.jdk.major}" } ?: "No Java build"
            return WorkspaceStatus(text, null, (listOf("No build: JDK classes only") + jdkLines).joinToString("\n"))
        }
        val tool =
            when (state?.tools?.get(folder)) {
                BuildTool.GRADLE -> " · Gradle"
                BuildTool.MAVEN -> " · Maven"
                null -> ""
            }
        val imported =
            when (val import = state?.imports?.get(folder)) {
                is ImportState.Done -> import.compliance?.let { " · Java $it" }.orEmpty()
                is ImportState.Importing -> " · importing…"
                is ImportState.Failed -> " · import failed"
                null -> ""
            }
        val kotlin = kotlinNear(projectFolder, file)
        val kotlinText = if (kotlin) " · Kotlin sources not visible" else ""
        val text = "Java project: ${labelOf(folder, projectFolder)}$tool$imported$kotlinText"
        val lines = listOf(folder.toString()) + jdkLines + listOfNotNull(KOTLIN_TOOLTIP.takeIf { kotlin })
        return WorkspaceStatus(text, folder, lines.joinToString("\n"))
    }

    private fun jdkLines(resolved: ProjectJdk?): List<String> =
        if (resolved == null) {
            emptyList()
        } else {
            listOfNotNull(
                "JDK ${resolved.jdk.version}: ${resolved.jdk.home} (${resolved.source.description})",
                resolved.problem,
            )
        }

    /** The root relative to the window folder, or the folder's own name when the root is the window folder. */
    private fun labelOf(
        root: Path,
        projectFolder: Path,
    ): String =
        if (root == projectFolder) {
            projectFolder.fileName?.toString() ?: projectFolder.toString()
        } else {
            projectFolder.relativize(root).toString()
        }

    override fun environmentProblem(
        file: Path,
        projectFolder: Path,
    ): EnvironmentProblem? {
        val state = states[key(projectFolder)] ?: return null
        val root = state.fileRoots[file.toAbsolutePath().normalize()] ?: return null
        val label = labelOf(root, projectFolder)
        return when (val import = state.imports[root]) {
            is ImportState.Importing -> {
                val tool = if (state.tools[root] == BuildTool.GRADLE) "Gradle" else "Maven"
                val elapsed = Duration.between(import.since, clock.instant()).coerceAtLeast(Duration.ZERO)
                val time = "%d:%02d".format(Locale.ROOT, elapsed.toMinutes(), elapsed.toSecondsPart())
                EnvironmentProblem(root, "Importing $label ($tool)… $time", emptyList(), dismissible = false)
            }

            is ImportState.Failed -> {
                EnvironmentProblem(root, "Import of $label failed: ${import.detail}", import.actions)
            }

            is ImportState.Done -> {
                outputOffers[key(projectFolder)]?.problem(root)
            }

            null -> {
                null
            }
        }
    }

    override fun sessionExtension(
        projectFolder: Path,
        access: SessionAccess,
    ): SessionExtension {
        val folder = key(projectFolder)
        val state = stateOf(folder)
        val scheduler = NamedThreads.scheduled("sluide-java-debounce")
        val worker = NamedThreads.single("sluide-java-session")
        val offers =
            OutputIgnoreOffers(
                folder,
                outputIgnoreChecks,
                { root -> state.tools[root] },
                { projectSettings.outputIgnoreDeclined(folder) },
                { projectSettings.declineOutputIgnore(folder) },
                worker,
                dispatch,
                access::workspaceChanged,
            )
        outputOffers[folder] = offers
        val imports =
            JavaImports(
                access,
                state.imports,
                worker,
                dispatch,
                clock,
                { task ->
                    val future = scheduler.scheduleWithFixedDelay({ dispatch(task) }, 1, 1, TimeUnit.SECONDS)
                    AutoCloseable { future.cancel(false) }
                },
                { state.jdk?.jdk?.version },
                { root -> failureActions(folder, access, root) },
                outputs = offers,
            )
        val session =
            JavaSession(
                folder,
                access,
                state,
                { settingsOf(folder) },
                installer::lombokJar,
                JavaBuilds::usesLombok,
                projectSettings,
                workspaceUse.acquire(workspaces.folderFor(folder)),
                worker,
                scheduler,
                dispatch,
                debounceMillis,
                imports,
            ) {
                sessions.remove(folder)
                states.remove(folder)
                outputOffers.remove(folder)
            }
        sessions[folder] = session
        return session
    }

    /** The buttons of a failed import's banner: Retry, the log and the JDK choice. */
    private fun failureActions(
        windowFolder: Path,
        access: SessionAccess,
        root: Path,
    ): List<ProblemAction> =
        listOf(
            ProblemAction.Callback("Retry Import") { access.refreshFolder(root) },
            ProblemAction.OpenFile("Show Log", workspaces.folderFor(windowFolder).resolve("data/.metadata/.log")),
            ProblemAction.Callback("Choose JDK…") { projectSettingsOpeners.show(windowFolder, listOf("Java")) },
        )

    /** The Java session of the window with [windowFolder], if its server was started with it; FX thread. */
    fun session(windowFolder: Path): JavaSession? = sessions[key(windowFolder)]

    /** Whether Kotlin sources sit next to [file] as `workspaceFolder` found when it last asked; false if it never did. */
    internal fun kotlinNear(
        windowFolder: Path,
        file: Path,
    ): Boolean = states[key(windowFolder)]?.kotlinNear?.get(file.toAbsolutePath().normalize()) == true

    /** The JDK `initializationOptions` or a JDK change resolved last for the window; null before. */
    internal fun jdkOf(windowFolder: Path): ProjectJdk? = states[key(windowFolder)]?.jdk

    /** The per-window state, for [JavaSession]; created on first use. */
    internal fun stateOf(windowFolder: Path): JavaWindowState = states.getOrPut(key(windowFolder)) { JavaWindowState() }

    private val states = ConcurrentHashMap<Path, JavaWindowState>()
    private val sessions = ConcurrentHashMap<Path, JavaSession>()
    private val outputOffers = ConcurrentHashMap<Path, OutputIgnoreOffers>()

    private fun key(folder: Path) = folder.toAbsolutePath().normalize()

    private companion object {
        const val DEBOUNCE_MILLIS = 1000L

        const val KOTLIN_TOOLTIP =
            "Kotlin sources in this build aren't visible to jdtls; references to them show as errors."
    }
}
