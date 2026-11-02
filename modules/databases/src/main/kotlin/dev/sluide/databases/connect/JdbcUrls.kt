package dev.sluide.databases.connect

import java.net.URLEncoder
import java.util.Properties

/** Builds the JDBC URL, the driver properties and the card subtitle of a [ConnectionConfig] (ADR 0121). */
object JdbcUrls {
    /**
     * The user's custom URL if not blank, else `jdbc:postgresql://host:port/database` (the name percent-encoded, spaces
     * as `%20`, because pgjdbc decodes the path) or `jdbc:mariadb://host:port`: Connector/J does not decode the path,
     * so the database goes in as the `database` property of [properties].
     */
    fun url(config: ConnectionConfig): String {
        config.customUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val scheme = if (config.kind == DatabaseKind.POSTGRESQL) "jdbc:postgresql" else "jdbc:mariadb"
        val database =
            if (config.database.isBlank() || config.kind == DatabaseKind.MARIADB) "" else "/${encoded(config.database)}"
        return "$scheme://${hostPort(config)}$database"
    }

    /**
     * The properties for `DriverManager`: first the user's driver properties, then user, [password] (when not null),
     * TLS, read-only and the application name, which win; except that a TLS key the user set in the driver
     * properties (`sslmode`/`sslrootcert`, `sslMode`/`serverSslCert`; MariaDB's compared ignoring case) keeps its
     * value.
     *
     * Read-only is PostgreSQL only: `readOnly=true` with `readOnlyMode=always`, so it also holds in auto-commit.
     * MariaDB's connector has no such property; there `Connection.setReadOnly(true)` does it.
     *
     * MariaDB's connector has no "prefer": PREFER maps to `disable` there, REQUIRE to `trust` (encrypted, certificate
     * not checked).
     */
    fun properties(
        config: ConnectionConfig,
        password: CharArray?,
    ): Properties {
        val result = Properties()
        config.driverProperties.forEach { (key, value) -> result.setProperty(key, value) }
        val generated = LinkedHashMap<String, String>()
        if (config.user.isNotBlank()) generated["user"] = config.user
        if (password != null) generated["password"] = String(password)
        val tls = LinkedHashMap<String, String>()
        if (config.kind == DatabaseKind.POSTGRESQL) {
            tls["sslmode"] =
                config.sslMode.name
                    .lowercase()
                    .replace('_', '-')
            config.caFile?.takeIf { it.isNotBlank() }?.let { tls["sslrootcert"] = it }
            if (config.readOnly) {
                generated["readOnly"] = "true"
                generated["readOnlyMode"] = "always"
            }
            generated["ApplicationName"] = "Thoreau"
        } else {
            tls["sslMode"] =
                when (config.sslMode) {
                    SslMode.DISABLE, SslMode.PREFER -> "disable"
                    SslMode.REQUIRE -> "trust"
                    SslMode.VERIFY_CA -> "verify-ca"
                    SslMode.VERIFY_FULL -> "verify-full"
                }
            config.caFile?.takeIf { it.isNotBlank() }?.let { tls["serverSslCert"] = it }
            if (config.database.isNotBlank() &&
                config.customUrl.isNullOrBlank()
            ) {
                generated["database"] = config.database
            }
            generated["connectionAttributes"] = "program_name:Thoreau"
        }
        generated.forEach { (key, value) -> result.setProperty(key, value) }
        val ignoreCase = config.kind == DatabaseKind.MARIADB
        tls.forEach { (key, value) ->
            if (config.driverProperties.keys.none { it.equals(key, ignoreCase) }) result.setProperty(key, value)
        }
        return result
    }

    /** `user@host:port/database` (without `user@` or `/database` when blank); a custom URL without query and userinfo. */
    fun display(config: ConnectionConfig): String {
        val custom = config.customUrl?.takeIf { it.isNotBlank() }
        if (custom != null) {
            val schemeEnd = custom.indexOf("//")
            if (schemeEnd < 0) return custom.substringBefore('?').substringBefore(';')
            val rest = custom.substring(schemeEnd + 2)
            val withoutUserinfo = rest.substring(rest.lastIndexOf('@') + 1)
            return custom.substring(0, schemeEnd + 2) + withoutUserinfo.substringBefore('?').substringBefore(';')
        }
        val user = if (config.user.isBlank()) "" else "${config.user}@"
        val database = if (config.database.isBlank()) "" else "/${config.database}"
        return "$user${hostPort(config)}$database"
    }

    private fun encoded(name: String) = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")

    private fun hostPort(config: ConnectionConfig): String {
        val host = config.host
        val shown = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "$shown:${config.port}"
    }
}
