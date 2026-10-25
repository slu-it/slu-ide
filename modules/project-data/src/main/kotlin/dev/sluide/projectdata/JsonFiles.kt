package dev.sluide.projectdata

import tools.jackson.core.JacksonException
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

private val log = System.getLogger(ProjectDataStore::class.java.name)

/** The format version this code writes; files with a greater one are read but never written. */
internal const val VERSION = 1
internal const val VERSION_FIELD = "version"
internal const val PATH_FIELD = "path"
internal const val FILES_FIELD = "files"

internal val mapper: JsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

/** What reading a JSON file gave: its object, no file, or a file that cannot be read or is no JSON object. */
internal sealed interface JsonRead {
    data class Found(
        val node: ObjectNode,
    ) : JsonRead

    data object Missing : JsonRead

    data class Unreadable(
        val cause: Exception?,
    ) : JsonRead
}

internal fun readJson(file: Path): JsonRead {
    val bytes =
        try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return JsonRead.Missing
        } catch (e: IOException) {
            return JsonRead.Unreadable(e)
        }
    val node =
        try {
            mapper.readTree(bytes)
        } catch (e: JacksonException) {
            return JsonRead.Unreadable(e)
        }
    return if (node is ObjectNode) JsonRead.Found(node) else JsonRead.Unreadable(null)
}

/** Whether this code may write [this] tree: its version is missing or not greater than [VERSION]. */
internal fun ObjectNode.writable(): Boolean {
    val version = get(VERSION_FIELD) ?: return true
    return version.isNumber && version.doubleValue() <= VERSION
}

/** Writes [node] to [file] through a temporary file in the same folder; problems are logged, `false` then. */
internal fun writeJson(
    file: Path,
    node: ObjectNode,
): Boolean =
    try {
        val parent = file.toAbsolutePath().parent
        parent.createDirectories()
        // The suffix keeps temporary files out of the scan for `*.json`.
        val temporary = Files.createTempFile(parent, file.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, mapper.writeValueAsBytes(node))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        true
    } catch (e: IOException) {
        log.log(System.Logger.Level.WARNING, "Cannot save the project data to $file", e)
        false
    } catch (e: JacksonException) {
        log.log(System.Logger.Level.WARNING, "Cannot save the project data to $file", e)
        false
    }
