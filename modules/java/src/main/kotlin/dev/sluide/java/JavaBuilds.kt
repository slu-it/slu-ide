package dev.sluide.java

import java.nio.file.Files
import java.nio.file.Path

enum class BuildTool { GRADLE, MAVEN }

data class JavaBuild(
    val root: Path,
    val tool: BuildTool,
)

private val GRADLE_SETTINGS = listOf("settings.gradle", "settings.gradle.kts")
private val GRADLE_BUILDS = listOf("build.gradle", "build.gradle.kts")
private val BUILD_FILE_NAMES =
    setOf(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle-wrapper.properties",
        "libs.versions.toml",
    )
private val KOTLIN_MARKERS = listOf("org.jetbrains.kotlin", "kotlin(")
private val LOMBOK_FILE_SUFFIXES = listOf(".gradle", ".gradle.kts")

/**
 * Where the build of a Java file is and what is next to it (ADR 0179). Pure file-system functions: they block on I/O,
 * so they run on worker threads, never on the FX thread (except [isBuildFile], which looks at names only). Every I/O
 * error counts as "absent".
 */
object JavaBuilds {
    /**
     * The build root of [file] inside [windowFolder] (inclusive, never above), or null for a loose file. Only builds the
     * trust checks cover count (see [covered] and `GradleCoverage`): the nearest build of the file, if it sits in a
     * hidden, output or linked folder or deeper than 8 folders, makes the file loose; the build above it is not used.
     */
    fun buildRoot(
        file: Path,
        windowFolder: Path,
    ): JavaBuild? {
        val folders = foldersUpTo(file, windowFolder)
        val gradle =
            folders.firstOrNull { folder -> GRADLE_SETTINGS.any { isFile(folder.resolve(it)) } }
                ?: folders.firstOrNull { folder -> hasGradleBuild(folder) }
        val mavenNearest = folders.firstOrNull { isFile(it.resolve("pom.xml")) }
        // A folder with both a pom and Gradle files counts as Gradle; otherwise the nearer build wins.
        val mavenWins =
            mavenNearest != null &&
                !hasGradleBuild(mavenNearest) &&
                (gradle == null || mavenNearest.nameCount > gradle.nameCount)
        if (mavenWins) {
            // The folders above a covered folder are covered too, so the climb stays inside the covered ones.
            if (!covered(windowFolder, mavenNearest)) return null
            return JavaBuild(mavenReactorRoot(mavenNearest, windowFolder), BuildTool.MAVEN)
        }
        return gradle?.takeIf { covered(windowFolder, it) }?.let { JavaBuild(it, BuildTool.GRADLE) }
    }

    /**
     * Whether Kotlin sources sit next to [file]'s Java: its module folder or [root] has `src/<any>/kotlin`, or a build
     * file there mentions `org.jetbrains.kotlin` or `kotlin(`.
     */
    fun kotlinNear(
        file: Path,
        root: Path,
    ): Boolean {
        val top = root.toAbsolutePath().normalize()
        val moduleFolder =
            foldersUpTo(file, top).firstOrNull { folder -> folder.hasBuildFile() } ?: top
        return listOf(moduleFolder, top).distinct().any { folder ->
            hasKotlinSources(folder) ||
                buildFilesIn(folder).any { build ->
                    readLimited(build)?.let { text -> KOTLIN_MARKERS.any(text::contains) } ==
                        true
                }
        }
    }

    /**
     * Whether a build file under [root] (pom.xml, *.gradle, *.gradle.kts, gradle/libs.versions.toml; capped walk)
     * mentions `org.projectlombok` or `lombok`.
     */
    fun usesLombok(root: Path): Boolean {
        var found = false
        walkFolders(root.toAbsolutePath().normalize()) { folder, children ->
            found =
                children.any { child ->
                    val name = child.fileName.toString()
                    val candidate =
                        name == "pom.xml" ||
                            LOMBOK_FILE_SUFFIXES.any(name::endsWith) ||
                            (name == "libs.versions.toml" && folder.fileName?.toString() == "gradle")
                    candidate && isFile(child) && readLimited(child)?.contains("lombok", ignoreCase = true) == true
                }
            !found
        }
        return found
    }

    /** Whether a change of [path] can change a build root answer or the build's import (by name only, no I/O). */
    fun isBuildFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (name in BUILD_FILE_NAMES) return true
        val inMvn = path.parent?.fileName?.toString() == ".mvn"
        return inMvn && (name == "extensions.xml" || name == "maven.config")
    }

    private fun Path.hasBuildFile() = isFile(resolve("pom.xml")) || hasGradleBuild(this)

    private fun hasGradleBuild(folder: Path) = (GRADLE_BUILDS + GRADLE_SETTINGS).any { isFile(folder.resolve(it)) }

    private fun buildFilesIn(folder: Path) =
        (listOf("pom.xml") + GRADLE_BUILDS).map { folder.resolve(it) }.filter(::isFile)

    private fun hasKotlinSources(folder: Path): Boolean {
        val sets =
            try {
                Files.newDirectoryStream(folder.resolve("src")).use { it.toList() }
            } catch (_: Exception) {
                return false
            }
        return sets.any { Files.isDirectory(it.resolve("kotlin")) }
    }

    /** From the nearest pom folder [start] up while a folder above lists the current one as a module. */
    private fun mavenReactorRoot(
        start: Path,
        windowFolder: Path,
    ): Path {
        val top = windowFolder.toAbsolutePath().normalize()
        var current = start
        while (true) current = listingFolder(current, top) ?: return current
    }

    /** The folders from [file]'s parent up to [windowFolder], both included; empty for a file outside it. */
    private fun foldersUpTo(
        file: Path,
        windowFolder: Path,
    ): List<Path> {
        val top = windowFolder.toAbsolutePath().normalize()
        val chain = generateSequence(file.toAbsolutePath().normalize().parent) { it.parent }
        return chain
            .takeWhile { it.startsWith(top) }
            .toList()
    }

    private fun isFile(path: Path): Boolean =
        try {
            Files.isRegularFile(path)
        } catch (_: Exception) {
            false
        }
}
