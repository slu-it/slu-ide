package dev.sluide.docker.dockerfile

/**
 * What the Build pane remembers for one Dockerfile (stored per file, see [DockerBuildStore]).
 *
 * @property tags the Tags field as typed: tags separated by spaces or commas ([tagsOf]).
 * @property history earlier texts of the Tags field of started builds, newest first, at most [MAX_TAG_HISTORY].
 * @property context the build context folder, relative to the Dockerfile's folder or absolute; empty: that folder.
 * @property target the stage to build (`--target`); empty: the last stage.
 * @property buildArgs the values of the build args by name, also of ARGs no longer in the file (they come back with
 *   the ARG); only those of the file's current ARGs are passed.
 * @property noCache flag `--no-cache`.
 * @property pull flag `--pull`.
 * @property platform `--platform` (`os/arch[/variant]`); empty: the engine's.
 */
internal data class BuildOptions(
    val tags: String = "",
    val history: List<String> = emptyList(),
    val context: String = "",
    val target: String = "",
    val buildArgs: Map<String, String> = emptyMap(),
    val noCache: Boolean = false,
    val pull: Boolean = false,
    val platform: String = "",
)

/** How many texts of the Tags field the history keeps. */
internal const val MAX_TAG_HISTORY = 10

/** The tags of the Tags field [text]: separated by spaces or commas, empty parts dropped. */
internal fun tagsOf(text: String): List<String> = text.split(TAG_SEPARATORS).filter { it.isNotEmpty() }

/** [history] with [text] (trimmed) on top, once, at most [MAX_TAG_HISTORY] long; unchanged for a blank [text]. */
internal fun withTagHistory(
    history: List<String>,
    text: String,
): List<String> {
    val entry = text.trim()
    if (entry.isEmpty()) return history
    return (listOf(entry) + history.filter { it != entry }).take(MAX_TAG_HISTORY)
}

private val TAG_SEPARATORS = Regex("""[\s,]+""")
