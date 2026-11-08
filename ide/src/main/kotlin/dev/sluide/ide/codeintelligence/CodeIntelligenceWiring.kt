package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.WindowServices
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.ide.ProjectService
import dev.sluide.ide.WINDOW_SCOPE
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.TrustGate
import dev.sluide.projecttrust.ProjectTrust
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The language servers and the editors' code intelligence of one window (ADR 0142, ADR 0145): one server per language
 * spec (every `LanguageServerSpec` bean) for the window's project folder, started when a matching editor opens and
 * ended with the window (Spring closes window beans when the window's scope is destroyed, after
 * `MainWindow.dispose` closed the tabs).
 */
@Component
@Scope(WINDOW_SCOPE)
class CodeIntelligenceWiring(
    specs: List<LanguageServerSpec>,
    projectService: ProjectService,
    projectTrust: ProjectTrust,
    fileWatcher: FileWatcher,
    prompts: InstallPrompts,
) : AutoCloseable {
    /** The window's servers; servers trust-gate on the window's project trust (ADR 0053). */
    val languageServers =
        LanguageServers(
            specs,
            projectFolder = { projectService.projectFolder.get() },
            trustGate = TrustGate { checkId, cancelled -> projectTrust.awaitAllows(checkId, cancelled) },
            fileWatcher = fileWatcher,
        )

    private val codeIntelligence = CodeIntelligence(languageServers, prompts)

    /** The extension that binds the window's code editors to the servers. */
    val editorExtension: CodeEditorExtension get() = codeIntelligence

    /** Registers the servers as a window service, for the views of other modules (ADR 0070). */
    fun register(services: WindowServices) = services.register(LanguageServers::class.java, languageServers)

    /** Unbinds the editors and ends the servers. */
    override fun close() {
        codeIntelligence.close()
        languageServers.close()
    }
}
