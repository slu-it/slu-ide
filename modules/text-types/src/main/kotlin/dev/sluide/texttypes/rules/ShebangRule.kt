package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * The interpreter of a `#!` first line: `bash`, `sh`, `zsh`, `dash`, `ksh`, `ash`, `mksh` → Shell Script, `python`
 * (any version) → Python, `node`, `nodejs`, `deno`, `bun` → JavaScript; also through `/usr/bin/env`, skipping its
 * words that start with `-` or hold `=`. An option's argument is not known as one: `env -u NAME python3` names no type.
 */
internal object ShebangRule : TextTypeSource {
    const val SCORE = 100

    private val shell = Regex("(?:ba|z|da|k|mk|a)?sh")
    private val python = Regex("python[0-9.]*")
    private val javascript = Regex("node|nodejs|deno|bun")

    override fun scores(text: String): List<TypeScore> {
        if (!text.startsWith("#!")) return emptyList()
        val interpreter = interpreter(text.substring(2).lineSequence().first())
        val type =
            when {
                shell.matches(interpreter) -> TextTypes.SHELL_SCRIPT
                python.matches(interpreter) -> TextTypes.PYTHON
                javascript.matches(interpreter) -> TextTypes.JAVASCRIPT
                else -> return emptyList()
            }
        return listOf(TypeScore(type, SCORE))
    }

    /** The interpreter's file name: `/bin/bash` → `bash`, `/usr/bin/env -S deno run` → `deno`; `""` if none. */
    private fun interpreter(line: String): String {
        val words = line.split(' ', '\t').filter(String::isNotEmpty)
        val command = words.firstOrNull().orEmpty().substringAfterLast('/')
        val program = if (command == "env") words.drop(1).firstOrNull { !it.startsWith("-") && '=' !in it } else command
        return program.orEmpty().substringAfterLast('/')
    }
}
