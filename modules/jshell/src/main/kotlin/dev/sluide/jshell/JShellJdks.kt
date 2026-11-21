package dev.sluide.jshell

import dev.sluide.java.JavaHomes
import dev.sluide.java.Jdk
import dev.sluide.java.Jdks
import dev.sluide.projectdata.ProjectDataStore
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The JDK a JShell file runs on: [jdk] null when no JDK with JShell was found; [source] how the user sees where it
 * came from ("chosen for this file", "the project's JDK", …, "none"); [problem] why a JDK before it in line was skipped.
 */
data class JShellJdk(
    val jdk: Jdk?,
    val source: String,
    val problem: String?,
)

/**
 * The JDK of each JShell file (ADR 0191): the one chosen for the file, kept in the field `jshell` of its data
 * (`{"jdk": "<home>"}`, [ProjectDataStore.updateFileData]; a move of the file takes it along), else the project's JDK
 * chain ([JavaHomes.resolve]) without the JDKs that have no JShell. [projectSetting] gives the project's own JDK
 * setting: `JavaProjectSettings.jdk(windowFolder)`. Blocks on file I/O and the login shell: worker thread only.
 */
class JShellJdks(
    private val store: ProjectDataStore,
    private val javaHomes: JavaHomes,
    private val projectSetting: () -> Path?,
) {
    /** The JDK home chosen for [file], null for the project's JDK. */
    fun remembered(
        file: Path,
        projectFolder: Path?,
    ): Path? {
        val home =
            store
                .fileData(file, projectFolder)
                ?.get(FIELD)
                ?.get(JDK)
                ?.takeIf { it.isString }
                ?.asString()
        if (home == null || home.isBlank()) return null
        return try {
            Path.of(home)
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** Chooses [home] for [file]; null goes back to the project's JDK and removes the field. */
    fun remember(
        file: Path,
        projectFolder: Path?,
        home: Path?,
    ) {
        store.updateFileData(file, projectFolder) { data ->
            data.remove(FIELD)
            if (home != null) data.putObject(FIELD).put(JDK, home.toString())
        }
    }

    /** The JDK [file] runs on: the chosen one if it still is a JDK with JShell, else the project's chain. */
    fun resolve(
        file: Path,
        projectFolder: Path?,
    ): JShellJdk {
        val chosen = remembered(file, projectFolder)
        if (chosen != null) {
            val jdk = Jdks.read(chosen)
            if (jdk != null && Jdks.hasJShell(jdk)) return JShellJdk(jdk, CHOSEN, null)
        }
        val gone = chosen?.let { "The JDK chosen for this file, $it, is gone or has no JShell" }
        val chain =
            javaHomes.resolve(projectSetting(), Jdks::hasJShell)
                ?: return JShellJdk(null, NONE, gone?.let { "$it; no other JDK with JShell was found." } ?: NOTHING)
        return JShellJdk(
            chain.jdk,
            chain.source.description,
            gone?.let { "$it; using ${chain.source.description}." } ?: chain.problem,
        )
    }

    /** The JDK the project's chain gives, whatever is chosen for a file: what "Project JDK" in the JDK menu means. */
    fun resolveProject(): JShellJdk {
        val chain = javaHomes.resolve(projectSetting(), Jdks::hasJShell) ?: return JShellJdk(null, NONE, NOTHING)
        return JShellJdk(chain.jdk, chain.source.description, chain.problem)
    }

    internal companion object {
        const val FIELD = "jshell"
        const val JDK = "jdk"

        /** The [JShellJdk.source] of a file's own choice. */
        const val CHOSEN = "chosen for this file"
        const val NONE = "none"
        const val NOTHING =
            "No JDK with JShell (JDK 9 or newer) was found: choose one in the project settings or set JAVA_HOME."
    }
}
