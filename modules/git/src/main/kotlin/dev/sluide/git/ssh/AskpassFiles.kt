package dev.sluide.git.ssh

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The askpass helper on disk (ADR 0194): the module jar carries `askpass.jar` and `kotlin-stdlib.jar` as resources
 * (`git-askpass/`), and ssh runs a program file: the wrapper script `askpass`, which starts them on Thoreau's `java`.
 */
internal object AskpassFiles {
    /** The resources, in class-path order. */
    val NAMES = listOf("askpass.jar", "kotlin-stdlib.jar")

    /** The wrapper script's name. */
    const val SCRIPT = "askpass"

    /**
     * Copies the helper's jars and writes its wrapper script, which runs them with [java], to `<root>/<first 16 hex
     * digits of the SHA-256 of the jars and the java path>/` unless they are there already; returns the script. Each
     * file is written to a temporary file in that folder and moved into place atomically, so two windows installing at
     * once both end with whole files; the script is executable by the user only. Blocks on file I/O.
     *
     * @throws java.io.IOException if a resource is missing or a file cannot be written
     */
    fun install(
        root: Path = Path.of(System.getProperty("user.home"), ".sluide", "git", "askpass"),
        java: Path = Path.of(System.getProperty("java.home"), "bin", "java"),
    ): Path =
        install(root, java, linux = System.getProperty("os.name").startsWith("Linux")) { name ->
            AskpassFiles::class.java.getResourceAsStream("/git-askpass/$name")
        }

    internal fun install(
        root: Path,
        java: Path,
        linux: Boolean,
        resource: (String) -> InputStream?,
    ): Path {
        val contents =
            NAMES.map { name ->
                val bytes =
                    resource(name)?.use { it.readAllBytes() }
                        ?: throw IOException("The git askpass helper's $name is missing from Thoreau")
                name to bytes
            }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((_, bytes) in contents) {
            // The length first, so that the same bytes split differently between the files are other content.
            digest.update("${bytes.size}\u0000".toByteArray())
            digest.update(bytes)
        }
        digest.update("java\u0000$java".toByteArray())
        val folder = Files.createDirectories(root.resolve(HexFormat.of().formatHex(digest.digest()).take(16)))
        val jars = contents.map { (name, bytes) -> folder.resolve(name).also { place(it, bytes, executable = false) } }
        val script = wrapperScript(java, jars, linux).toByteArray()
        return folder.resolve(SCRIPT).also { place(it, script, executable = true) }
    }

    /** Writes [bytes] to [target] through a temporary file unless it has them (and is [executable] if it must be). */
    private fun place(
        target: Path,
        bytes: ByteArray,
        executable: Boolean,
    ) {
        val permissions = if (executable) OWNER_ONLY else null
        val whole = Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)
        if (whole && (permissions == null || Files.getPosixFilePermissions(target) == permissions)) return
        val attributes = listOfNotNull(permissions?.let(PosixFilePermissions::asFileAttribute)).toTypedArray()
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp", *attributes)
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * The wrapper script: `exec`s [java] with the [classPath] and the helper's main class, passing ssh's arguments on.
     * `exec`, so that no shell stays behind holding ssh's pipe and the helper's parent is ssh. Each value is quoted. On
     * [linux] java runs with `LC_ALL=C.UTF-8`, so that it decodes ssh's prompt (a key path) as UTF-8 also where the
     * user's locale is not; macOS's JVM always does. `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` are
     * unset: they are meant for the user's own JVMs, and the JVM would print "Picked up …" for each into ssh's and so
     * git's error output, which the git log shows.
     */
    internal fun wrapperScript(
        java: Path,
        classPath: List<Path>,
        linux: Boolean,
    ): String {
        val path = quoted(classPath.joinToString(":"))
        val locale = if (linux) "LC_ALL=C.UTF-8 " else ""
        return "#!/bin/sh\nunset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS\n${locale}exec ${quoted(
            java.toString(),
        )} -cp $path dev.sluide.git.askpass.Askpass \"\$@\"\n"
    }

    /** [value] in single quotes for `/bin/sh`, each `'` in it as `'\''`. */
    private fun quoted(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
}
