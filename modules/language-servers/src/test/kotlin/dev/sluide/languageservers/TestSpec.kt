package dev.sluide.languageservers

import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Path

internal class TestSpec(
    @Volatile var command: ServerCommand? = ServerCommand(Path.of("fake-server")),
    /** The command for a window folder; `null` keeps the default ([command]). */
    private val commandFor: ((projectFolder: Path) -> ServerCommand?)? = null,
    override val runsProjectCode: Boolean = false,
    override val trustCheckId: String? = null,
    /** The trust checks; `null` keeps the default (the [trustCheckId] alone). */
    private val trustChecks: List<String>? = null,
    private val config: (ConfigurationItem) -> Any? = { null },
    private val initOptions: Any? = null,
    override val id: String = "test",
    /** The workspace folder of a file in a project folder; `null` keeps the default (the window folder). */
    private val folders: ((file: Path, projectFolder: Path) -> Path?)? = null,
    private val watched: (folder: Path) -> List<Path> = { emptyList() },
    private val affects: (change: Path) -> Boolean = { false },
    /** Whether the gate is asked; [gates] answers it. */
    override val gatesDocuments: Boolean = false,
    private val gates: (file: Path, projectFolder: Path) -> DocumentGate = { _, _ -> DocumentGate.SERVED },
    private val keeps: (file: Path) -> Boolean = { false },
    override val workspaceFolderMode: WorkspaceFolderMode = WorkspaceFolderMode.WINDOW_FIRST,
    /** The session extension; `null` keeps the default (none). */
    private val extension: ((projectFolder: Path, access: SessionAccess) -> SessionExtension?)? = null,
) : LanguageServerSpec {
    override val name = "Test"

    override val trustCheckIds: List<String> get() = trustChecks ?: super.trustCheckIds

    override fun initializationOptions(root: Path) = initOptions

    override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

    override fun languageId(file: Path) = "python"

    override fun command() = command

    override fun command(projectFolder: Path) = commandFor?.invoke(projectFolder) ?: super.command(projectFolder)

    override fun configuration(item: ConfigurationItem) = config(item)

    override fun workspaceFolder(
        file: Path,
        projectFolder: Path,
    ): Path? = folders?.invoke(file, projectFolder) ?: super.workspaceFolder(file, projectFolder)

    override fun watchedFolders(folder: Path) = watched(folder)

    override fun affectsWorkspaceFolders(change: Path) = affects(change)

    override fun gate(
        file: Path,
        projectFolder: Path,
    ) = gates(file, projectFolder)

    override fun keepsDiagnostics(file: Path) = keeps(file)

    override fun sessionExtension(
        projectFolder: Path,
        access: SessionAccess,
    ): SessionExtension? = extension?.invoke(projectFolder, access) ?: super.sessionExtension(projectFolder, access)
}
