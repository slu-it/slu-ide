package dev.sluide.dockersandbox

import java.io.IOException
import java.nio.file.Path

/**
 * The rule for where Docker sandboxes may run: only in folders strictly below the user's home folder, never in the
 * home folder itself, a folder above it or anywhere else. Folders are compared by their real paths, so symbolic links
 * and `..` cannot lead out of the home folder.
 */
internal object SandboxFolder {
    /** Why sandboxes may not run in [folder], or `null` if they may. */
    fun check(
        folder: Path,
        home: Path,
    ): String? {
        val realHome = realPath(home) ?: return "Your home folder $home does not exist."
        val real = realPath(folder) ?: return "The folder $folder does not exist."
        if (real != realHome && real.startsWith(realHome)) return null
        return "Docker sandboxes run only in folders below your home folder ($realHome), not in $real."
    }

    private fun realPath(path: Path): Path? =
        try {
            path.toAbsolutePath().toRealPath()
        } catch (_: IOException) {
            null
        }
}
