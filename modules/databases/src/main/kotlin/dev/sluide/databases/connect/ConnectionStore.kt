package dev.sluide.databases.connect

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(ConnectionStore::class.java.name)

/** The field of the project data (or the shared file) that holds the connections (ADR 0121). */
internal const val DATABASES_FIELD = "databases"

private const val CONNECTIONS = "connections"

private val KNOWN_KEYS =
    setOf(
        "id",
        "name",
        "kind",
        "host",
        "port",
        "database",
        "user",
        "readOnly",
        "commitMode",
        "sslMode",
        "caFile",
        "customUrl",
        "driverProperties",
        "color",
        "showSystemSchemas",
    )

/**
 * The connections of a project, kept in its project data (ADR 0090) or, without a project, in the shared file, as
 * `databases: {"connections": [...]}` in their order. Only values that differ from the [ConnectionConfig] defaults are
 * written (`id`, `name` and `kind` always); enums by name; never a password. Unknown keys of the `databases` object and
 * of a connection (matched by `id`) survive a rewrite. An entry that cannot be read (unknown kind, no id or name) is
 * skipped on load and kept unchanged, after the others, on save. A small synchronized JSON file: called on any thread.
 * Never throws: failures are logged.
 */
@Component
class ConnectionStore(
    private val store: ProjectDataStore,
) {
    /** The connections of the project in their order; shared data when [folder] is null. Never throws (logs). */
    fun load(folder: Path?): List<ConnectionConfig> =
        try {
            val root = if (folder != null) store.read(folder) else store.readShared()
            val entries = (root?.get(DATABASES_FIELD) as? ObjectNode)?.get(CONNECTIONS)
            if (entries != null && entries.isArray) {
                entries
                    .values()
                    .mapNotNull { entry -> (entry as? ObjectNode)?.let(::read) ?: skipped() }
                    .distinctBy { it.id }
            } else {
                emptyList()
            }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot read the database connections", e)
            emptyList()
        }

    /**
     * Replaces the whole list (order = list order), so the caller must hold the current one (the tab model owns it per
     * window). `true` if stored; `false` (logged) if not. Never throws.
     */
    fun save(
        folder: Path?,
        connections: List<ConnectionConfig>,
    ): Boolean {
        val edit = { root: ObjectNode -> change(root, connections) }
        return try {
            if (folder != null) store.update(folder, edit) else store.updateShared(edit)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot store the database connections", e)
            false
        }
    }

    private fun skipped(): ConnectionConfig? {
        log.log(System.Logger.Level.WARNING, "Skipping a database connection entry that cannot be read")
        return null
    }

    private fun change(
        root: ObjectNode,
        connections: List<ConnectionConfig>,
    ) {
        val databases = (root.get(DATABASES_FIELD) as? ObjectNode) ?: mapper.createObjectNode()
        val old =
            databases
                .get(CONNECTIONS)
                ?.takeIf { it.isArray }
                ?.values()
                ?.toList() ?: emptyList()
        val readable = old.filterIsInstance<ObjectNode>().filter { read(it) != null }
        val oldById = readable.associateBy { it.get("id").stringValue() }
        val unreadable = old.filter { it !in readable }
        val array = mapper.createArrayNode()
        for (config in connections) {
            val node = write(config)
            oldById[config.id]?.properties()?.forEach { (key, value) ->
                if (key !in KNOWN_KEYS) node.set(key, value)
            }
            array.add(node)
        }
        unreadable.forEach(array::add)
        if (array.isEmpty) databases.remove(CONNECTIONS) else databases.set(CONNECTIONS, array)
        if (databases.isEmpty) root.remove(DATABASES_FIELD) else root.set(DATABASES_FIELD, databases)
    }
}

internal fun write(config: ConnectionConfig): ObjectNode {
    val d = ConnectionConfig(config.id, config.name, config.kind)
    val node = mapper.createObjectNode()
    node.put("id", config.id).put("name", config.name).put("kind", config.kind.name)
    if (config.host != d.host) node.put("host", config.host)
    if (config.port != d.port) node.put("port", config.port)
    if (config.database != d.database) node.put("database", config.database)
    if (config.user != d.user) node.put("user", config.user)
    if (config.readOnly != d.readOnly) node.put("readOnly", config.readOnly)
    if (config.commitMode != d.commitMode) node.put("commitMode", config.commitMode.name)
    if (config.sslMode != d.sslMode) node.put("sslMode", config.sslMode.name)
    config.caFile?.let { node.put("caFile", it) }
    config.customUrl?.let { node.put("customUrl", it) }
    if (config.driverProperties.isNotEmpty()) {
        val props = node.putObject("driverProperties")
        config.driverProperties.forEach { (key, value) -> props.put(key, value) }
    }
    if (config.color != d.color) node.put("color", config.color.name)
    if (config.showSystemSchemas != d.showSystemSchemas) node.put("showSystemSchemas", config.showSystemSchemas)
    return node
}

/** The connection of [node], or `null` when its id, name or kind is missing or unknown. Bad optional values default. */
internal fun read(node: ObjectNode): ConnectionConfig? {
    val id = text(node.get("id"))?.takeIf { it.isNotBlank() } ?: return null
    val name = text(node.get("name"))?.takeIf { it.isNotBlank() } ?: return null
    val kind = enumOf<DatabaseKind>(node.get("kind")) ?: return null
    val d = ConnectionConfig(id, name, kind)
    return ConnectionConfig(
        id = id,
        name = name,
        kind = kind,
        host = text(node.get("host")) ?: d.host,
        port = node.get("port")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: d.port,
        database = text(node.get("database")) ?: d.database,
        user = text(node.get("user")) ?: d.user,
        readOnly = bool(node.get("readOnly")) ?: d.readOnly,
        commitMode = enumOf<CommitMode>(node.get("commitMode")) ?: d.commitMode,
        sslMode = enumOf<SslMode>(node.get("sslMode")) ?: d.sslMode,
        caFile = text(node.get("caFile")),
        customUrl = text(node.get("customUrl")),
        driverProperties =
            (node.get("driverProperties") as? ObjectNode)
                ?.properties()
                ?.mapNotNull { (key, value) -> text(value)?.let { key to it } }
                ?.toMap() ?: emptyMap(),
        color = enumOf<CardColor>(node.get("color")) ?: d.color,
        showSystemSchemas = bool(node.get("showSystemSchemas")) ?: d.showSystemSchemas,
    )
}

private fun text(node: JsonNode?): String? = node?.takeIf { it.isString }?.stringValue()

private fun bool(node: JsonNode?): Boolean? = node?.takeIf { it.isBoolean }?.booleanValue()

private inline fun <reified E : Enum<E>> enumOf(node: JsonNode?): E? =
    text(node)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
