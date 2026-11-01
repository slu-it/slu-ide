package dev.sluide.docker.cli

import dev.sluide.docker.model.EngineFlavor
import dev.sluide.terminal.loginShellPrints
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private val log = System.getLogger(DockerExecutable::class.java.name)

/** The executable that the settings name by default. */
internal const val DEFAULT_DOCKER_EXECUTABLE = "docker"

/**
 * The variables of the login shell that reach the CLI: its `PATH` (credential helpers, `docker-compose`), and where
 * the engine is (Docker contexts and hosts, Podman connections, the rootless socket's runtime folder).
 */
internal val CAPTURED_VARIABLES =
    listOf(
        "PATH",
        "DOCKER_HOST",
        "DOCKER_CONTEXT",
        "DOCKER_CONFIG",
        "DOCKER_CERT_PATH",
        "DOCKER_TLS_VERIFY",
        "CONTAINER_HOST",
        "CONTAINER_CONNECTION",
        "XDG_RUNTIME_DIR",
    )

private const val ENVIRONMENT_SEPARATOR = "--sluide-environment--"

/**
 * The CLI to run: an absolute path and the environment to run it with.
 *
 * @property flavor a guess from the executable's name (`podman…`); the engine's
 *   [EngineInfo.flavor][dev.sluide.docker.model.EngineInfo] tells for sure.
 */
data class ResolvedDocker(
    val executable: Path,
    val environment: Map<String, String>,
    val flavor: EngineFlavor = flavorOf(executable),
)

/** What looking the executable up found. */
internal sealed interface DockerLookup {
    data class Found(
        val docker: ResolvedDocker,
    ) : DockerLookup

    /** Not found or not usable; [message] tells the user what to do. */
    data class NotFound(
        val message: String,
    ) : DockerLookup
}

/**
 * What the login shell printed.
 *
 * @property path the absolute path `command -v` printed, if any.
 * @property alias what `command -v` printed instead for an alias, function or builtin (`alias docker=podman`, or just
 *   the name), if it did.
 * @property environment the [CAPTURED_VARIABLES] the shell has set; `null` if it printed no environment at all.
 */
internal data class ShellLookup(
    val path: String?,
    val alias: String?,
    val environment: Map<String, String>?,
)

/**
 * Finds the executable of the Docker settings once per window, as a terminal would (ADR 0117): a plain name (`docker`,
 * `podman`) through `command -v` in the user's interactive login shell, an absolute path as it is. Either way the
 * shell's [CAPTURED_VARIABLES] are taken over, so the CLI, run directly afterwards, reaches the same engine as in a
 * terminal. Blocks for as long as the login shell starts: not on the FX thread.
 */
internal class DockerExecutable(
    private val shell: (script: String, environment: Map<String, String>) -> String? = { script, environment ->
        loginShellPrints(script, environment)
    },
    private val isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
) {
    /** Looks up [setting] (blank: `docker`) for an IDE started with [environment]. */
    fun resolve(
        setting: String,
        environment: Map<String, String>,
    ): DockerLookup {
        val value = setting.trim().ifEmpty { DEFAULT_DOCKER_EXECUTABLE }
        val plainName = '/' !in value && '\\' !in value && !value.startsWith("~")
        val output = shell(lookupScript(value.takeIf { plainName }), environment)
        if (output == null) log.log(System.Logger.Level.WARNING, "The login shell did not answer the lookup of $value")
        return resolution(value, output?.let { parseLookup(it, value) }, environment, isExecutable)
    }
}

/** Connects to the CLI the [setting] names: [DockerExecutable] and then a [ProcessDockerCli] for what it found. */
internal fun connectDocker(
    setting: String,
    environment: Map<String, String>,
): DockerConnection =
    when (val lookup = DockerExecutable().resolve(setting, environment)) {
        is DockerLookup.Found -> DockerConnection.Ready(lookup.docker, ProcessDockerCli(lookup.docker))
        is DockerLookup.NotFound -> DockerConnection.NotFound(lookup.message)
    }

/** A CLI to talk to, or why there is none. */
internal sealed interface DockerConnection {
    data class Ready(
        val docker: ResolvedDocker,
        val cli: DockerCli,
    ) : DockerConnection

    data class NotFound(
        val message: String,
    ) : DockerConnection
}

/** The script that prints `command -v` of [name] (none for a path) and the environment, separated by a line. */
internal fun lookupScript(name: String?): String =
    listOfNotNull(
        name?.let { "command -v ${shellQuoted(it)}" },
        "printf '%s\\n' '$ENVIRONMENT_SEPARATOR'",
        "env",
    ).joinToString("\n")

/** [value] in single quotes for sh, bash, zsh and fish. */
internal fun shellQuoted(value: String) = "'" + value.replace("'", "'\\''") + "'"

