package dev.sluide.docker.run

import dev.sluide.commandruns.ShellWords
import dev.sluide.docker.cli.shellQuoted
import java.nio.file.Path

private val NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val IPV6 = Regex("""\[[0-9a-fA-F:.]+]""")
private val IMAGE_ID = Regex("(sha256:)?[0-9a-f]{12,64}")

/** Whether [name] is a valid container name. */
internal fun isValidContainerName(name: String) = NAME.matches(name)

/** Whether [name] is a valid environment variable name. */
internal fun isValidEnvName(name: String) = ENV_NAME.matches(name)

private fun hasNul(text: String) = '\u0000' in text

/** The options without the rows the user left empty (a port, a variable or a volume with nothing typed). */
internal fun RunOptions.withoutBlankRows() =
    copy(
        name = name.trim(),
        ports = ports.map { it.trim() }.filter { it.isNotEmpty() },
        environment =
            environment
                .filter { it.name.isNotBlank() || it.value.isNotEmpty() }
                .map { it.copy(name = it.name.trim()) },
        volumes = volumes.filter { it.source.isNotBlank() || it.target.isNotBlank() },
        network = network?.takeIf { it.isNotBlank() },
    )

private fun isPortNumber(text: String) =
    text.length <= 5 && text.all { it in '0'..'9' } && text.toIntOrNull() in 1..65535

private fun isHostIp(ip: String) = IPV6.matches(ip) || (IPV4.matches(ip) && ip.split('.').all { it.toInt() <= 255 })

/** Whether [port] is `[host-ip:]host-port:container-port[/tcp|udp]` or `container-port`, ports 1-65535. */
internal fun isValidPort(port: String): Boolean {
    if (hasNul(port)) return false
    val spec = port.substringBefore('/')
    if ('/' in port && port.substringAfter('/') !in setOf("tcp", "udp")) return false
    val parts = spec.split(':')
    return when (parts.size) {
        1 -> isPortNumber(parts[0])
        2 -> parts.all(::isPortNumber)
        else -> parts.takeLast(2).all(::isPortNumber) && isHostIp(parts.dropLast(2).joinToString(":"))
    }
}

/** The first thing wrong with [volume], or `null`. */
private fun volumeProblem(volume: RunVolume): String? {
    val source = volume.source.trim()
    val target = volume.target.trim()
    if (source.isEmpty()) return "A volume has no source (a host path or a volume name)."
    if (hasNul(source) || hasNul(target) || ':' in source) return "The volume source $source is not valid."
    val isPath = source.startsWith("/") || source.startsWith("./") || source.startsWith("~/")
    if (!isPath && !NAME.matches(source)) {
        return "The volume source $source must be an absolute path, a ./ or ~/ path or a volume name."
    }
    if (!target.startsWith("/") || ':' in target) {
        return "The volume target $target must be an absolute path in the container."
    }
    return null
}

/**
 * The first thing wrong with [options], as a message to show; `null` if `docker run` can be built. Blank rows are
 * ignored.
 */
internal fun validate(options: RunOptions): String? {
    val o = options.withoutBlankRows()
    if (o.image.isBlank() || o.image.startsWith("-") || hasNul(o.image)) return "The image ${o.image} is not valid."
    if (o.name.isNotEmpty() && !isValidContainerName(o.name)) {
        return "The name must start with a letter or digit and use only letters, digits, _, . and -."
    }
    o.ports.firstOrNull { !isValidPort(it) }?.let {
        return "The port $it is not [host-ip:]host-port:container-port[/tcp|udp] or a container port (1-65535)."
    }
    o.environment.forEach { variable ->
        if (!isValidEnvName(variable.name)) {
            return "The variable name ${variable.name} must be letters, digits and _, not starting with a digit."
        }
        if (hasNul(variable.value)) return "The value of ${variable.name} contains a NUL character."
    }
    val names = o.environment.map { it.name }
    names.firstOrNull { name -> names.count { it == name } > 1 }?.let { return "The variable $it is set twice." }
    o.volumes.firstNotNullOfOrNull(::volumeProblem)?.let { return it }
    o.network?.let { if (it.startsWith("-") || hasNul(it)) return "The network $it is not valid." }
    if (hasNul(o.command)) return "The command contains a NUL character."
    try {
        ShellWords.tokenize(o.command)
    } catch (e: IllegalArgumentException) {
        return e.message ?: "The command is not valid."
    }
    return null
}

private fun resolvedSource(
    source: String,
    projectFolder: Path?,
    home: Path,
): String =
    when {
        source.startsWith("./") -> (projectFolder ?: home).resolve(source.removePrefix("./")).normalize().toString()
        source.startsWith("~/") -> home.resolve(source.removePrefix("~/")).normalize().toString()
        else -> source
    }

/** What the preview shows instead of a variable's value. */
internal const val MASK = "••••"

