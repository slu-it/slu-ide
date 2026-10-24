package dev.sluide.gradle.detect

import java.nio.file.Path

/** Builds the command strings run by `$SHELL -l -i -c` (ADR 0085, ADR 0087). */
internal object GradleCommand {
    /** Characters that need no quoting in a POSIX shell word. */
    private val SAFE = Regex("[A-Za-z0-9_./:-][A-Za-z0-9_./:=@%+,-]*")

    /** The program of [installation]: `<folder>/gradlew`, `sh <folder>/gradlew` or `gradle`. */
    fun executable(installation: GradleInstallation): String =
        when (installation) {
            is GradleInstallation.Wrapper -> {
                val gradlew =
                    quote(
                        installation.folder
                            .toAbsolutePath()
                            .normalize()
                            .resolve("gradlew")
                            .toString(),
                    )
                if (installation.executable) gradlew else "sh $gradlew"
            }

            GradleInstallation.Global -> {
                "gradle"
            }

            GradleInstallation.None -> {
                throw IllegalArgumentException("No Gradle to run")
            }
        }

    /**
     * The command running [tasks] with the [userArguments] (tokenized like a shell does: spaces separate, quotes
     * group); throws [IllegalArgumentException] with a message to show if a quote is not closed.
     */
    fun run(
        installation: GradleInstallation,
        userArguments: String,
        tasks: List<String>,
    ): String = (listOf(executable(installation)) + (tokenize(userArguments) + tasks).map(::quote)).joinToString(" ")

    /** The command writing the task list of the build to [output] with the [initScript]. */
    fun loadTasks(
        installation: GradleInstallation,
        initScript: Path,
        output: Path,
    ): String =
        listOf(
            executable(installation),
            "--init-script",
            quote(initScript.toAbsolutePath().toString()),
            quote("-Dsluide.taskList=${output.toAbsolutePath()}"),
            "-Dorg.gradle.configuration-cache=false",
            "sluideTaskList",
        ).joinToString(" ")

    /**
     * Splits [text] into words at white space; single and double quotes group (and are removed), `\` escapes the next
     * character (in `"` only `"` and `\`).
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' -> {
                    if (c == '\'') quote = null else current.append(c)
                }

                quote == '"' -> {
                    when {
                        c == '"' -> quote = null
                        c == '\\' && i + 1 < text.length && text[i + 1] in "\"\\" -> current.append(text[++i])
                        else -> current.append(c)
                    }
                }

                c == '\\' && i + 1 < text.length -> {
                    current.append(text[++i])
                    inToken = true
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    inToken = true
                }

                c.isWhitespace() -> {
                    if (inToken) {
                        tokens += current.toString()
                        current.clear()
                        inToken = false
                    }
                }

                else -> {
                    current.append(c)
                    inToken = true
                }
            }
            i++
        }
        require(quote == null) { "Unbalanced $quote quote in the arguments" }
        if (inToken) tokens += current.toString()
        return tokens
    }

    /** [word] as one word of a POSIX shell: as is if it is plain, else in single quotes. */
    fun quote(word: String): String = if (SAFE.matches(word)) word else "'" + word.replace("'", "'\\''") + "'"
}
