package dev.sluide.codeintelligence.workspace

import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ObservableMap
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Path

/** One problem of a configuration file: where it is (0-based, as `openFile` takes it) and its first message line. */
internal data class ConfigProblem(
    val line: Int,
    val column: Int,
    val message: String,
) {
    /** The link's text: `3:7  Option 'x' has been removed.` (1-based). */
    val text: String get() = "${line + 1}:${column + 1}  $message"
}

/**
 * What the banner says about the problems of one configuration [file] (ADR 0160): the [title], the [listed] problems
 * (the first ones) of [all].
 */
internal data class ConfigProblems(
    val file: Path,
    val title: String,
    val listed: List<ConfigProblem>,
    val all: List<ConfigProblem>,
) {
    /** How many problems the banner does not list. */
    val more: Int get() = all.size - listed.size
}

/** The most problems of one file the banner lists. */
internal const val MAX_CONFIG_PROBLEMS = 10

/**
 * What a bound editor's configuration problems banner says (ADR 0160): for each of [configFiles] (the files whose
 * problems concern the document) the diagnostics the server pushed for it ([keptOf]), while the document is
 * [served]; nothing for a file without problems. Not Now ([notNow]) hides a file's banner in this window until its set
 * of problems changes (compared as a set). Recomputed when any of [changes] fires (kept diagnostics, workspace
 * changes) or [notNow] does. FX thread.
 *
 * @param specName the language's name for the title (`TypeScript`).
 * @param projectFolder the window's project folder, config files are named relative to it.
 * @param configFiles the spec's `configFiles` of the document.
 * @param keptOf the session's kept diagnostics of a file.
 * @param notNow the problems each config file's banner was put off for.
 */
internal class ConfigProblemsModel(
    private val specName: String,
    private val projectFolder: Path?,
    private val configFiles: () -> List<Path>,
    private val keptOf: (Path) -> List<Diagnostic>,
    private val served: () -> Boolean,
    changes: List<Observable>,
    private val notNow: ObservableMap<Path, List<ConfigProblem>>,
) : AutoCloseable {
    private val wrapper = ReadOnlyObjectWrapper(compute())

    /** The banners to show, one per config file with problems, empty for none. */
    val shown: ReadOnlyObjectProperty<List<ConfigProblems>> = wrapper.readOnlyProperty

    private val listener = InvalidationListener { wrapper.set(compute()) }
    private val observed: List<Observable> = changes + notNow

    init {
        observed.forEach { it.addListener(listener) }
    }

    /** Not Now of [problems]: hides it until the file's set of problems changes. */
    fun notNow(problems: ConfigProblems) {
        notNow[problems.file] = problems.all
    }

    private fun compute(): List<ConfigProblems> {
        if (!served()) return emptyList()
        return configFiles().distinct().mapNotNull { file ->
            val all =
                keptOf(file)
                    .mapNotNull(::problemOf)
                    .sortedWith(compareBy({ it.line }, { it.column }))
            if (all.isEmpty() || notNow[file]?.toSet() == all.toSet()) {
                null
            } else {
                ConfigProblems(file, titleOf(file, all.size), all.take(MAX_CONFIG_PROBLEMS), all)
            }
        }
    }

    /** [diagnostic] as a problem; `null` for one without a start position or a message (a malformed push). */
    private fun problemOf(diagnostic: Diagnostic): ConfigProblem? {
        val start = diagnostic.range?.start ?: return null
        val message = diagnostic.message ?: return null
        val text = message.left ?: message.right?.value ?: return null
        return ConfigProblem(start.line, start.character, firstLine(text))
    }

    private fun titleOf(
        file: Path,
        count: Int,
    ): String = "${nameOf(file)} has $count ${if (count == 1) "problem" else "problems"} for $specName:"

    private fun nameOf(file: Path): String {
        val project = projectFolder
        if (project != null && file.startsWith(project)) {
            return project.relativize(file).joinToString("/") { it.toString() }
        }
        return file.fileName.toString()
    }

    override fun close() = observed.forEach { it.removeListener(listener) }
}

private fun firstLine(message: String) =
    message
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
