package dev.sluide.fileviewers

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Which text files a viewer opens, all case-insensitive: files with one of the [extensions], with one of the exact
 * file [names] (`dockerfile`, `.bashrc`), or whose whole name matches one of the [namePatterns] (`dockerfile\..*`).
 * Files without an extension also match if their first line is a shebang naming one of the [interpreters]
 * (see [shebangInterpreter]); a file with an extension never matches by its shebang.
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

    /** Whether [file] is a readable text file that matches by its name or, without an extension, by its shebang. */
    fun matches(file: Path): Boolean {
        if (matchesName(file)) return Files.isReadable(file) && isTextFile(file)
        if (interpreters.isEmpty() || file.extension.isNotEmpty() || !Files.isReadable(file)) return false
        return firstLine(file)?.let(::shebangInterpreter)?.let(::isInterpreter) ?: false
    }

    private fun matchesName(file: Path): Boolean {
        val name = file.name
        return file.extension.lowercase() in extensions ||
            name.lowercase() in names ||
            namePatterns.any { it.matches(name) }
    }

    /** `python3.12` is accepted as `python3.12`, `python3` and `python`. */
    private fun isInterpreter(name: String): Boolean {
        val raw = name.lowercase()
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
