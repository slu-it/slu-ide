package dev.sluide.pitestkotlin

/** A source file the SMAP's `*F` section names: its file name and the class compiled from it. */
internal data class SmapFile(
    val name: String,
    val className: String,
) {
    /** The path from the source root, the class's package folder and the file name: `dev/sluide/x/Foo.kt`. */
    val path: String = listOf(className.substringBeforeLast('/', ""), name).filter(String::isNotEmpty).joinToString("/")
}

/** A line of a source file. */
internal data class SourceLine(
    val file: SmapFile,
    val line: Int,
)

/**
 * The `Kotlin` stratum of a class's SMAP (JSR-45, the `SourceDebugExtension` the Kotlin compiler writes when it inlines
 * code): which source file and line each line number of the class stands for.
 */
internal class SourceMap private constructor(
    private val files: Map<Int, SmapFile>,
    private val entries: List<LineEntry>,
) {
    /** Where [line], a line number of the class, was written; null when no entry maps it. */
    fun sourceOf(line: Int): SourceLine? {
        val entry = entries.firstOrNull { line in it.outputLines } ?: return null
        val file = files[entry.fileId] ?: return null
        return SourceLine(file, entry.inputStart + (line - entry.outputLines.first) / entry.increment)
    }

    companion object {
        /** The map in [sourceDebug], the class's `SourceDebugExtension`; null without one or without a Kotlin stratum. */
        fun parse(sourceDebug: String?): SourceMap? {
            val lines = sourceDebug?.lines().orEmpty()
            if ("*S Kotlin" !in lines) return null
            val stratum = lines.dropWhile { it != "*S Kotlin" }.drop(1).takeWhile { !it.startsWith("*S ") }
            return SourceMap(filesOf(sectionOf(stratum, "*F")), entriesOf(sectionOf(stratum, "*L")))
        }

        /** The lines of [section] (`*F`, `*L`) in [stratum], up to the next section or the end (`*E`). */
        private fun sectionOf(
            stratum: List<String>,
            section: String,
        ) = stratum.dropWhile { it != section }.drop(1).takeWhile { !it.startsWith("*") }

        /** `+ <id> <name>`, followed by the line with the class compiled from that file. */
        private fun filesOf(section: List<String>): Map<Int, SmapFile> =
            section
                .zipWithNext()
                .mapNotNull { (line, className) ->
                    FILE.matchEntire(line)?.destructured?.let { (id, name) -> id.toInt() to SmapFile(name, className) }
                }.toMap()

        /**
         * The entries of the `*L` section. One without a file id is in the file of the one before; a line that is no
         * entry maps nothing.
         */
        private fun entriesOf(section: List<String>): List<LineEntry> {
            var fileId = 0
            return section.mapNotNull { line ->
                val (inputStart, id, repeat, outputStart, increment) =
                    LINE.matchEntire(line)?.destructured ?: return@mapNotNull null
                fileId = id.toIntOrNull() ?: fileId
                val step = increment.toIntOrNull() ?: 1
                val first = outputStart.toInt()
                LineEntry(inputStart.toInt(), fileId, first until first + (repeat.toIntOrNull() ?: 1) * step, step)
            }
        }

        private val FILE = Regex("""\+ (\d+) (.+)""")

        /** `InputStartLine[#LineFileID][,RepeatCount]:OutputStartLine[,OutputLineIncrement]` (JSR-45). */
        private val LINE = Regex("""(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?""")
    }
}

/** One line entry: source lines from [inputStart] in file [fileId], each [increment] lines of [outputLines]. */
private class LineEntry(
    val inputStart: Int,
    val fileId: Int,
    val outputLines: IntRange,
    val increment: Int,
)
