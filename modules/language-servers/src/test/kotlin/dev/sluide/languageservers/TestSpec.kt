package dev.sluide.languageservers

import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Path

internal class TestSpec(
    @Volatile var command: ServerCommand? = ServerCommand(Path.of("fake-server")),
    override val runsProjectCode: Boolean = false,
    override val trustCheckId: String? = null,
    private val config: (ConfigurationItem) -> Any? = { null },
    private val initOptions: Any? = null,
) : LanguageServerSpec {
    override val id = "test"
    override val name = "Test"

    override fun initializationOptions(root: Path) = initOptions

    override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

    override fun languageId(file: Path) = "python"

    override fun command() = command

    override fun configuration(item: ConfigurationItem) = config(item)
}
