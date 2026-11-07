package dev.sluide.python

import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.ServerCommand
import org.eclipse.lsp4j.ConfigurationItem
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.Locale

/** The file extensions ty handles: sources, stubs and windowed scripts (scripts known only by a shebang are not). */
private val PYTHON_EXTENSIONS = setOf("py", "pyi", "pyw")

/**
 * Python through ty (ADR 0144). ty finds the project's virtual environment by itself (`.venv` in the project folder,
 * `VIRTUAL_ENV`, Conda, `python3` on the PATH) and runs no program of the project, so it needs no trust check.
 */
@Component
class TySpec(
    override val installer: TyInstaller,
) : LanguageServerSpec {
    override val id = "python"

    override val name = "Python"

    override fun matches(file: Path): Boolean =
        file.fileName
            ?.toString()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT) in PYTHON_EXTENSIONS

    override fun languageId(file: Path) = "python"

    override fun command(): ServerCommand? = installer.command()

    /** Nothing configured: an empty object for every section (servers like Pyrefly reject `null`). */
    override fun configuration(item: ConfigurationItem): Any = emptyMap<String, Any?>()
}
