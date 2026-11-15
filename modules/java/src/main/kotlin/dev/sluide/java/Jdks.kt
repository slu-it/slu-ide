package dev.sluide.java

import dev.sluide.terminal.loginShellPrints
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path

data class Jdk(
    val home: Path,
    val version: String,
    val major: Int,
)

enum class JdkSource(
    internal val description: String,
) {
    PROJECT_SETTING("the project's JDK"),
    LOGIN_SHELL("the login shell's JAVA_HOME"),
    ENVIRONMENT("the JAVA_HOME of Thoreau's environment"),
    THOREAU("Thoreau's own JDK"),
}

data class ProjectJdk(
    val jdk: Jdk,
    val source: JdkSource,
    val problem: String?,
)

private const val MAX_RELEASE_BYTES = 64 * 1024
private val VERSION_LINE = Regex("""^JAVA_VERSION\s*=\s*"([^"]*)"""", RegexOption.MULTILINE)
private val MAJOR = Regex("""^(\d+)(?:\.(\d+))?""")

/**
 * The installed JDKs. Everything blocks on file-system I/O, so it runs on a worker thread, never on the FX thread.
 */
object Jdks {
    /** Folders of every system whose children are JDK homes. */
    val defaults: List<Path> = listOf(Path.of("/Library/Java/JavaVirtualMachines"), Path.of("/usr/lib/jvm"))

    /** The JDK at [home]: needs `bin/java` (a regular file) and a `release` file with `JAVA_VERSION="…"`; null otherwise. No process runs. */
    fun read(home: Path): Jdk? {
        if (!Files.isRegularFile(home.resolve("bin").resolve("java"))) return null
        val release =
            try {
                Files.newInputStream(home.resolve("release")).use { it.readNBytes(MAX_RELEASE_BYTES) }
            } catch (_: IOException) {
                return null
            }
        val version = VERSION_LINE.find(String(release, Charsets.UTF_8))?.groupValues?.get(1) ?: return null
        val major = majorOf(version) ?: return null
        return Jdk(home, version, major)
    }

    /** JDKs in well-known folders, newest major first, then by path; duplicates (same real path) once. */
    fun installed(
        userHome: Path,
        macOs: Boolean,
        systemFolders: List<Path> = defaults,
    ): List<Jdk> {
        val folders =
            systemFolders +
                listOfNotNull(userHome.resolve("Library/Java/JavaVirtualMachines").takeIf { macOs }) +
                listOf(
                    userHome.resolve(".sdkman/candidates/java"),
                    userHome.resolve(".gradle/jdks"),
                    userHome.resolve(".jdks"),
                    userHome.resolve(".asdf/installs/java"),
                )
        val jdks =
            folders.flatMap { folder ->
                val children =
                    try {
                        Files.newDirectoryStream(folder).use { it.toList() }
                    } catch (_: IOException) {
                        emptyList()
                    } catch (_: DirectoryIteratorException) {
                        emptyList()
                    }
                children.flatMap { child ->
                    listOfNotNull(child, child.resolve("Contents/Home").takeIf { macOs }).mapNotNull(::read)
                }
            }
        // A link (sdkman's `current`) is the duplicate of the folder it points to.
        return jdks
            .groupBy { realPath(it.home) }
            .values
            .map { group -> group.minWith(compareBy({ Files.isSymbolicLink(it.home) }, { it.home.toString() })) }
            .sortedWith(compareByDescending<Jdk> { it.major }.thenBy { it.home.toString() })
    }

    private fun realPath(path: Path): Path =
        try {
            path.toRealPath()
        } catch (_: IOException) {
            path.toAbsolutePath().normalize()
        }

    /** `17.0.2` is 17, `1.8.0_392` is 8, `25-ea` is 25; null if unparsable. */
    private fun majorOf(version: String): Int? {
        val match = MAJOR.find(version) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull()
        return if (first == 1 && second != null) second else first
    }
}

/**
 * Which JDK a project uses (ADR 0179). Blocks (the login shell takes a while, the JDKs are read from disk): call it
 * on a worker thread, never on the FX thread.
 */
class JavaHomes(
    private val shell: (script: String, environment: Map<String, String>) -> String? = { s, e ->
        loginShellPrints(s, e)
    },
    private val environment: Map<String, String> = System.getenv(),
    private val thoreauHome: Path = Path.of(System.getProperty("java.home")),
) {
    private var asked = false
    private var loginShellHome: Path? = null

    private fun askLoginShell(): Path? =
        synchronized(this) {
            if (!asked) {
                val answer = shell("printf '%s' \"\$JAVA_HOME\"", environment)
                // A null on an interrupted thread is the shell's cancel, not its answer: asked again next time.
                if (answer != null || !Thread.currentThread().isInterrupted) {
                    asked = true
                    loginShellHome =
                        answer?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
                }
            }
            loginShellHome
        }

    /** JAVA_HOME as the login shell sets it; asked once per instance (later calls return the cached answer, also a failed one, except a null from an interrupted thread,
     * which is asked again). */
    fun loginShellJavaHome(): Path? = askLoginShell()

    /** The JDK for a project: [projectSetting] if valid, else the login shell's JAVA_HOME, else this process's JAVA_HOME, else Thoreau's own JDK. */
    fun resolve(projectSetting: Path?): ProjectJdk? {
        var skipped: String? = null
        val candidates =
            sequenceOf<Pair<JdkSource, () -> Path?>>(
                JdkSource.PROJECT_SETTING to { projectSetting },
                JdkSource.LOGIN_SHELL to ::loginShellJavaHome,
                JdkSource.ENVIRONMENT to {
                    environment["JAVA_HOME"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        runCatching {
                            Path.of(
                                it,
                            )
                        }.getOrNull()
                    }
                },
                JdkSource.THOREAU to { thoreauHome },
            )
        for ((source, home) in candidates) {
            val path = home() ?: continue
            val jdk = read(path)
            if (jdk != null) {
                val problem = skipped?.let { "$it; using ${source.description}." }
                return ProjectJdk(jdk, source, problem)
            }
            if (skipped == null) {
                skipped =
                    "${source.description.replaceFirstChar {
                        it.uppercase()
                    }} $path is not a JDK (no bin/java or release file)"
            }
        }
        return null
    }

    private fun read(path: Path): Jdk? = Jdks.read(path)
}
