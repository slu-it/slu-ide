package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvKeys
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Builds the [EnvTable] of two parsed files. */
internal class EnvTableBuilder(
    private val trees: EnvTrees,
) {
    private val environments = trees.environments
    private val readOnlyEnvironments = trees.readOnlyEnvironments
    private val problems = mutableListOf<String>()

    fun build(): EnvTable {
        fileProblems()
        val variables =
            keys(locations(ValuePath.ROOT)).filterNot { it in EnvSchema.RESERVED }.map {
                row(ValuePath.of(it), it, environments.toSet(), FileSide.PUBLIC)
            }
        return EnvTable(
            environments = environments,
            readOnlyEnvironments = readOnlyEnvironments,
            variables = variables,
            auth = authGroups(),
            certificate = certificate(),
            problems = problems.toList(),
            error = null,
        )
    }

    private fun fileProblems() {
        trees.topLevelKeys.filter { it.startsWith("$") }.forEach {
            problems += "\"$it\" is not shown here and kept as it is"
        }
        for (env in environments) {
            for (side in FileSide.entries) {
                val node = trees.file(side).get(env) ?: continue
                if (node !is ObjectNode) {
                    problems += "${side.fileName}: environment \"$env\" must be an object; it is shown read-only"
                    continue
                }

                fun mustBeObject(
                    path: ValuePath,
                    what: String,
                ) {
                    val value = trees.nodeAt(side, env, path)
                    if (value != null && value !is ObjectNode) {
                        problems += "${side.fileName}: $what of environment \"$env\" must be an object"
                    }
                }
                mustBeObject(ValuePath.of(EnvKeys.SECURITY), EnvKeys.SECURITY)
                mustBeObject(EnvSchema.SECURITY_AUTH, EnvSchema.SECURITY_AUTH.toString())
                (trees.nodeAt(side, env, EnvSchema.SECURITY_AUTH) as? ObjectNode)?.propertyNames()?.forEach {
                    mustBeObject(EnvSchema.authPath(it), "Auth \"$it\"")
                }
                val security = ValuePath.of(EnvKeys.SECURITY)
                (trees.nodeAt(side, env, security) as? ObjectNode)?.propertyNames()?.forEach {
                    if (it != EnvKeys.AUTH) hidden(side, env, security.key(it))
                }
            }
        }
    }

    private fun locations(path: ValuePath): Map<String, Location> =
        environments.associateWith { if (it in readOnlyEnvironments) Location.NONE else trees.locate(it, path) }

    /** The keys of the objects at [locations], in the order of the public file's environments, then the private's. */
    private fun keys(locations: Map<String, Location>): List<String> =
        FileSide.entries
            .flatMap { side ->
                environments.flatMap { env -> (locations[env]?.node(side) as? ObjectNode)?.propertyNames().orEmpty() }
            }.distinct()

    /**
     * The row at [path] with its children; its type is the one of the values defined, [fallbackType] if none is,
     * [ValueType.MIXED] if they disagree.
     */
    private fun row(
        path: ValuePath,
        label: String,
        applicable: Set<String>,
        defaultSide: FileSide,
        fallbackType: ValueType = ValueType.TEXT,
    ): Row {
        val locations = locations(path)
        val values = locations.mapValues { it.value.value }
        val types =
            values.values
                .filterNotNull()
                .map(EnvValues::typeOf)
                .distinct()
        val type = types.singleOrNull() ?: if (types.isEmpty()) fallbackType else ValueType.MIXED
        val cells =
            environments.associateWith { env ->
                val location = locations.getValue(env)
                values[env]?.let { Cell(it, location.owner, location.overridesPublic) } ?: Cell.UNDEFINED
            }
        val children =
            when (type) {
                ValueType.OBJECT -> {
                    keys(locations).map { row(path.key(it), it, applicable, defaultSide) }
                }

                ValueType.LIST -> {
                    val size = values.values.maxOf { (it as? ArrayNode)?.size() ?: 0 }
                    (0 until size).map { row(path.index(it), "[$it]", applicable, defaultSide) }
                }

                else -> {
                    emptyList()
                }
            }
        return Row(
            path = path,
            label = label,
            type = type,
            cells = cells,
            children = children,
            defaultSide = defaultSide,
            sideFixed = false,
            readOnly = type == ValueType.MIXED,
            choices = null,
            applicable = applicable,
        )
    }

    private fun authGroups(): List<AuthGroup> =
        keys(locations(EnvSchema.SECURITY_AUTH)).mapNotNull { id ->
            val base = EnvSchema.authPath(id)
            val configs = locations(base).mapValues { it.value.value as? ObjectNode }
            val applicable = environments.filter { configs[it] != null }.toSet()
            if (applicable.isEmpty()) return@mapNotNull null
            val passwordGrant =
                applicable
                    .filter { env ->
                        val grant = configs[env]?.get(EnvKeys.Auth.GRANT_TYPE)?.takeIf { it.isString }?.stringValue()
                        grant?.trim().equals(EnvKeys.Auth.GRANT_PASSWORD, ignoreCase = true)
                    }.toSet()
            val known =
                EnvSchema.AUTH_FIELDS.map { field ->
                    val fieldApplicable =
                        if (field.key in
                            EnvSchema.PASSWORD_GRANT_FIELDS
                        ) {
                            passwordGrant
                        } else {
                            applicable
                        }
                    val row = row(base.key(field.key), field.key, fieldApplicable, field.defaultSide, field.type)
                    if (row.type == field.type) row.copy(choices = field.choices) else row
                }
            val knownKeys = EnvSchema.AUTH_FIELDS.map { it.key }.toSet()
            val unknown =
                keys(locations(base)).filterNot { it in knownKeys }.map {
                    row(base.key(it), it, applicable, FileSide.PUBLIC)
                }
            AuthGroup(id, known + unknown)
        }

    private fun certificate(): List<Row>? {
        val ssl = EnvSchema.SSL
        val editable = environments.filterNot { it in readOnlyEnvironments }
        val publicSsl = editable.filter { trees.nodeAt(FileSide.PUBLIC, it, ssl) != null }
        val privateSsl = editable.associateWith { trees.nodeAt(FileSide.PRIVATE, it, ssl) }.filterValues { it != null }
        if (publicSsl.isEmpty() && privateSsl.isEmpty()) return null
        publicSsl.forEach {
            problems +=
                "${FileSide.PUBLIC.fileName}: ${EnvKeys.SSL_CONFIGURATION} of environment \"$it\" is only read from " +
                FileSide.PRIVATE.fileName
        }
        privateSsl.filterValues { it !is ObjectNode }.keys.forEach {
            problems +=
                "${FileSide.PRIVATE.fileName}: ${EnvKeys.SSL_CONFIGURATION} of environment \"$it\" must be an object"
        }
        val configs = privateSsl.mapNotNull { (env, node) -> (node as? ObjectNode)?.let { env to it } }.toMap()
        configs.forEach { (env, config) -> hiddenCertificateKeys(env, config) }
        val section = CertificateSection(configs.keys)

        fun entries(part: CertificatePart) =
            configs.mapValues { (_, config) ->
                config.get((EnvSchema.certificatePath(part).last as Segment.Key).name)
            }

        fun certificateRows(
            part: CertificatePart,
            label: String,
        ): List<Row> {
            val entries = entries(part)
            val paths =
                entries.mapValues { (_, entry) ->
                    if (entry is ObjectNode) entry.get(EnvKeys.Ssl.PATH) else entry
                }
            val formats = entries.mapValues { (_, entry) -> (entry as? ObjectNode)?.get(EnvKeys.Ssl.FORMAT) }
            return listOf(
                section.row(EnvSchema.certificatePath(part), label, ValueType.TEXT, paths, null),
                section.row(
                    EnvSchema.formatPath(part),
                    "$label format",
                    ValueType.TEXT,
                    formats,
                    listOf("") + EnvSchema.CERTIFICATE_FORMATS,
                ),
            )
        }

        fun flagRow(
            key: String,
            label: String,
        ) = section.row(ssl.key(key), label, ValueType.YES_NO, configs.mapValues { it.value.get(key) }, null)

        return certificateRows(CertificatePart.CERTIFICATE, "Certificate") +
            certificateRows(CertificatePart.KEY, "Key") +
            flagRow(EnvKeys.Ssl.HAS_CERTIFICATE_PASSPHRASE, "Has passphrase") +
            flagRow(EnvKeys.Ssl.VERIFY_HOST_CERTIFICATE, "Verify host certificate")
    }

    /** Reports the keys of an `SSLConfiguration` and of its certificate entries that the form does not show. */
    private fun hiddenCertificateKeys(
        env: String,
        config: ObjectNode,
    ) {
        for (key in config.propertyNames()) {
            val path = EnvSchema.SSL.key(key)
            if (key !in EnvSchema.SSL_KEYS) hidden(FileSide.PRIVATE, env, path)
            if (EnvSchema.certificatePart(path) == null) continue
            (config.get(key) as? ObjectNode)?.propertyNames()?.forEach {
                if (it != EnvKeys.Ssl.PATH && it != EnvKeys.Ssl.FORMAT) hidden(FileSide.PRIVATE, env, path.key(it))
            }
        }
    }

    /**
     * Rows of the private-only certificate settings of the environments in [applicable]. A public `SSLConfiguration`
     * does not make them read-only: they are written to the private file anyway.
     */
    private inner class CertificateSection(
        private val applicable: Set<String>,
    ) {
        /** A row of [values] by environment; read-only if a value is not of [type]. */
        fun row(
            path: ValuePath,
            label: String,
            type: ValueType,
            values: Map<String, JsonNode?>,
            choices: List<String>?,
        ): Row {
            val defined = values.filterValues { it != null }
            val types =
                defined.values
                    .filterNotNull()
                    .map(EnvValues::typeOf)
                    .distinct()
            val rowType = types.singleOrNull() ?: if (types.isEmpty()) type else ValueType.MIXED
            return Row(
                path = path,
                label = label,
                type = rowType,
                cells =
                    environments.associateWith { env ->
                        values[env]?.let { Cell(it, FileSide.PRIVATE, false) } ?: Cell.UNDEFINED
                    },
                children = emptyList(),
                defaultSide = FileSide.PRIVATE,
                sideFixed = true,
                readOnly = rowType != type,
                choices = choices.takeIf { rowType == type },
                applicable = applicable,
            )
        }
    }

    private fun hidden(
        side: FileSide,
        env: String,
        path: ValuePath,
    ) {
        problems += "${side.fileName}: \"$path\" of environment \"$env\" is not shown here and kept as it is"
    }
}
