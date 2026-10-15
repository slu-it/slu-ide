package dev.sluide.fileviewers

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Which text files a viewer opens, all case-insensitive: files with one of the [extensions], with one of the exact
 * file [names] (`dockerfile`, `.bashrc`), or whose whole name matches one of the [namePatterns] (`dockerfile\..*`)
 * ([matchesName]). Files without an extension that no viewer opens by name may also be opened by a shebang naming
 * one of the [interpreters] ([matchesInterpreter]); [FileViewers] reads the shebang once for all viewers.
 */
internal class FileMatch(
    extensions: Set<String>,
    names: Set<String> = emptySet(),
    namePatterns: List<Regex> = emptyList(),
    interpreters: Set<String> = emptySet(),
) {
    private val extensions = extensions.mapTo(HashSet()) { it.lowercase() }
    private val names = names.mapTo(HashSet()) { it.lowercase() }
    private val namePatterns = namePatterns.map { Regex(it.pattern, it.options + RegexOption.IGNORE_CASE) }
    private val interpreters = interpreters.mapTo(HashSet()) { it.lowercase() }

    /** Whether [file] is a readable text file whose extension or name matches; its content is not looked at. */
    fun matchesName(file: Path): Boolean = nameMatch(file) != null && Files.isReadable(file) && isTextFile(file)

    /** How the name of [file] matches, the strongest way first, or `null` if it does not; the file is not read. */
    fun nameMatch(file: Path): NameMatch? {
        val name = file.name
        return when {
            name.lowercase() in names -> NameMatch.NAME
            file.extension.lowercase() in extensions -> NameMatch.EXTENSION
            namePatterns.any { it.matches(name) } -> NameMatch.PATTERN
            else -> null
        }
    }

    /**
     * Whether a shebang naming [interpreter] ([shebangInterpreter]) is one of the [interpreters], with or without its
     * version: `python3.12` is accepted as `python3.12`, `python3` and `python`.
     */
    fun matchesInterpreter(interpreter: String): Boolean {
        val raw = interpreter.lowercase()
        return raw in interpreters ||
            raw.replace(MINOR_VERSIONS, "") in interpreters ||
            raw.replace(VERSION, "") in interpreters
    }

    private companion object {
        val MINOR_VERSIONS = Regex("""(\.\d+)+$""")
        val VERSION = Regex("""\d+(\.\d+)*$""")
    }
}

/**
 * How a file's name matches a viewer, strongest first: [FileViewers] prefers a viewer naming the file exactly
 * (`Dockerfile`) over one taking its extension (`.md`), and that over one matching a name pattern (`dockerfile\..+`),
 * so that `Dockerfile.md` is Markdown whatever the order of the viewers.
 */
internal enum class NameMatch { NAME, EXTENSION, PATTERN }

/**
 * The interpreter a shebang [line] names, without its folder: `bash` for `#!/bin/bash` or `#! /bin/bash -e`, and
 * `python3` for `#!/usr/bin/env python3` or `#!/usr/bin/env -S python3 -u` (options of `env`, with the arguments of
 * `-u`, `-C` and `-P`, and `NAME=value` settings are skipped). The launcher `uv run` (`#!/usr/bin/env -S uv run
 * --script`, PEP 723 scripts) counts as `python`. `null` if the line is no shebang or names nothing.
 */
internal fun shebangInterpreter(line: String): String? {
    if (!line.startsWith("#!")) return null
    val words =
        line
            .substring(2)
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
    val command = words.firstOrNull()?.substringAfterLast('/') ?: return null
    val program = if (command.equals("env", ignoreCase = true)) envProgram(words.drop(1)) else words
    val interpreter = program.firstOrNull()?.substringAfterLast('/')?.ifEmpty { null } ?: return null
    val isUvRun = interpreter == "uv" && program.drop(1).firstOrNull { !it.startsWith("-") } == "run"
    return if (isUvRun) "python" else interpreter
}

/** The command line `env` runs, given its [arguments]: without its options and `NAME=value` settings. */
private fun envProgram(arguments: List<String>): List<String> {
    var index = 0
    while (index < arguments.size) {
        val word = arguments[index]
        when {
            word in ENV_OPTIONS_WITH_ARGUMENT -> index += 2
            word.startsWith("-") || '=' in word -> index++
            else -> return arguments.subList(index, arguments.size)
        }
    }
    return emptyList()
}

private val WHITESPACE = Regex("""\s+""")

/** `env` options whose argument is the next word: unset a variable, change the folder, search a path. */
private val ENV_OPTIONS_WITH_ARGUMENT = setOf("-u", "--unset", "-C", "--chdir", "-P")
