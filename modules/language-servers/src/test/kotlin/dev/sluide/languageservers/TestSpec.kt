package dev.sluide.languageservers

import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Path

internal class TestSpec(
    @Volatile var command: ServerCommand? = ServerCommand(Path.of("fake-server")),
    override val runsProjectCode: Boolean = false,
    override val trustCheckId: String? = null,
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
) : LanguageServerSpec {
    override val name = "Test"

    override fun initializationOptions(root: Path) = initOptions

    override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

    override fun languageId(file: Path) = "python"

    override fun command() = command

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
}
