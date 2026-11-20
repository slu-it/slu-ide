package dev.sluide.jshell.engine

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The agent's class path on disk (ADR 0191): the module jar carries `agent.jar` and `kotlin-stdlib.jar` as resources
 * (`jshell-agent/`), and a JVM can only run them as files.
 */
internal object AgentFiles {
    /** The resources, in class-path order. */
    val NAMES = listOf("agent.jar", "kotlin-stdlib.jar")

    /**
     * Copies the agent files to `<root>/<first 16 hex digits of their SHA-256>/` unless they are there already, and
     * returns them in class-path order. Each file is written to a temporary file in that folder and moved into place
     * atomically, so two windows installing at once both end with whole files. Blocks on file I/O: worker thread only.
     *
     * @throws java.io.IOException if a resource is missing or a file cannot be written
     */
    fun install(root: Path = Path.of(System.getProperty("user.home"), ".sluide", "jshell")): List<Path> =
        install(root) { name -> AgentFiles::class.java.getResourceAsStream("/jshell-agent/$name") }

    internal fun install(
        root: Path,
        resource: (String) -> InputStream?,
    ): List<Path> {
        val contents =
            NAMES.map { name ->
                val bytes =
                    resource(name)?.use { it.readAllBytes() }
                        ?: throw IOException("The JShell agent's $name is missing from Thoreau")
                name to bytes
            }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in contents) {
            // name and length first, so that the same bytes split differently between the files are other content
            digest.update("$name\u0000${bytes.size}\u0000".toByteArray())
            digest.update(bytes)
        }
        val folder = Files.createDirectories(root.resolve(HexFormat.of().formatHex(digest.digest()).take(16)))
        return contents.map { (name, bytes) ->
            val target = folder.resolve(name)
            if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                val temporary = Files.createTempFile(folder, name, ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            target
        }
    }
}
