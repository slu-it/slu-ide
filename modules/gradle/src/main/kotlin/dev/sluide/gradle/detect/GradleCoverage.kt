package dev.sluide.gradle.detect

import java.nio.file.Files
import java.nio.file.Path

/**
 * Which folders of a project Gradle may run in: the folders the trust check looks at (ADR 0085). The locator uses it so
 * it never picks a Gradle the trust prompt did not cover: no hidden or skipped segment, no symbolic link, at most
 * [MAX_DEPTH] folders below the project root.
 */
internal object GradleCoverage {
    const val MAX_DEPTH = 8

    private val SKIPPED = setOf("build", "node_modules", "out")

    /** Whether a folder of this [name] is left out: hidden (`.git`, `.gradle`) or a build output or dependency folder. */
    fun skips(name: String): Boolean = name.startsWith(".") || name in SKIPPED

    /** Whether [folder] (absolute, normalized) is [root] or covered below it. */
    fun covers(
        root: Path,
        folder: Path,
    ): Boolean {
        if (!folder.startsWith(root)) return false
        val relative = root.relativize(folder)
        if (relative.toString().isEmpty()) return true
        if (relative.nameCount > MAX_DEPTH) return false
        var current = root
        for (segment in relative) {
            current = current.resolve(segment)
            if (skips(segment.toString()) || Files.isSymbolicLink(current)) return false
        }
        return true
    }
}
