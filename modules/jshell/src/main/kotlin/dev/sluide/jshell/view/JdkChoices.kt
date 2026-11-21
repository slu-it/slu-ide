package dev.sluide.jshell.view

import dev.sluide.java.Jdk
import dev.sluide.java.JdkSource
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellJdks
import java.nio.file.Path

/** One radio item of the JDK menu: [home] null is "Project JDK". */
internal data class JdkChoice(
    val label: String,
    val home: Path?,
    val selected: Boolean,
)

/** The texts of the JDK menu button, its items and the banners of a JShell view. */
internal object JdkChoices {
    private const val NONE = "No JDK with JShell"

    // the sources JShellJdks gives: its own choice and the descriptions of the project's chain
    private val SHORT_SOURCES =
        mapOf(
            JShellJdks.CHOSEN to "chosen",
            JdkSource.PROJECT_SETTING.description to "project setting",
            JdkSource.LOGIN_SHELL.description to "JAVA_HOME (shell)",
            JdkSource.ENVIRONMENT.description to "JAVA_HOME",
            JdkSource.THOREAU.description to "Thoreau",
        )

    /** `JDK 25 · project setting`; [NONE] when there is no JDK; `JDK…` while it is resolved. */
    fun buttonText(jdk: JShellJdk?): String =
        when {
            jdk == null -> "JDK…"
            jdk.jdk == null -> NONE
            else -> "JDK ${jdk.jdk.major} · ${shortSource(jdk.source)}"
        }

    /** The home path and the problem, if any. */
    fun tooltip(jdk: JShellJdk?): String =
        when {
            jdk == null -> "Looking for the JDK"
            jdk.jdk == null -> jdk.problem ?: NONE
            else -> listOfNotNull("JDK ${jdk.jdk.version} in ${jdk.jdk.home}", jdk.problem).joinToString("\n")
        }

    fun shortSource(source: String): String = SHORT_SOURCES[source] ?: source

    /** "Project JDK" first, then one item per installed JDK; the item of [remembered] (null: the first) is selected. */
    fun items(
        remembered: Path?,
        installed: List<Jdk>,
        project: JShellJdk,
    ): List<JdkChoice> =
        listOf(JdkChoice(projectLabel(project), null, remembered == null)) +
            installed.map { JdkChoice("JDK ${it.version} — ${it.home}", it.home, it.home == remembered) }

    private fun projectLabel(project: JShellJdk) =
        "Project JDK (${if (project.jdk == null) "none" else buttonText(project)})"

    /** The text of the banner above the source, null for none. */
    fun bannerText(jdk: JShellJdk?): String? =
        when {
            jdk == null -> null
            jdk.jdk == null -> "No JDK with JShell (JDK 9 or newer) was found."
            else -> jdk.problem
        }
}
