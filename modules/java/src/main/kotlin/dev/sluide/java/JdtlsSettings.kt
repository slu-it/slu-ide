package dev.sluide.java

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Gradle init script every Gradle import runs with (ADR 0179). Eclipse needs the output folders inside the
 * project, and `eclipse.classpath.baseSourceOutputDir` moves Gradle's per-source-set output below the build directory:
 * `<buildDirectory>/jdtls-bin/<source set>`. It is a Directory property in Gradle 9 and a File property in 8.x
 * (measured with 8.5, 8.14.3 and 9.5.1), and lazy, so a build that changes its build directory is followed. The default
 * output folder is a plain File, set after the build script ran.
 */
internal object GradleInitScript {
    const val FILE_NAME = "thoreau-output.gradle"

    val CONTENT: String =
        """
        allprojects {
            apply plugin: 'eclipse'
            def output = layout.buildDirectory.dir('jdtls-bin')
            def base = eclipse.classpath.hasProperty('baseSourceOutputDir') ? eclipse.classpath.baseSourceOutputDir : null
            if (base != null) {
                // A Directory property in Gradle 9, a File property in 8.x.
                if (base instanceof org.gradle.api.file.DirectoryProperty) {
                    base.set(output)
                } else {
                    base.set(output.map { it.asFile })
                }
            }
            afterEvaluate {
                eclipse.classpath.defaultOutputDir = layout.buildDirectory.dir('jdtls-bin/default').get().asFile
            }
        }
        """.trimIndent() + "\n"

    /** Writes the script into [folder] (a prepared workspace folder, never the project) unless it is already there. */
    @Throws(IOException::class)
    fun writeInto(folder: Path): Path {
        val file = folder.resolve(FILE_NAME)
        val current =
            try {
                Files.readString(file, StandardCharsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        if (current != CONTENT) Files.writeString(file, CONTENT, StandardCharsets.UTF_8)
        return file
    }
}

/** The `settings` object jdtls gets in its initialization options and in `didChangeConfiguration` (ADR 0179). */
internal object JdtlsSettings {
    /** The settings for [jdk] (null: the server's own) and the init script [initScript]. */
    fun of(
        jdk: Jdk?,
        initScript: Path,
    ): Map<String, Any?> {
        val gradle =
            buildMap<String, Any?> {
                put("enabled", true)
                put("wrapper", mapOf("enabled" to true))
                if (jdk != null) put("java", mapOf("home" to jdk.home.toString()))
                put(
                    "arguments",
                    listOf("--init-script", initScript.toString(), "-Dorg.gradle.daemon.idletimeout=900000"),
                )
            }
        val configuration =
            buildMap<String, Any?> {
                put("updateBuildConfiguration", "automatic")
                if (jdk != null) {
                    put(
                        "runtimes",
                        listOf(
                            mapOf("name" to runtimeName(jdk.major), "path" to jdk.home.toString(), "default" to true),
                        ),
                    )
                }
            }
        return mapOf(
            "java" to
                mapOf(
                    "import" to mapOf("gradle" to gradle, "maven" to mapOf("enabled" to true)),
                    "configuration" to configuration,
                    "maven" to mapOf("downloadSources" to true),
                    "eclipse" to mapOf("downloadSources" to true),
                ),
        )
    }

    /** The execution environment name: `JavaSE-1.8` for Java 8, `JavaSE-<major>` otherwise. */
    private fun runtimeName(major: Int) = if (major <= 8) "JavaSE-1.8" else "JavaSE-$major"
}
