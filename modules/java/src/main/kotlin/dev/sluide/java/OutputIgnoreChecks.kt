package dev.sluide.java

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Where to ask git which of jdtls's output folders it does not ignore (ADR 0179): the java module may not run git, so
 * each window registers a check over its git status for its folder ([register]); the latest registration of a folder
 * wins. FX thread.
 */
@Component
class OutputIgnoreChecks {
    private val checks = HashMap<Path, MutableList<(List<Path>, (List<Path>?) -> Unit) -> Unit>>()

    /** Registers [check] for [windowFolder] until the result is closed. */
    fun register(
        windowFolder: Path,
        check: (folders: List<Path>, done: (List<Path>?) -> Unit) -> Unit,
    ): AutoCloseable {
        val key = windowFolder.toAbsolutePath().normalize()
        // A lambda of its own, so that two registrations of the same function object stay distinct.
        val entry: (List<Path>, (List<Path>?) -> Unit) -> Unit = { folders, done -> check(folders, done) }
        checks.getOrPut(key) { mutableListOf() }.add(entry)
        return AutoCloseable { checks[key]?.removeAll { it === entry } }
    }

    /**
     * Asks the check of [windowFolder] which of [folders] hold untracked files git does not ignore: [done] gets them,
     * or null if no check is registered or git cannot tell (no repository, trust, a failure).
     */
    fun check(
        windowFolder: Path,
        folders: List<Path>,
        done: (List<Path>?) -> Unit,
    ) {
        val check = checks[windowFolder.toAbsolutePath().normalize()]?.lastOrNull()
        if (check == null) done(null) else check(folders, done)
    }
}
