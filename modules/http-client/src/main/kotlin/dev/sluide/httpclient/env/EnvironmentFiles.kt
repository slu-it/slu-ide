package dev.sluide.httpclient.env

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** The public and private environment files that apply to a `.http` file; either may be missing. */
internal data class EnvironmentFiles(
    val public: Path?,
    val private: Path?,
) {
    companion object {
        const val PUBLIC = "http-client.env.json"
        const val PRIVATE = "http-client.private.env.json"

        /**
         * Finds each of the two files independently: the nearest one from the folder of [httpFile] up to
         * [projectRoot]. Only the file's own folder is searched when [projectRoot] is null or not one of its parents.
         */
        fun find(
            httpFile: Path,
            projectRoot: Path?,
        ): EnvironmentFiles {
            val folder = httpFile.toAbsolutePath().normalize().parent
            val root = projectRoot?.toAbsolutePath()?.normalize()?.takeIf { folder.startsWith(it) } ?: folder
            val folders = generateSequence(folder) { if (it == root) null else it.parent }

            fun nearest(name: String) = folders.map { it.resolve(name) }.firstOrNull { it.isRegularFile() }
            return EnvironmentFiles(nearest(PUBLIC), nearest(PRIVATE))
        }
    }
}
