package dev.sluide.gradle.detect

import java.nio.file.Files
import java.nio.file.Path

/** The Gradle a build file runs with. */
internal sealed interface GradleInstallation {
    /** The wrapper of [folder]; [executable] if `gradlew` can be started directly, else it runs as `sh gradlew`. */
    data class Wrapper(
        val folder: Path,
        val executable: Boolean,
    ) : GradleInstallation

    /** The `gradle` of the user's login shell. */
    data object Global : GradleInstallation

    /** No Gradle to run. */
    data object None : GradleInstallation
}

/** Finds the Gradle for a build file (ADR 0085): a pure walk over the file system. */
internal object GradleLocator {
    /**
     * The wrapper in the nearest folder from [buildFile]'s folder up to and including [projectRoot]; never above it.
     * Without one, [GradleInstallation.Global] if [globalAvailable] (asked only then), else none. A build file outside
     * the project root or in a folder the trust check does not cover ([GradleCoverage]), or no project root, gets none.
     */
    fun locate(
        buildFile: Path,
        projectRoot: Path?,
        globalAvailable: () -> Boolean,
    ): GradleInstallation {
        if (projectRoot == null) return GradleInstallation.None
        val root = projectRoot.toAbsolutePath().normalize()
        val file = buildFile.toAbsolutePath().normalize()
        if (!file.startsWith(root)) return GradleInstallation.None
        var folder: Path? = file.parent
        if (folder == null || !GradleCoverage.covers(root, folder)) return GradleInstallation.None
        while (folder != null && folder.startsWith(root)) {
            val gradlew = folder.resolve("gradlew")
            if (Files.isRegularFile(gradlew) &&
                Files.isRegularFile(folder.resolve("gradle/wrapper/gradle-wrapper.properties"))
            ) {
                return GradleInstallation.Wrapper(folder, Files.isExecutable(gradlew))
            }
            folder = folder.parent
        }
        return if (globalAvailable()) GradleInstallation.Global else GradleInstallation.None
    }
}
