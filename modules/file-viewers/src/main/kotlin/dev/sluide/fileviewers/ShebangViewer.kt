package dev.sluide.fileviewers

/**
 * A viewer that also opens text files without an extension by their shebang. [FileViewers] asks it only when no
 * viewer opens the file by its name, so a name always wins over a shebang.
 */
interface ShebangViewer : FileViewer {
    /**
     * Whether this viewer opens a script whose shebang names [interpreter], without its folder (`bash` for
     * `#!/bin/bash`, `python3` for `#!/usr/bin/env python3`); see [interpreterMatches].
     */
    fun opensInterpreter(interpreter: String): Boolean
}

/**
 * Whether a shebang naming [interpreter] (without its folder) names one of the [interpreters], case-insensitively and
 * with or without its version: `python3.12` is accepted as `python3.12`, `python3` and `python`, `ksh93` as `ksh`.
 */
fun interpreterMatches(
    interpreter: String,
    interpreters: Set<String>,
): Boolean {
    val raw = interpreter.lowercase()
    val names = listOf(raw, raw.replace(MINOR_VERSIONS, ""), raw.replace(VERSION, ""))
    return names.any { name -> interpreters.any { it.equals(name, ignoreCase = true) } }
}

private val MINOR_VERSIONS = Regex("""(\.\d+)+$""")
private val VERSION = Regex("""\d+(\.\d+)*$""")
