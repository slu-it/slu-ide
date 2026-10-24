package dev.sluide.gradle.tasks

import java.nio.file.Files
import java.nio.file.Path

private const val RESOURCE = "/dev/sluide/gradle/task-list.init.gradle"

/** Writes the bundled init script (ADR 0085) into [dir] and returns the file; an existing one is replaced. */
fun extractInitScript(dir: Path): Path {
    Files.createDirectories(dir)
    val target = dir.resolve("task-list.init.gradle")
    val stream =
        object {}.javaClass.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("Missing resource $RESOURCE")
    stream.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    return target
}
