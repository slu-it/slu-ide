package dev.sluide.dockersandbox

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** What can be started in a Docker sandbox: the menu [title] and the `sbx` [command] run in the project folder. */
enum class SandboxAction(
    val title: String,
    internal val command: String,
    /** A file the project folder must contain, or `null`. */
    internal val requiredFile: String?,
) {
    CLAUDE_CODE("Claude Code", "sbx run claude .", null),
    ENVIRONMENT("Environment", "sbx env run", ENVIRONMENT_FILE),
    ;

    companion object {
        /** The action the menu offers for [folder]: [ENVIRONMENT] if it has a regular `sbxenv.yaml`, else [CLAUDE_CODE]. */
        fun forFolder(folder: Path?): SandboxAction =
            if (folder != null && folder.resolve(ENVIRONMENT_FILE).isRegularFile()) ENVIRONMENT else CLAUDE_CODE
    }
}

private const val ENVIRONMENT_FILE = "sbxenv.yaml"
