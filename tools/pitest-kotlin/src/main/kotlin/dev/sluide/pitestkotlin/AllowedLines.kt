package dev.sluide.pitestkotlin

import java.nio.file.Files
import java.nio.file.Path

/**
 * The entries of an allow-list, `path:line` with the path relative to the repository root, e.g.
 * `modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:166`.
 */
class AllowedLines(
    entries: List<String>,
) {
    /** The listed paths per line. */
    private val pathsByLine: Map<Int, List<String>> =
        entries.groupBy({ it.substringAfterLast(':').toInt() }, { it.substringBeforeLast(':') })

    /**
     * True when [location]'s line is listed with a path that ends, at a `/`, in its path from the source root
     * (`dev/sluide/git/GitPush.kt`), which all classes compiled from that file share: nested classes, companions,
     * lambdas and the `GitPushKt` facade, and the copies of its inline functions in other files.
     */
    internal fun allows(location: CodeLocation): Boolean =
        pathsByLine[location.line].orEmpty().any { "/$it".endsWith("/${location.path}") }

    companion object {
        /**
         * Reads the allow-list at [file], the feature's parameter. Fails without it: a run that should be limited to some
         * lines never mutates everything instead.
         */
        fun fromFile(file: String?): AllowedLines {
            requireNotNull(file) { "LINES needs the allow-list file: +LINES(file[<path>])" }
            require(Files.isRegularFile(Path.of(file))) { "LINES: the allow-list $file does not exist" }
            val entries = Files.readAllLines(Path.of(file)).map(String::trim).filter(String::isNotEmpty)
            require(entries.isNotEmpty()) { "LINES: the allow-list $file is empty" }
            entries.forEach { entry ->
                val line = entry.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
                require(line != null && entry.substringBeforeLast(':').isNotEmpty()) {
                    "LINES: the allow-list $file has an entry that is no path:line: $entry"
                }
            }
            return AllowedLines(entries)
        }
    }
}
