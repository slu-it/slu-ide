package dev.sluide.java

import java.nio.file.Path

/**
 * Whether [file] is jdtls's log, `<root>/<workspace>/data/.metadata/.log`, with [root] the jdtls workspaces root
 * (`JavaWorkspaces.root`, ADR 0179); both paths are made absolute and normalized first.
 */
fun isJdtlsLog(
    file: Path,
    root: Path,
): Boolean {
    val path = file.toAbsolutePath().normalize()
    val metadata = path.parent
    val data = metadata?.parent
    val workspace = data?.parent
    return path.fileName?.toString() == ".log" &&
        metadata?.fileName?.toString() == ".metadata" &&
        data?.fileName?.toString() == "data" &&
        workspace?.parent == root.toAbsolutePath().normalize()
}
