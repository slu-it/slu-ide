package dev.sluide.gradle.trust

import dev.sluide.gradle.detect.GradleCoverage
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustFinding
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/**
 * What Gradle would run in a project (ADR 0085): listing or running tasks downloads and runs a wrapper's distribution
 * and executes the build scripts. One finding per wrapper, and one per folder with build scripts and no wrapper in it
 * or above it. It covers the folders of [GradleCoverage], which the locator uses too. It is not checkable (`null`) if
 * the walk is interrupted or reaches the folder cap. It only reads file names and the wrapper files, and runs nothing.
 */
@Component
class GradleTrustCheck internal constructor(
    private val maxFolders: Int,
) : ProjectTrustCheck {
    @Autowired
    constructor() : this(MAX_FOLDERS)

    override val id = ID

    override val name = "Gradle"

    override fun check(folder: Path): List<TrustFinding>? {
        val top = folder.toAbsolutePath().normalize()
        if (!Files.isDirectory(top)) return null
        val findings = mutableListOf<TrustFinding>()
        var visited = 0
        var complete = true

        fun walk(
            dir: Path,
            depth: Int,
            wrapperAbove: Boolean,
            scriptsAbove: Boolean,
        ) {
            if (!complete) return
            if (Thread.currentThread().isInterrupted || ++visited > maxFolders) {
                complete = false
                return
            }
            val children =
                try {
                    Files.list(dir).use { it.toList() }
                } catch (_: IOException) {
                    if (dir == top) complete = false
                    return
                }
            val names = children.map { it.fileName.toString() }.toSet()
            val relative = top.relativize(dir).toString().ifEmpty { "." }
            val hasWrapper = "gradlew" in names && Files.isRegularFile(dir.resolve(WRAPPER_PROPERTIES))
            val hasScripts = names.any { it in BUILD_FILES }
            // Every wrapper counts, also one below another: it runs its own distribution. Build files without a
            // wrapper of their own belong to the build root above them.
            if (hasWrapper) {
                findings += wrapperFinding(dir, relative)
            } else if (hasScripts && !wrapperAbove && !scriptsAbove) {
                findings +=
                    TrustFinding(
                        "Gradle build scripts in $relative",
                        "run by the global gradle when tasks are listed or run",
                    )
            }
            if (depth >= GradleCoverage.MAX_DEPTH) return
            for (child in children.sorted()) {
                if (GradleCoverage.skips(child.fileName.toString())) continue
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                walk(child, depth + 1, wrapperAbove || hasWrapper, scriptsAbove || hasScripts)
            }
        }
        walk(top, 0, false, false)
        return findings.takeIf { complete }
    }

    /** The finding of the wrapper in [dir]; a fixed one if its files cannot be read. */
    private fun wrapperFinding(
        dir: Path,
        relative: String,
    ): TrustFinding =
        try {
            val url =
                Properties()
                    .also { props -> Files.newBufferedReader(dir.resolve(WRAPPER_PROPERTIES)).use(props::load) }
                    .getProperty("distributionUrl")
                    ?.trim()
                    .orEmpty()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(Files.readAllBytes(dir.resolve("gradlew")))
            digest.update(0)
            val jar = dir.resolve(WRAPPER_JAR)
            if (Files.isRegularFile(jar)) digest.update(Files.readAllBytes(jar))
            digest.update(0)
            digest.update(url.toByteArray())
            TrustFinding(
                "Gradle wrapper in $relative",
                "downloads and runs ${url.ifEmpty { "its Gradle distribution" }} when tasks are listed or run",
                "gradle-wrapper\u0000$relative\u0000" + digest.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (_: IOException) {
            TrustFinding("Gradle wrapper in $relative", "could not be read", "gradle-wrapper-unreadable\u0000$relative")
        }

    companion object {
        /** The id the Gradle view asks [dev.sluide.projecttrust.ProjectTrust.allows] with. */
        const val ID = "gradle"

        private const val WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"
        private const val WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar"
        private const val MAX_FOLDERS = 20_000
        private val BUILD_FILES = setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    }
}
