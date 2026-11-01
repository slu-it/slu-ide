package dev.sluide.docker.compose

import dev.sluide.docker.cli.shellQuoted
import java.nio.file.Path

/** What `--env-file` gets to read nothing: it replaces the `.env` compose would read by itself. */
private val NULL_DEVICE = if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"

/** What the shell action runs in a service: bash if it has one, else sh (as the container Shell does). */
private const val SHELL_SCRIPT = "command -v bash >/dev/null 2>&1 && exec bash || exec sh"

/**
 * Builds the argument lists of the compose commands for one compose file (not shell strings: [commandLine] quotes them
 * for the login shell). The working directory of every command is the compose file's folder, which is compose's
 * project directory, so `--project-directory` is not needed. A command is `baseArgs(…) + <one of the verbs>`.
 *
 * Values that reach the command line are checked so that nothing can pose as a flag: the files go in as absolute
 * paths, project and profile names must be valid names (see [isValidProjectName], [isValidProfileName]) and services
 * must not start with `-`; otherwise [IllegalArgumentException]. [ComposeStore.load] already drops invalid values.
 *
 * Which base which verb gets: Up, Build, Pull and a project-wide Restart with the chosen profiles (`baseArgs`);
 * [downArgs] with every profile (`--profile '*'`), as a project-wide Down must also remove the containers of a profile
 * that was chosen at Up and unchosen since; the listing (`config`) with every profile (`allProfiles = true`) to see all services; the verbs of
 * single services (`upService`, start, stop, restart, logs, shell) work whatever profiles are chosen, because compose
 * enables the profile of a service named on the command line, so they take `baseArgs` too.
 */
object ComposeCommands {
    /**
     * `compose -f <file> [-f <override>] [-p <name>] [--env-file <f>]… [--profile <p>]…`, the arguments every compose
     * command starts with (after the executable), profiles sorted.
     *
     * Env files: [ComposeOptions.envFiles] `null` passes none, as compose finds the `.env` in the project directory
     * (the file's folder) itself. A given list passes one `--env-file` per entry, resolved against the file's folder,
     * and compose then no longer reads the `.env` unless it is in the list; an empty list reads nothing at all (the
     * null device is passed).
     *
     * With [allProfiles] `--profile '*'` replaces the profiles, so that `config` lists the services of every profile.
     */
    fun baseArgs(
        file: Path,
        override: Path?,
        options: ComposeOptions,
        allProfiles: Boolean = false,
    ): List<String> {
        val folder = checkNotNull(absolute(file).parent) { "No folder for $file" }
        val args = mutableListOf("compose", "-f", absolute(file).toString())
        override?.let { args += listOf("-f", absolute(it).toString()) }
        options.projectName?.let {
            require(isValidProjectName(it)) { "Not a valid project name: \"$it\"" }
            args += listOf("-p", it)
        }
        val envFiles = options.envFiles
        if (envFiles != null) {
            if (envFiles.isEmpty()) args += listOf("--env-file", NULL_DEVICE)
            envFiles.forEach {
                require(it.isNotBlank()) { "An env file name is blank" }
                args += listOf("--env-file", folder.resolve(it).normalize().toString())
            }
        }
        if (allProfiles) {
            args += listOf("--profile", "*")
        } else {
            options.profiles.sorted().forEach {
                require(isValidProfileName(it)) { "Not a valid profile name: \"$it\"" }
                args += listOf("--profile", it)
            }
        }
        return args
    }

    /** The whole command of a project-wide Down: [baseArgs] with every profile, then [down]. */
    fun downArgs(
        file: Path,
        override: Path?,
        options: ComposeOptions,
        removeVolumes: Boolean,
    ): List<String> = baseArgs(file, override, options, allProfiles = true) + down(removeVolumes)

    /** `up` with the flags of [options]: `-d` if detached, `--build`, `--force-recreate`, `--remove-orphans`. */
    fun up(
        options: ComposeOptions,
        services: List<String> = emptyList(),
    ): List<String> = listOf("up") + upFlags(options, options.detached) + names(services)

