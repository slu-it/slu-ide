package dev.sluide.dockersandbox

/** What can be started in a Docker sandbox: the menu [title] and the `sbx` [command] run in the project folder. */
enum class SandboxAction(
    val title: String,
    internal val command: String,
    /** A file the project folder must contain, or `null`. */
    internal val requiredFile: String?,
) {
    CLAUDE_CODE("Claude Code", "sbx run claude .", null),
    ENVIRONMENT("Environment", "sbx env run", "sbxenv.yaml"),
}
