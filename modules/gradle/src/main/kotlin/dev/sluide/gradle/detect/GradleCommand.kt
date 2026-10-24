package dev.sluide.gradle.detect

import dev.sluide.commandruns.ShellWords.quote
import dev.sluide.commandruns.ShellWords.tokenize
import java.nio.file.Path

/** Builds the command strings run by `$SHELL -l -i -c` (ADR 0085, ADR 0087). */
internal object GradleCommand {
    /** The program of [installation]: `<folder>/gradlew`, `sh <folder>/gradlew` or `gradle`. */
    fun executable(installation: GradleInstallation): String =
        when (installation) {
            is GradleInstallation.Wrapper -> {
                val gradlew =
                    quote(
                        installation.folder
                            .toAbsolutePath()
                            .normalize()
                            .resolve("gradlew")
                            .toString(),
                    )
                if (installation.executable) gradlew else "sh $gradlew"
            }

            GradleInstallation.Global -> {
                "gradle"
            }

            GradleInstallation.None -> {
                throw IllegalArgumentException("No Gradle to run")
            }
        }

    /**
     * The command running [tasks] with the [userArguments] (tokenized like a shell does: spaces separate, quotes
     * group); throws [IllegalArgumentException] with a message to show if a quote is not closed.
     */
    fun run(
        installation: GradleInstallation,
        userArguments: String,
        tasks: List<String>,
    ): String = (listOf(executable(installation)) + (tokenize(userArguments) + tasks).map(::quote)).joinToString(" ")

    /** The command writing the task list of the build to [output] with the [initScript]. */
    fun loadTasks(
        installation: GradleInstallation,
        initScript: Path,
        output: Path,
    ): String =
        listOf(
            executable(installation),
            "--init-script",
            quote(initScript.toAbsolutePath().toString()),
            quote("-Dsluide.taskList=${output.toAbsolutePath()}"),
            "-Dorg.gradle.configuration-cache=false",
            "sluideTaskList",
        ).joinToString(" ")
}
