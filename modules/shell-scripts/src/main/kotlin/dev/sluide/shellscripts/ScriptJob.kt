package dev.sluide.shellscripts

import dev.sluide.commandruns.RunJob

/**
 * A run of the script [fileName] with the [arguments] as the user typed them (split into words): the default texts of
 * a [RunJob] name it by the file and the arguments, e.g. `▶ deploy.sh --dry-run — running 0:02`.
 */
internal data class ScriptJob(
    val fileName: String,
    val arguments: List<String>,
) : RunJob {
    override val label: String = (listOf(fileName) + arguments).joinToString(" ")

    override val backgroundName: String get() = "Script: $label"

    override fun closeWarning(fileName: String): String = "$fileName is still running."
}