/**
 * Reads the output of [lookupScript] for [name]: before the separator, a line with an absolute path is what
 * `command -v` found; a line `alias …` (zsh, bash), `… aliased to …` or just the name (a function or builtin) is an
 * alias; anything else (csh's "command: Command not found.", other errors) means not found. After the separator, the
 * captured variables, first value of each.
 */
internal fun parseLookup(
    output: String,
    name: String,
): ShellLookup {
    val lines = output.lines()
    val separator = lines.indexOfFirst { it.trim() == ENVIRONMENT_SEPARATOR }
    val commandLines = if (separator < 0) lines else lines.subList(0, separator)
    val before = commandLines.map { it.trim() }.filter { it.isNotEmpty() }
    val environment =
        if (separator < 0) {
            null
        } else {
            val captured = linkedMapOf<String, String>()
            for (line in lines.subList(separator + 1, lines.size)) {
                val variable = line.substringBefore('=', "")
                val wanted = variable in CAPTURED_VARIABLES && variable !in captured
                if (wanted) captured[variable] = line.substringAfter('=')
            }
            captured
        }
    val path = before.firstOrNull { it.startsWith("/") }
    val alias =
        if (path != null) {
            null
        } else {
            before.firstOrNull { it.startsWith("alias ") || "aliased to" in it || it == name }
        }
    return ShellLookup(path, alias, environment)
}

/**
 * What [value] (a plain name or a path) resolves to, given what the login shell printed ([lookup], `null` if it did
 * not answer) and the IDE's [environment]. The CLI's environment is the IDE's with the shell's [CAPTURED_VARIABLES]
 * over it: those the shell has set non-empty take its value, the others are removed, so that a `DOCKER_HOST` the IDE
 * inherited but the shell no longer sets does not point the CLI elsewhere. If the shell printed no environment, the
 * IDE's is kept as it is.
 */
internal fun resolution(
    value: String,
    lookup: ShellLookup?,
    environment: Map<String, String>,
    isExecutable: (Path) -> Boolean,
): DockerLookup {
    val captured = lookup?.environment
    val fromShell = captured?.filterValues { it.isNotEmpty() }
    val merged = if (fromShell == null) environment else environment - CAPTURED_VARIABLES.toSet() + fromShell
    val plainName = '/' !in value && '\\' !in value && !value.startsWith("~")
    if (!plainName) {
        val path = pathOf(value, environment["HOME"])
        return when {
            path == null || !path.isAbsolute -> {
                DockerLookup.NotFound(
                    "\"$value\" is neither a program name nor a full path. Enter docker, podman or the full path " +
                        "of either in Settings → Docker.",
                )
            }

            !isExecutable(path) -> {
                DockerLookup.NotFound("$path is not an executable file. Check the path in Settings → Docker.")
            }

            else -> {
                DockerLookup.Found(ResolvedDocker(path, merged))
            }
        }
    }
    if (lookup == null) {
        // The login shell is broken or slow; the IDE's own PATH may still have it.
        val onIdePath = findOnPath(value, environment["PATH"], isExecutable)
        return if (onIdePath != null) {
            DockerLookup.Found(ResolvedDocker(onIdePath, environment))
        } else {
            DockerLookup.NotFound(
                "Your login shell did not answer, so $value could not be looked up. Enter the full path of the " +
                    "executable in Settings → Docker.",
            )
        }
    }
    val path = lookup.path?.let { pathOf(it, null) }
    return when {
        path != null && isExecutable(path) -> {
            DockerLookup.Found(ResolvedDocker(path, merged))
        }

        path != null -> {
            DockerLookup.NotFound(
                "$path is not an executable file. Enter the full path of the executable in Settings → Docker.",
            )
        }

        lookup.alias != null -> {
            DockerLookup.NotFound(
                "In your login shell, $value is an alias or a function (${lookup.alias}), not a program. Enter the " +
                    "full path of the executable in Settings → Docker.",
            )
        }

        else -> {
            DockerLookup.NotFound(
                "$value was not found on the PATH of your login shell. Install Docker or Podman, or enter the full " +
                    "path of the executable in Settings → Docker.",
            )
        }
    }
}

/** The flavor a CLI's file name suggests. */
internal fun flavorOf(executable: Path): EngineFlavor =
    if (executable.fileName?.toString()?.startsWith("podman") == true) EngineFlavor.PODMAN else EngineFlavor.DOCKER

private fun pathOf(
    text: String,
    home: String?,
): Path? =
    try {
        if (text == "~" || text.startsWith("~/")) home?.let { Path.of(it + text.removePrefix("~")) } else Path.of(text)
    } catch (_: InvalidPathException) {
        null
    }

private fun findOnPath(
    name: String,
    path: String?,
    isExecutable: (Path) -> Boolean,
): Path? =
    path
        ?.split(File.pathSeparatorChar)
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { folder -> pathOf(folder, null)?.resolve(name) }
        ?.firstOrNull { it.isAbsolute && isExecutable(it) }
