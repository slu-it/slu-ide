package dev.sluide.docker.dockerfile

import dev.sluide.docker.cli.shellQuoted
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The build command of a Dockerfile (not shell strings: [commandLine] quotes them for the login shell):
 * `build -f <file> [-t tag]… [--target t] [--build-arg NAME=$SLUIDE_BUILD_ARG_<i>]… [--no-cache] [--pull]
 * [--platform p] <context>`.
 *
 * Build-arg values never reach the command line. Each passed arg's value goes into the process environment under a
 * private name, `SLUIDE_BUILD_ARG_0`, `SLUIDE_BUILD_ARG_1`, … in the order of the passed args ([environment]), and the
 * command line carries `"NAME=$SLUIDE_BUILD_ARG_0"` in double quotes, which the login shell expands after its startup
 * files ran. So a startup file cannot override the value (as it could `HTTP_PROXY` or `PATH` under the ARG's own
 * name), the docker CLI's own environment stays as it is (`DOCKER_HOST`, `HOME`, `*_PROXY`), and the value shows
 * neither in the terminal nor in the preview. Only the ARGs of the file's current text with a value are passed; values
 * stored for ARGs no longer in the file are not. Everything that reaches the command line is checked first
 * ([problem]); the file and the context go in as absolute paths, so nothing poses as a flag.
 */
internal object BuildCommands {
    /** The arguments after the executable; [IllegalArgumentException] with the [problem] if there is one. */
    fun buildArgs(
        options: BuildOptions,
        file: Path,
        info: DockerfileInfo,
        context: Path = contextOf(options, file),
        isDirectory: (Path) -> Boolean = Files::isDirectory,
    ): List<String> {
        problem(options, info, context, isDirectory)?.let { throw IllegalArgumentException(it) }
        val args = mutableListOf("build", "-f", absolute(file).toString())
        tagsOf(options.tags).forEach { args += listOf("-t", it) }
        options.target
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { args += listOf("--target", it) }
        passedArgs(options, info).forEachIndexed { index, name ->
            args += listOf("--build-arg", "$name=\$${variableName(index)}")
        }
        if (options.noCache) args += "--no-cache"
        if (options.pull) args += "--pull"
        options.platform
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { args += listOf("--platform", it) }
        args += context.toString()
        return args
    }

    /**
     * The command line for the login shell: [executable] and every one of [args] in single quotes, but the build-arg
     * words `NAME=$SLUIDE_BUILD_ARG_<i>` of [buildArgs] in double quotes, so that the shell puts the value in. No other
     * word can look like one: tags, target and platform are checked and cannot hold `=` or `$`, the file and the context
     * are absolute paths.
     */
    fun commandLine(
        executable: String,
        args: List<String>,
    ): String =
        (listOf(executable) + args).joinToString(" ") { word ->
            if (BUILD_ARG_WORD.matches(word)) "\"$word\"" else shellQuoted(word)
        }

    /**
     * The values of the passed build args ([passedArgs]) under the private names the command line refers to
     * (`SLUIDE_BUILD_ARG_0`, … in the order of the passed args), for the process environment of the build.
     */
    fun environment(
        options: BuildOptions,
        info: DockerfileInfo,
    ): Map<String, String> =
        passedArgs(options, info)
            .mapIndexed { index, name -> variableName(index) to checkNotNull(options.buildArgs[name]) }
            .toMap()

    /** The private environment variable that holds the value of the passed build arg at [index]. */
    private fun variableName(index: Int) = "$VARIABLE_PREFIX$index"

    /** The names of the file's ARGs, in file order, that have a value in [options]. */
    fun passedArgs(
        options: BuildOptions,
        info: DockerfileInfo,
    ): List<String> = info.args.map { it.name }.filter { !options.buildArgs[it].isNullOrEmpty() }

    /**
     * The context folder of a build of [file]: [BuildOptions.context] resolved against the file's folder, which it is
     * when empty; absolute and normalized. [IllegalArgumentException] for a context that is no path.
     */
    fun contextOf(
        options: BuildOptions,
        file: Path,
    ): Path {
        val folder = checkNotNull(absolute(file).parent) { "No folder for $file" }
        val context = options.context.trim()
        if (context.isEmpty()) return folder
        return try {
            folder.resolve(context).normalize()
        } catch (e: InvalidPathException) {
            throw IllegalArgumentException("Not a valid context folder: \"$context\"", e)
        }
    }

    /**
     * Why [options] cannot build, or `null` if they can: an invalid tag, a missing [context] folder, an invalid target,
     * platform or build-arg name, or a value with a NUL character (which a process cannot get).
     */
    fun problem(
        options: BuildOptions,
        info: DockerfileInfo,
        context: Path,
        isDirectory: (Path) -> Boolean = Files::isDirectory,
    ): String? {
        tagsOf(options.tags).firstOrNull { !isValidTag(it) }?.let {
            return "Not a valid tag: \"$it\" (lower-case name[:tag], e.g. myapp:1.0)"
        }
        val target = options.target.trim()
        if (target.isNotEmpty() && !TARGET.matches(target)) return "Not a valid target: \"$target\""
        val platform = options.platform.trim()
        if (platform.isNotEmpty() && !PLATFORM.matches(platform)) {
            return "Not a valid platform: \"$platform\" (os/arch[/variant], e.g. linux/amd64)"
        }
        for (name in passedArgs(options, info)) {
            if (!BUILD_ARG_NAME.matches(name)) return "Not a valid build arg name: \"$name\""
            if ('\u0000' in checkNotNull(options.buildArgs[name])) return "The value of $name contains a NUL character"
        }
        if (!isDirectory(context)) return "The context folder does not exist: $context"
        return null
    }

    /** Whether [tag] is an image reference to tag a build with, loosely after Docker's grammar. */
    fun isValidTag(tag: String): Boolean = TAG.matches(tag)

    private fun absolute(path: Path): Path = path.toAbsolutePath().normalize()

    /** A path component of an image name: lower-case letters and digits, joined by `.`, `_`, `__` or dashes. */
    private const val COMPONENT = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*"

    /** A registry: `localhost`, a name with a dot, or a name with a port, each optionally with a port. */
    private const val REGISTRY =
        "(?:(?:localhost|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)(?::[0-9]+)?|[A-Za-z0-9-]+:[0-9]+)"

    private val TAG = Regex("(?:$REGISTRY/)?$COMPONENT(?:/$COMPONENT)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?")
    private val TARGET = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val PLATFORM = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?")
    private val BUILD_ARG_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private const val VARIABLE_PREFIX = "SLUIDE_BUILD_ARG_"
    private val BUILD_ARG_WORD = Regex("[A-Za-z_][A-Za-z0-9_]*=\\\$$VARIABLE_PREFIX[0-9]+")
}
