package dev.sluide.ide.jshell

import dev.sluide.editorarea.EditorArea
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.ide.ProjectService
import dev.sluide.ide.WINDOW_SCOPE
import dev.sluide.ide.isMac
import dev.sluide.ide.scratches.WindowScratches
import dev.sluide.java.JavaHomes
import dev.sluide.java.JavaProjectSettings
import dev.sluide.java.Jdk
import dev.sluide.java.Jdks
import dev.sluide.jshell.JShellEditorExtensions
import dev.sluide.jshell.JShellFileView
import dev.sluide.jshell.JShellJdks
import dev.sluide.jshell.JShellSession
import dev.sluide.jshell.JShellWindow
import dev.sluide.jshell.SessionEvent
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.ProjectSettingsOpeners
import javafx.stage.Stage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/** The JDK lookups are shared by all windows: the login shell is asked once. */
private val javaHomes by lazy { JavaHomes() }

/** The installed JDKs a JShell view can run on: [Jdks.installed] without the ones that have no JShell. Blocks. */
internal fun installedJdksWithJShell(
    userHome: Path,
    macOs: Boolean,
    systemFolders: List<Path> = Jdks.defaults,
): List<Jdk> = Jdks.installed(userHome, macOs, systemFolders).filter(Jdks::hasJShell)

/**
 * The JShell services of one window (ADR 0191): the window's [JShellWindow] for the `.jsh` views, built from the
 * window's folder, the project's data and JDK setting, and registered as a window service (ADR 0070). A change of the
 * window's project JDK setting reaches it; it is closed with the window (Spring closes window beans when the window's
 * scope is destroyed). The installed JDKs are looked for in [userHome] (and the system's folders).
 */
@Component
@Scope(WINDOW_SCOPE)
class JShellWiring internal constructor(
    projectService: ProjectService,
    projectData: ProjectDataStore,
    javaProjectSettings: JavaProjectSettings,
    projectSettingsOpeners: ProjectSettingsOpeners,
    userHome: Path,
    startSession: ((Jdk, (SessionEvent) -> Unit) -> JShellSession)? = null,
) : AutoCloseable {
    @Autowired
    constructor(
        projectService: ProjectService,
        projectData: ProjectDataStore,
        javaProjectSettings: JavaProjectSettings,
        projectSettingsOpeners: ProjectSettingsOpeners,
    ) : this(
        projectService,
        projectData,
        javaProjectSettings,
        projectSettingsOpeners,
        Path.of(System.getProperty("user.home")),
    )

    private val folder: () -> Path? = { projectService.projectFolder.get() }

    val window: JShellWindow =
        JShellWindow(
            JShellJdks(projectData, javaHomes, { folder()?.let(javaProjectSettings::jdk) }),
            { installedJdksWithJShell(userHome, isMac) },
            { folder()?.let { projectSettingsOpeners.show(it, listOf("Java")) } },
            startSession ?: JShellWindow.REAL_START_SESSION,
            canChooseProjectJdk = { folder() != null },
        )

    private val projectJdkChanges =
        javaProjectSettings.onChange { changed ->
            if (changed == folder()?.toAbsolutePath()?.normalize()) window.projectJdkChanged()
        }

    /**
     * Registers the window's [JShellWindow] for the views of other modules and adds "Evaluate Selection in JShell" to the
     * window's [CodeEditorExtensions], which must be registered already: it makes a scratch `selection.jsh` of the
     * selected text through [scratches], opens it and runs it all.
     */
    internal fun register(
        editorArea: EditorArea,
        stage: Stage,
        scratches: WindowScratches,
    ) {
        val services = editorArea.windowServices
        services.register(JShellWindow::class.java, window)
        checkNotNull(
            services.get(CodeEditorExtensions::class.java),
        ) { "the code editor extensions are registered first" }.add(
            JShellEditorExtensions.evaluateSelection { text ->
                val path = scratches.openText(stage, "selection", "jsh", text) ?: return@evaluateSelection
                (editorArea.viewOf(path) as? JShellFileView)?.runAll()
            },
        )
    }

    override fun close() = projectJdkChanges.close()
}
