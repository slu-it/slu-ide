package dev.sluide.editorarea

import javafx.scene.Node
import java.nio.file.Path

/**
 * The [path] of a file as questions show it: relative to the project [folder] if it is within it, else in full. A
 * sibling folder that only shares the name's start (`app2` next to `app`) is outside.
 */
internal fun shownPath(
    path: Path,
    folder: Path?,
): String {
    val base = folder?.toAbsolutePath()?.normalize()
    return if (base != null && path.startsWith(base)) base.relativize(path).toString() else path.toString()
}

/** Whether this node is [ancestor] or within it. */
internal fun Node.isInside(ancestor: Node): Boolean = generateSequence(this) { it.parent }.any { it === ancestor }