    /** `up -d <service>` with the flags of [options], whatever [ComposeOptions.detached] says (the gutter's ▶). */
    fun upService(
        options: ComposeOptions,
        service: String,
    ): List<String> = listOf("up") + upFlags(options, detached = true) + names(listOf(service))

    /** `down`, with `-v` (also removes the named volumes) if [removeVolumes]. */
    fun down(removeVolumes: Boolean): List<String> = listOfNotNull("down", "-v".takeIf { removeVolumes })

    fun build(services: List<String> = emptyList()): List<String> = listOf("build") + names(services)

    fun pull(services: List<String> = emptyList()): List<String> = listOf("pull") + names(services)

    fun restart(services: List<String> = emptyList()): List<String> = listOf("restart") + names(services)

    fun stop(services: List<String> = emptyList()): List<String> = listOf("stop") + names(services)

    fun start(services: List<String> = emptyList()): List<String> = listOf("start") + names(services)

    /** `logs -f <services>` (all services if none). */
    fun logs(services: List<String> = emptyList()): List<String> = listOf("logs", "-f") + names(services)

    /** `exec <service> sh -c '…'`: a shell in a running service, bash if it has one. */
    fun shell(service: String): List<String> =
        listOf("exec") + names(listOf(service)) + listOf("sh", "-c", SHELL_SCRIPT)

    /**
     * The environment of the compose process: the variables of [options] by name (a later one wins; invalid names
     * and values with a NUL character, which a process cannot get, are left out). They beat every `--env-file` and
     * the `.env`.
     */
    fun environment(options: ComposeOptions): Map<String, String> =
        buildMap {
            options.variables.filter { isValidVariableName(it.name) && '\u0000' !in it.value }.forEach {
                put(it.name, it.value)
            }
        }

    /**
     * The environment of a compose run in the terminal: the value of each of [environment]'s variables, in order, under
     * a private name (`SLUIDE_COMPOSE_ENV_0`, …) that [commandLine] hands on under the real name. The login shell's
     * start files run before the command and could set the real names over the table's values; the private names they
     * leave alone.
     */
    fun terminalEnvironment(environment: Map<String, String>): Map<String, String> =
        environment.values.withIndex().associate { (index, value) -> terminalVariable(index) to value }

    /**
     * The command line for the login shell: [executable] and every one of [args] in single quotes. With [variables]
     * (the names of [terminalEnvironment]'s map, in its order, each a valid variable name) it starts with
     * `env "NAME=$SLUIDE_COMPOSE_ENV_0" …`, so compose gets them under their names while their values stay out of the
     * command line.
     */
    fun commandLine(
        executable: String,
        args: List<String>,
        variables: Collection<String> = emptyList(),
    ): String {
        val assignments =
            variables.mapIndexed { index, name ->
                require(isValidVariableName(name)) { "Not a valid variable name: \"$name\"" }
                "\"$name=\$${terminalVariable(index)}\""
            }
        val words = (listOf(executable) + args).map { shellQuoted(it) }
        return (if (assignments.isEmpty()) words else listOf("env") + assignments + words).joinToString(" ")
    }

    private fun terminalVariable(index: Int) = "SLUIDE_COMPOSE_ENV_$index"

    private fun upFlags(
        options: ComposeOptions,
        detached: Boolean,
    ) = listOfNotNull(
        "-d".takeIf { detached },
        "--build".takeIf { options.build },
        "--force-recreate".takeIf { options.forceRecreate },
        "--remove-orphans".takeIf { options.removeOrphans },
    )

    private fun names(services: List<String>): List<String> {
        services.forEach { require(it.isNotBlank() && !it.startsWith("-")) { "Not a valid service name: \"$it\"" } }
        return services
    }

    private fun absolute(path: Path): Path = path.toAbsolutePath().normalize()
}