/** The prefix of the terminal environment variables that carry the values of an attached run. */
internal const val ATTACHED_ENV_PREFIX = "SLUIDE_RUN_ENV_"

/** A word of a command line; a [literal] one is shell syntax to write as it is, any other is quoted as needed. */
private class Word(
    val text: String,
    val literal: Boolean = false,
)

/**
 * The words of `docker run` for valid [options] (see [validate]), the executable not included: `run [-d] [--rm]
 * [--name n] [-p spec]... [-e ...]... [-v src:target[:ro]]... [--network net] image [command words]`. [variable]
 * makes the word after `-e` from the variable's index, name and value. `./` sources are resolved against
 * [projectFolder] (or [home] without a project), `~/` against [home].
 */
private fun runWords(
    options: RunOptions,
    projectFolder: Path?,
    home: Path,
    variable: (index: Int, name: String, value: String) -> Word,
): List<Word> {
    val o = options.withoutBlankRows()
    val words = mutableListOf(Word("run"))

    fun add(vararg texts: String) = texts.forEach { words += Word(it) }
    if (o.detached) add("-d")
    if (o.removeWhenStopped) add("--rm")
    if (o.name.isNotEmpty()) add("--name", o.name)
    o.ports.forEach { add("-p", it) }
    o.environment.forEachIndexed { index, it ->
        add("-e")
        words += variable(index, it.name, it.value)
    }
    o.volumes.forEach {
        val source = resolvedSource(it.source.trim(), projectFolder, home)
        add("-v", "$source:${it.target.trim()}" + if (it.readOnly) ":ro" else "")
    }
    o.network?.let { add("--network", it) }
    add(o.image)
    ShellWords.tokenize(o.command).forEach { add(it) }
    return words
}

private fun inline(
    index: Int,
    name: String,
    value: String,
) = Word("$name=$value")

private fun line(
    executable: String,
    words: List<Word>,
    quote: (String) -> String,
) = (listOf(Word(executable)) + words).joinToString(" ") { if (it.literal) it.text else quote(it.text) }

/**
 * The arguments of `docker run` for valid [options], without the executable, with each variable inline as
 * `-e NAME=value`: for the detached run, which the CLI executes directly so that no shell or terminal shows them.
 */
internal fun runArgs(
    options: RunOptions,
    projectFolder: Path?,
    home: Path,
): List<String> = runWords(options, projectFolder, home, ::inline).map { it.text }

/** The command line as the preview shows it: each variable as `-e 'NAME=••••'`, quoted where a shell needs it. */
internal fun previewText(
    executable: String,
    options: RunOptions,
    projectFolder: Path?,
    home: Path,
): String =
    line(executable, runWords(options, projectFolder, home) { _, name, _ -> Word("$name=$MASK") }, ShellWords::quote)

/**
 * A command line that works when pasted into a shell, built from [options]: like the preview but with the real values,
 * each variable as `-e 'NAME=value'`, every word quoted.
 */
internal fun copyText(
    executable: String,
    options: RunOptions,
    projectFolder: Path?,
    home: Path,
): String = line(executable, runWords(options, projectFolder, home, ::inline), ::shellQuoted)

/**
 * The command line of an attached run: every word quoted, each variable as the literal `"NAME=$SLUIDE_RUN_ENV_<i>"`
 * (double quotes, so the shell expands it after its start files ran and keeps the value in one word). The values are
 * in [attachedEnvironment]; they never show in the terminal.
 */
internal fun attachedCommand(
    executable: String,
    options: RunOptions,
    projectFolder: Path?,
    home: Path,
): String =
    line(
        executable,
        runWords(options, projectFolder, home) { index, name, _ ->
            Word("\"$name=\$$ATTACHED_ENV_PREFIX$index\"", literal = true)
        },
        ::shellQuoted,
    )

/** The terminal environment of an attached run: `SLUIDE_RUN_ENV_<i>` holds the value of the i-th variable. */
internal fun attachedEnvironment(options: RunOptions): Map<String, String> =
    options
        .withoutBlankRows()
        .environment
        .mapIndexed { index, it -> "$ATTACHED_ENV_PREFIX$index" to it.value }
        .toMap()

/**
 * The repository of an image [reference]: without the tag and digest, keeping a registry port (`reg:5000/app:1.0` ->
 * `reg:5000/app`); `null` for an image id (an untagged image), which has no repository.
 */
internal fun repositoryOf(reference: String): String? {
    val noDigest = reference.substringBefore('@')
    if (IMAGE_ID.matches(noDigest)) return null
    val colon = noDigest.indexOf(':', startIndex = noDigest.lastIndexOf('/') + 1)
    return (if (colon >= 0) noDigest.substring(0, colon) else noDigest).takeIf { it.isNotEmpty() }
}
