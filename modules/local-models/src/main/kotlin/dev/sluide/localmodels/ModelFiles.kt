package dev.sluide.localmodels

import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * A file to download: [path] relative to the folder of its key (`/`-separated, may name subfolders), where it comes
 * from, its size and its SHA-256 (lower-case hex).
 */
data class ModelFile(
    val path: String,
    val url: URI,
    val bytes: Long,
    val sha256: String,
)

/** A download failed (network, status, checksum, stall); the message is readable for the user. */
class ModelDownloadException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Where the model files of one key stand. */
sealed interface InstallState {
    data object NotInstalled : InstallState

    data class Downloading(
        val progress: Double,
    ) : InstallState

    data object Installed : InstallState

    data class Failed(
        val message: String,
    ) : InstallState
}

/**
 * What a user of [ModelInstaller] knows about its models; [K] names one installable unit (a variant, a pair).
 *
 * The installer calls [installed] on the FX thread, [files] and [folder] on its worker threads, and [taskName] on
 * both, so every method must be thread-safe (pure functions of the key, or reads of the disk).
 */
interface ModelSpecs<K : Any> {
    /** All files [key] needs; called on a worker thread. */
    fun files(key: K): List<ModelFile>

    /** Where the [ModelFile.path]s of [key] resolve; called on a worker thread. */
    fun folder(key: K): Path

    /**
     * Whether [key] is ready to use, e.g. every file present with its expected size; called on the FX thread, so it
     * must be cheap (no hashing).
     */
    fun installed(key: K): Boolean

    /**
     * The text of the background task row while [key] downloads, at [percent] (in steps of 5); called on the FX thread
     * and on the download's thread.
     */
    fun taskName(
        key: K,
        percent: Int,
    ): String
}

/**
 * Where [path] goes in [folder]. A path that leaves the folder (`../x`, an absolute path) or names the folder itself
 * (`""`, `.`) is a programming error of the specs: [IllegalArgumentException].
 */
internal fun resolveIn(
    folder: Path,
    path: String,
): Path {
    val base = folder.normalize()
    val target = base.resolve(path).normalize()
    require(target.startsWith(base) && target != base) { "$path is not a file in the model folder" }
    return target
}
