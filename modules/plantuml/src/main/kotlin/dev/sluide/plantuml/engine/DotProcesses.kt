package dev.sluide.plantuml.engine

import java.io.IOException
import java.nio.file.Path

/** The `dot` processes PlantUML started in this JVM, found by their exact executable, never by a name search. */
internal object DotProcesses {
    /**
     * Whether a process whose `ProcessHandle.Info.command()` is [command] runs [dot]. Linux reports the executable's
     * real path (`/proc/<pid>/exe`, measured; Homebrew's `dot` is a link into the Cellar). macOS reports the path the
     * process was started with, [dot] as PlantUML got it, according to the JDK's source (`KERN_PROCARGS2`); not
     * measured.
     */
    fun isDot(
        command: String?,
        dot: Path,
    ): Boolean = command != null && (command == dot.toString() || command == realPath(dot))

    private fun realPath(dot: Path): String? =
        try {
            dot.toRealPath().toString()
        } catch (_: IOException) {
            null
        }

    /**
     * Finds this JVM's child processes that run [dot] now (reading the process table), and returns what kills them
     * (quick, so it may run under a lock).
     */
    fun killer(dot: Path): Kill {
        val found =
            ProcessHandle
                .current()
                .children()
                .toList()
                .filter { isDot(it.info().command().orElse(null), dot) }
        return Kill { for (child in found) child.destroyForcibly() }
    }

    /** Kills what [killer] found. */
    fun interface Kill {
        fun now()
    }
}
