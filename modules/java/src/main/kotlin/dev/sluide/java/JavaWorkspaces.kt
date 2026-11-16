package dev.sluide.java

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

data class PreparedWorkspace(
    val folder: Path,
    val data: Path,
    val config: Path,
)

data class WorkspaceInfo(
    val folder: Path,
    val projectPath: Path?,
    val bytes: Long,
    val lastUsed: Instant?,
)

private const val PROJECT_PATH_FILE = "project-path.txt"
private const val LAST_USED_FILE = "last-used"
private const val CONFIG_PREFIX = "config-"
private const val MAX_NAME = 40
private const val HASH_DIGITS = 12
private const val MAX_SMALL_FILE_BYTES = 64 * 1024

/**
 * jdtls's data folders, one per window folder, below [root] (ADR 0179). Everything blocks on file-system I/O: call it on
 * a worker thread, never on the FX thread. Copies, sizes and deletes never follow links.
 */
open class JavaWorkspaces(
    val root: Path = Path.of(System.getProperty("user.home"), ".sluide", "jdtls", "workspaces"),
) {
    /**
     * `<root>/<name>-<first 12 hex of sha256 of the window folder's absolute normalized path string>`; name = the
     * folder's file name reduced to `[A-Za-z0-9._-]` (others `_`), at most 40 characters, `project` if empty.
     */
    fun folderFor(windowFolder: Path): Path {
        val absolute = windowFolder.toAbsolutePath().normalize()
        val name = sanitize(absolute.fileName?.toString().orEmpty()).take(MAX_NAME).ifEmpty { "project" }
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(absolute.toString().toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(HASH_DIGITS)
        return root.resolve("$name-$hash")
    }

    /**
     * Creates `data/`, writes `project-path.txt` (the window folder's path) and a `last-used` timestamp, and copies
     * [configSource] (jdtls's `config_<os>`) to `config-<configKey>/` unless it exists; removes other `config-*` folders.
     */
    fun prepare(
        windowFolder: Path,
        configSource: Path,
        configKey: String,
    ): PreparedWorkspace {
        val folder = folderFor(windowFolder)
        val data = Files.createDirectories(folder.resolve("data"))
        Files.writeString(folder.resolve(PROJECT_PATH_FILE), windowFolder.toAbsolutePath().normalize().toString())
        Files.writeString(folder.resolve(LAST_USED_FILE), Instant.now().toString())
        val config = folder.resolve(CONFIG_PREFIX + sanitize(configKey))
        if (!Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            // Copied beside it first, so an interrupted copy never looks like a complete config.
            val partial =
                folder.resolve(
                    "$CONFIG_PREFIX${config.fileName.toString().removePrefix(CONFIG_PREFIX)}.partial",
                )
            deleteTree(partial)
            copyTree(configSource.toRealPath(), partial)
            Files.move(partial, config, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.newDirectoryStream(folder, "$CONFIG_PREFIX*").use { stream ->
            stream.filter { it != config }.forEach(::deleteTree)
        }
        return PreparedWorkspace(folder, data, config)
    }

    /** All workspaces with their project path (null if unreadable), recursive size (no link following) and last use; any order. */
    fun list(): List<WorkspaceInfo> {
        val children =
            try {
                Files.newDirectoryStream(root).use { stream -> stream.toList() }
            } catch (_: IOException) {
                return emptyList()
            } catch (_: DirectoryIteratorException) {
                return emptyList()
            }
        return children
            .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .map { folder ->
                WorkspaceInfo(
                    folder,
                    readSmall(folder.resolve(PROJECT_PATH_FILE))?.takeIf { it.isNotBlank() }?.let {
                        runCatching { Path.of(it) }.getOrNull()
                    },
                    sizeOf(folder),
                    readSmall(folder.resolve(LAST_USED_FILE))?.let {
                        try {
                            Instant.parse(it.trim())
                        } catch (_: DateTimeParseException) {
                            null
                        }
                    },
                )
            }
    }

    /** Deletes [folder] recursively; refuses (IllegalArgumentException) a folder that is not a direct child of [root]; never follows links. */
    fun delete(folder: Path) {
        val normalized = folder.toAbsolutePath().normalize()
        require(normalized.parent == root.toAbsolutePath().normalize()) {
            "$folder is not a workspace folder of $root"
        }
        deleteTree(normalized)
    }

    /**
     * Deletes `data/` of the workspace of [windowFolder] and nothing else of it (`project-path.txt`, the config copy
     * and the init script stay); never follows links; nothing if there is no data folder.
     */
    open fun deleteData(windowFolder: Path) = deleteTree(folderFor(windowFolder).resolve("data"))

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readSmall(file: Path): String? =
        try {
            Files.newInputStream(file).use { it.readNBytes(MAX_SMALL_FILE_BYTES) }.toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }

    private fun sizeOf(folder: Path): Long {
        var total = 0L
        try {
            Files.walkFileTree(
                folder,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ) = FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            // What was counted so far stands.
        }
        return total
    }

    private fun copyTree(
        source: Path,
        target: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file).toString()),
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
