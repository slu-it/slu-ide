package dev.sluide.java

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.java.trust.MavenTrustCheck
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LibraryDocument
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.SessionAccess
import dev.sluide.languageservers.SessionExtension
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.WorkspaceStatus
import dev.sluide.librarycode.LibraryCode
import javafx.application.Platform
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
    private val classesRoot: Path,
    private val dispatch: (Runnable) -> Unit,
    private val debounceMillis: Long,
) : LanguageServerSpec {
    @Autowired
    constructor(
        installer: JdtlsInstaller,
        settings: JavaSettings,
        projectSettings: JavaProjectSettings,
        workspaceUse: JavaWorkspaceUse,
    ) : this(
        installer,
        settings,
        projectSettings,
        workspaceUse,
        JavaHomes(),
        JavaWorkspaces(),
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

    override fun workspaceFolder(
        file: Path,
        projectFolder: Path,
    ): Path? {
        val state = stateOf(projectFolder)
        val absolute = file.toAbsolutePath().normalize()
        val build = JavaBuilds.buildRoot(absolute, projectFolder)
        if (build == null) {
            state.kotlinNear.remove(absolute)
            return null
        }
        state.tools[build.root] = build.tool
        state.kotlinNear[absolute] = JavaBuilds.kotlinNear(absolute, build.root)
        return build.root
    }

    override fun affectsWorkspaceFolders(change: Path): Boolean {
        val absolute = change.toAbsolutePath().normalize()
        if (absolute.fileName?.toString() in SESSION_NOTIFIED) {
            // Every window's session: each ignores paths outside the roots it announced.
            sessions.values.forEach { it.buildFileChanged(absolute) }
        }
        return JavaBuilds.isBuildFile(absolute)
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
        if (folder == null) return WorkspaceStatus("No Java build", null, null)
        val label =
            if (folder == projectFolder) {
                projectFolder.fileName?.toString() ?: projectFolder.toString()
            } else {
                projectFolder.relativize(folder).toString()
            }
        val tool =
            when (states[key(projectFolder)]?.tools?.get(folder)) {
                BuildTool.GRADLE -> " · Gradle"
                BuildTool.MAVEN -> " · Maven"
                null -> ""
            }
        return WorkspaceStatus("Java project: $label$tool", folder, folder.toString())
    }

    override fun environmentProblem(
        file: Path,
        projectFolder: Path,
    ): EnvironmentProblem? = null

    override fun sessionExtension(
        projectFolder: Path,
        access: SessionAccess,
    ): SessionExtension {
        val folder = key(projectFolder)
        val session =
            JavaSession(
                folder,
                access,
                stateOf(folder),
                { settingsOf(folder) },
                installer::lombokJar,
                JavaBuilds::usesLombok,
                projectSettings,
                workspaceUse.acquire(workspaces.folderFor(folder)),
                NamedThreads.single("sluide-java-session"),
                NamedThreads.scheduled("sluide-java-debounce"),
                dispatch,
                debounceMillis,
            ) {
                sessions.remove(folder)
                states.remove(folder)
            }
        sessions[folder] = session
        return session
    }

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

    private fun key(folder: Path) = folder.toAbsolutePath().normalize()

    private companion object {
        const val DEBOUNCE_MILLIS = 1000L

        /** The files jdtls does not watch, whose change the window's session tells it about. */
        val SESSION_NOTIFIED = setOf("settings.gradle", "settings.gradle.kts", "gradle-wrapper.properties")
    }
}
