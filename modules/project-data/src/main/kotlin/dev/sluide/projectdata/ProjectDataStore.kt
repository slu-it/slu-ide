package dev.sluide.projectdata

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Properties
import java.util.UUID
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists

private val log = System.getLogger(ProjectDataStore::class.java.name)

/**
 * The metadata of every project, one JSON file per project in [folder] (by default `~/.sluide/projects`), named by a
 * random UUID and found by its `path`; the per-file data of files outside any project lives in [outsideFile] (by default
 * `~/.sluide/outside-projects.json`), keyed by absolute path (ADR 0090).
 *
 * The store owns `version`, `path` and `files`; all other fields belong to the modules that write them and are kept as
 * they are. Before the first access, the [migrations] move the old properties files in. Problems with the files are
 * logged, never thrown; a file that cannot be read, or has a newer version, is never replaced. Thread-safe: every
 * method holds the store's lock.
 *
 * Folders and files are compared as `toAbsolutePath().normalize()`, without resolving symbolic links or case: a project
 * opened through a link or in other case is another project, and per-file keys are exact strings.
 */
@Component
class ProjectDataStore(
    internal val folder: Path,
    internal val outsideFile: Path,
    private val migrations: List<LegacyProjectData>,
) {
    @Autowired
    constructor(migrations: List<LegacyProjectData>) : this(
        Path.of(System.getProperty("user.home"), ".sluide", "projects"),
        Path.of(System.getProperty("user.home"), ".sluide", "outside-projects.json"),
        migrations,
    )

    private var migrated = false

    /** Warnings already logged, so that a broken file is not reported on every access. */
    private val warned = mutableSetOf<String>()

    /** Every remembered project, by folder. */
    @Synchronized
    fun projects(): List<ProjectData> {
        migrateLegacyFiles()
        return scan()
            .projects
            .map { (folder, files) -> ProjectData(folder, files.first().node.deepCopy()) }
            .sortedBy { it.folder.toString() }
    }

    /** A copy of the data of the project in [folder]; `null` if there is none. */
    @Synchronized
    fun read(folder: Path): ObjectNode? {
        migrateLegacyFiles()
        return scan().newest(normalized(folder))?.node?.deepCopy()
    }

    /**
     * Applies [change] to the data of the project in [folder], creating its file if needed; `true` if it was saved.
     * [change] runs under the store's lock, on the caller's thread: it must not take other locks or call back into the
     * store (a write nested in it would be overwritten by this one).
     */
    @Synchronized
    fun update(
        folder: Path,
        change: (ObjectNode) -> Unit,
    ): Boolean {
        migrateLegacyFiles()
        return editProject(normalized(folder), create = true, change)
    }

    /** Forgets the project in [folder]: deletes its file(s); the folder itself is never touched. */
    @Synchronized
    fun remove(folder: Path) {
        migrateLegacyFiles()
        scan().projects[normalized(folder)].orEmpty().forEach { delete(it.file) }
    }

    /** A copy of the data of [file], kept in the file of [projectFolder] if it lies under it; `null` if there is none. */
    @Synchronized
    fun fileData(
        file: Path,
        projectFolder: Path?,
    ): ObjectNode? {
        migrateLegacyFiles()
        val location = locate(file, projectFolder)
        return (root(location.project)?.get(FILES_FIELD)?.get(location.key) as? ObjectNode)?.deepCopy()
    }

    /**
     * Applies [change] to the data of [file] (see [fileData]), dropping it when it ends up empty; `true` if saved.
     * [change] runs under the store's lock, on the caller's thread: it must not take other locks or call back into the
     * store (a write nested in it would be overwritten by this one).
     */
    @Synchronized
    fun updateFileData(
        file: Path,
        projectFolder: Path?,
        change: (ObjectNode) -> Unit,
    ): Boolean {
        migrateLegacyFiles()
        val location = locate(file, projectFolder)
        return editFiles(location.project, create = true) { files ->
            val data = files.get(location.key) as? ObjectNode ?: files.putObject(location.key)
            change(data)
            if (data.isEmpty) files.remove(location.key)
        }
    }

    /**
     * Moves the data of [from] and of everything under it to [to], in the file of [projectFolder] and in the outside
     * file; an entry goes to the file its new path belongs to. Data stored for [to] and under it before is dropped in
     * both files, also when [from] has none, so a moved file never inherits stale data. The target is written first,
     * so a failure duplicates an entry rather than losing it. When [from] is the project folder itself, its entries go
     * to the outside file (the explorer cannot move the project root).
     */
    @Synchronized
    fun moved(
        from: Path,
        to: Path,
        projectFolder: Path?,
    ) {
        migrateLegacyFiles()
        val source = normalized(from)
        val target = normalized(to)
        val project = projectFolder?.let(::normalized)
        val moves =
            containers(project).flatMap { container ->
                entries(container).mapNotNull { (key, value) ->
                    val file = fileOf(container, key)?.takeIf { it.startsWith(source) } ?: return@mapNotNull null
                    Move(container, key, locate(target.resolve(source.relativize(file)), project), value)
                }
            }
        // first the files the entries move into, each without its stale data at the target and with the moves within
        // the file; then the other files, only without their stale data
        val written =
            containers(project)
                .sortedBy { container -> if (moves.any { it.target.project == container }) 0 else 1 }
                .associateWith { container ->
                    val into = moves.filter { it.target.project == container }
                    editFiles(container, create = into.isNotEmpty()) { files ->
                        files
                            .propertyNames()
                            .filter { fileOf(container, it)?.startsWith(target) == true }
                            .forEach { files.remove(it) }
                        into.filter { it.container == container }.forEach { files.remove(it.key) }
                        into.forEach { files.set(it.target.key, it.value.deepCopy()) }
                    }
                }
        // then the moves out of a file, if their target was saved
        moves
            .filter { it.container != it.target.project && written[it.target.project] == true }
            .groupBy { it.container }
            .forEach { (container, out) ->
                editFiles(container, create = false) { files -> out.forEach { files.remove(it.key) } }
            }
    }

    /** Forgets the data of [path] and of everything under it, in the file of [projectFolder] and in the outside file. */
    @Synchronized
    fun deleted(
        path: Path,
        projectFolder: Path?,
    ) {
        migrateLegacyFiles()
        val gone = normalized(path)
        containers(projectFolder?.let(::normalized)).forEach { container ->
            editFiles(container, create = false) { files ->
                files
                    .propertyNames()
                    .filter { fileOf(container, it)?.startsWith(gone) == true }
                    .forEach { files.remove(it) }
            }
        }
    }

    private class Move(
        val container: Path?,
        val key: String,
        val target: FileLocation,
        val value: JsonNode,
    )

    /** The project's file, then the outside file. */
    private fun containers(project: Path?): List<Path?> = listOf(project, null).distinct()

    /** The stored data of [project], or of the outside file if `null`; `null` if there is none or it is unreadable. */
    private fun root(project: Path?): ObjectNode? =
        if (project != null) scan().newest(project)?.node else (readJson(outsideFile) as? JsonRead.Found)?.node

    /** The per-file entries of the file of [project], or of the outside file if `null`. */
    private fun entries(project: Path?): List<Pair<String, JsonNode>> {
        val root = root(project)
        val files = root?.get(FILES_FIELD) as? ObjectNode ?: return emptyList()
        return files.properties().map { it.key to it.value }
    }

    /** Applies [change] to the `files` object of [project]'s file, or of the outside file if `null`. */
    private fun editFiles(
        project: Path?,
        create: Boolean,
        change: (ObjectNode) -> Unit,
    ): Boolean {
        val changeFiles = { root: ObjectNode ->
            val files = root.get(FILES_FIELD) as? ObjectNode ?: root.putObject(FILES_FIELD)
            change(files)
            if (files.isEmpty) root.remove(FILES_FIELD)
        }
        return if (project != null) editProject(project, create, changeFiles) else editOutside(create, changeFiles)
    }

    private fun editProject(
        folder: Path,
        create: Boolean,
        change: (ObjectNode) -> Unit,
    ): Boolean {
        val existing = scan().newest(folder)
        if (existing == null) {
            if (!create) return true
            val root = mapper.createObjectNode().put(VERSION_FIELD, VERSION).put(PATH_FIELD, folder.toString())
            change(root)
            root.put(VERSION_FIELD, VERSION).put(PATH_FIELD, folder.toString())
            return writeJson(this.folder.resolve("${UUID.randomUUID()}.json"), root)
        }
        if (!existing.node.writable()) {
            log.log(System.Logger.Level.WARNING, "Not replacing ${existing.file}, written by a newer version")
            return false
        }
        val root = existing.node.deepCopy()
        change(root)
        root.set(PATH_FIELD, existing.node.get(PATH_FIELD))
        root.put(VERSION_FIELD, VERSION)
        return root == existing.node || writeJson(existing.file, root)
    }

    private fun editOutside(
        create: Boolean,
        change: (ObjectNode) -> Unit,
    ): Boolean {
        val existing =
            when (val read = readJson(outsideFile)) {
                is JsonRead.Found -> {
                    read.node
                }

                JsonRead.Missing -> {
                    if (create) mapper.createObjectNode().put(VERSION_FIELD, VERSION) else return true
                }

                is JsonRead.Unreadable -> {
                    warnOnce("Not replacing the unreadable $outsideFile", read.cause)
                    return false
                }
            }
        if (!existing.writable()) {
            log.log(System.Logger.Level.WARNING, "Not replacing $outsideFile, written by a newer version")
            return false
        }
        val root = existing.deepCopy()
        change(root)
        root.put(VERSION_FIELD, VERSION)
        return root == existing || writeJson(outsideFile, root)
    }

    private class Stored(
        val file: Path,
        val node: ObjectNode,
        val modified: FileTime,
    )

    /**
     * The readable project files by normalized folder, newest first. Unreadable files and files without a `path` are
     * left out: they can be matched to no folder, so they are never replaced or deleted, and block no other project.
     */
    private class Scan(
        val projects: Map<Path, List<Stored>>,
    ) {
        fun newest(folder: Path) = projects[folder]?.first()
    }

    private fun scan(): Scan {
        if (!folder.isDirectory()) return Scan(emptyMap())
        val files =
            try {
                Files.list(folder).use { list ->
                    list.filter { it.name.endsWith(".json") && it.isRegularFile() }.toList()
                }
            } catch (e: IOException) {
                warnOnce("Cannot list the project data in $folder", e)
                return Scan(emptyMap())
            }
        val stored = mutableListOf<Pair<Path, Stored>>()
        for (file in files) {
            when (val read = readJson(file)) {
                JsonRead.Missing -> {}

                is JsonRead.Unreadable -> {
                    warnOnce("Cannot read the project data in $file, skipping it", read.cause)
                }

                is JsonRead.Found -> {
                    val project = projectFolder(read.node)
                    if (project == null) {
                        warnOnce("No project path in $file, skipping it")
                    } else {
                        stored.add(project to Stored(file, read.node, modified(file)))
                    }
                }
            }
        }
        val projects =
            stored.groupBy({ it.first }, { it.second }).mapValues { (project, copies) ->
                if (copies.size > 1) warnOnce("Several files for $project: ${copies.map { it.file }}, the newest wins")
                copies.sortedWith(compareByDescending<Stored> { it.modified }.thenBy { it.file.name })
            }
        return Scan(projects)
    }

    private fun projectFolder(node: ObjectNode): Path? {
        val path = node.get(PATH_FIELD)?.takeIf { it.isString }?.stringValue() ?: return null
        return fileOf(null, path)
    }

    private fun modified(file: Path): FileTime =
        try {
            Files.getLastModifiedTime(file)
        } catch (_: IOException) {
            FileTime.fromMillis(0)
        }

    private fun delete(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete the project data $file", e)
        }
    }

    private fun warnOnce(
        message: String,
        cause: Exception? = null,
    ) {
        if (warned.add(message)) log.log(System.Logger.Level.WARNING, message, cause)
    }

    /**
     * Runs the [migrations] once per store, before anything is read or written: an old file is deleted once its
     * migration wrote everything, else kept for the next start.
     */
    private fun migrateLegacyFiles() {
        if (migrated) return
        migrated = true
        for (migration in migrations) {
            val file = migration.file
            if (file.notExists()) continue
            val properties = Properties()
            try {
                file.inputStream().use { properties.load(it) }
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "Cannot read $file to migrate it, keeping it", e)
                continue
            } catch (e: IllegalArgumentException) {
                // a malformed \uXXXX escape
                log.log(System.Logger.Level.WARNING, "Cannot read $file to migrate it, keeping it", e)
                continue
            }
            val entries = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
            val complete =
                try {
                    migration.migrate(entries, this)
                } catch (e: RuntimeException) {
                    log.log(System.Logger.Level.WARNING, "Migrating $file failed", e)
                    false
                }
            if (complete) {
                delete(file)
                log.log(System.Logger.Level.INFO, "Migrated $file into the project data")
            } else {
                log.log(System.Logger.Level.WARNING, "Could not migrate all of $file, keeping it for the next start")
            }
        }
    }
}
