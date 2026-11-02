package dev.sluide.databases.ui.dialog

import dev.sluide.databases.connect.CardColor
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.JdbcUrls
import dev.sluide.databases.connect.SslMode

/**
 * The fields of the Add/Edit connection dialog as plain values, without JavaFX (ADR 0084). The port stays text while
 * it is typed; [customUrl] is `null` while "Custom URL" is off.
 */
data class ConnectionForm(
    val id: String,
    val name: String,
    val kind: DatabaseKind,
    val host: String,
    val portText: String,
    val database: String,
    val user: String,
    val readOnly: Boolean,
    val commitMode: CommitMode,
    val sslMode: SslMode,
    val caFile: String,
    val customUrl: String?,
    /** Rows of the properties table; keys may repeat while typing, [validation] reports it. */
    val driverProperties: List<Pair<String, String>>,
    val color: CardColor,
    val showSystemSchemas: Boolean,
    /** The names of the connections of the project. */
    val existingNames: Set<String>,
    /** The name the edited connection had; it does not clash with itself. `null` for a new one. */
    val ownName: String? = null,
) {
    /** The first problem in UI words, or `null` when the form can be saved. */
    fun validation(): String? {
        val trimmed = name.trim()
        val clash = existingNames.any { !it.equals(ownName, ignoreCase = true) && it.trim().equals(trimmed, true) }
        val port = portText.trim().toIntOrNull()
        val custom = customUrl
        val prefix = urlPrefix(kind)
        val keys = driverProperties.map { it.first.trim() }
        return when {
            trimmed.isEmpty() -> "Enter a name."
            clash -> "A connection named '$trimmed' exists."
            custom == null && host.isBlank() -> "Enter a host."
            custom == null && (port == null || port !in 1..65535) -> "The port must be a number from 1 to 65535."
            custom != null && !custom.trim().startsWith(prefix) -> "The URL must start with $prefix."
            keys.any { it.isEmpty() } -> "A driver property has no key."
            keys.distinct().size != keys.size -> "A driver property key appears twice."
            else -> null
        }
    }

    /** The config of these values; an unparsable port becomes the kind's default (check [validation] first). */
    fun toConfig(): ConnectionConfig =
        ConnectionConfig(
            id = id,
            name = name.trim(),
            kind = kind,
            host = host.trim(),
            port = portText.trim().toIntOrNull() ?: kind.defaultPort,
            database = database.trim(),
            user = user.trim(),
            readOnly = readOnly,
            commitMode = commitMode,
            sslMode = sslMode,
            caFile = caFile.trim().ifEmpty { null },
            customUrl = customUrl?.trim(),
            driverProperties = driverProperties.associate { it.first.trim() to it.second },
            color = color,
            showSystemSchemas = showSystemSchemas,
        )

    /** The URL host, port and database give, whatever [customUrl] says. */
    fun generatedUrl(): String = JdbcUrls.url(toConfig().copy(customUrl = null))

    /** Switches to [newKind]; the port follows when it was the old kind's default. */
    fun withKind(newKind: DatabaseKind): ConnectionForm {
        if (newKind == kind) return this
        val port = if (portText.trim() == kind.defaultPort.toString()) newKind.defaultPort.toString() else portText
        return copy(kind = newKind, portText = port)
    }

    companion object {
        /** What a custom URL of [kind] starts with. */
        fun urlPrefix(kind: DatabaseKind): String =
            if (kind == DatabaseKind.POSTGRESQL) "jdbc:postgresql:" else "jdbc:mariadb:"

        /** The form of [config]; an edited connection ([isNew] false) may keep its own name. */
        fun of(
            config: ConnectionConfig,
            isNew: Boolean,
            existingNames: Set<String>,
        ) = ConnectionForm(
            id = config.id,
            name = config.name,
            kind = config.kind,
            host = config.host,
            portText = config.port.toString(),
            database = config.database,
            user = config.user,
            readOnly = config.readOnly,
            commitMode = config.commitMode,
            sslMode = config.sslMode,
            caFile = config.caFile.orEmpty(),
            customUrl = config.customUrl,
            driverProperties = config.driverProperties.toList(),
            color = config.color,
            showSystemSchemas = config.showSystemSchemas,
            existingNames = existingNames,
            ownName = if (isNew) null else config.name,
        )
    }
}
