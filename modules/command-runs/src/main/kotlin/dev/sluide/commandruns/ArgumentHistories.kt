package dev.sluide.commandruns

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.SettingsStore
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path

private val log = System.getLogger(ArgumentHistories::class.java.name)

/** The settings key of [ArgumentHistories.expanded]. */
internal const val EXPANDED_KEY = "runs.history.expanded"

/** The field of a file's project data that holds its run arguments, newest first. */
internal const val ARGUMENTS_FIELD = "runArguments"

/** The number of arguments kept per file. */
internal const val MAX_ENTRIES = 20

/**
 * The run-argument histories of all files (ADR 0091) and whether the run panes show them expanded. A file's history
 * lives in its project data ([ProjectDataStore.updateFileData], field `runArguments`, newest first). FX thread.
 */
@Component
class ArgumentHistories(
    private val store: ProjectDataStore,
    private val settings: SettingsStore,
) {
    /**
     * Whether the history sections of all run panes are expanded; stored as `runs.history.expanded` (default true).
     * Read from the settings on first access; a change is stored, and a failure to store it logged.
     */
    val expanded: BooleanProperty by lazy {
        val stored = settings.load()[EXPANDED_KEY]?.trim()?.toBooleanStrictOrNull() ?: true
        SimpleBooleanProperty(stored).apply {
            addListener { _, _, value -> storeExpanded(value) }
        }
    }

    /** The run arguments of [file] in the project in [projectFolder], newest first. */
    fun entries(
        file: Path,
        projectFolder: Path?,
    ): List<String> = store.fileData(file, projectFolder)?.let(::read).orEmpty()

    /**
     * Puts [arguments] (trimmed) on top of the history of [file] and returns the history; blank arguments are not
     * recorded.
     */
    fun record(
        file: Path,
        projectFolder: Path?,
        arguments: String,
    ): List<String> {
        // Nothing to write; the store would create an empty file where there is none.
        if (arguments.isBlank()) return entries(file, projectFolder)
        return change(file, projectFolder) { recorded(it, arguments) }
    }

    /** Removes [entry] from the history of [file] and returns the history. */
    fun remove(
        file: Path,
        projectFolder: Path?,
        entry: String,
    ): List<String> = change(file, projectFolder) { removed(it, entry) }

    /** Applies [change] to the stored history (the store leaves an unchanged file alone); the history as stored after. */
    private fun change(
        file: Path,
        projectFolder: Path?,
        change: (List<String>) -> List<String>,
    ): List<String> {
        var next = emptyList<String>()
        val saved =
            store.updateFileData(file, projectFolder) { data ->
                next = change(read(data))
                if (next.isEmpty()) {
                    data.remove(ARGUMENTS_FIELD)
                } else {
                    data.putArray(ARGUMENTS_FIELD).apply { next.forEach(::add) }
                }
            }
        return if (saved) next else entries(file, projectFolder)
    }

    private fun read(data: ObjectNode): List<String> =
        (data.get(ARGUMENTS_FIELD) as? ArrayNode)
            ?.values()
            ?.filter { it.isString }
            ?.map { it.stringValue() }
            .orEmpty()

    private fun storeExpanded(value: Boolean) {
        try {
            settings.update(mapOf(EXPANDED_KEY to value.toString()))
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot store whether the run histories are expanded", e)
        }
    }
}

/** [entries] with [arguments] (trimmed) on top, once, at most [max] of them; [entries] if [arguments] is blank. */
internal fun recorded(
    entries: List<String>,
    arguments: String,
    max: Int = MAX_ENTRIES,
): List<String> {
    val text = arguments.trim()
    if (text.isEmpty()) return entries
    return (listOf(text) + entries.filter { it != text }).take(max)
}

/** [entries] without [entry]. */
internal fun removed(
    entries: List<String>,
    entry: String,
): List<String> = entries.filter { it != entry }
