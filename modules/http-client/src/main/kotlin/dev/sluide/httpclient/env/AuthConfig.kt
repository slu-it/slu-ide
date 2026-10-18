package dev.sluide.httpclient.env

import tools.jackson.databind.JsonNode

/**
 * An auth configuration from `Security.Auth.<id>` of an environment. String values are templates that may contain
 * `{{variables}}`; they are resolved when a token is acquired.
 */
internal data class AuthConfig(
    val id: String,
    val type: String?,
    val grantType: String?,
    val tokenUrl: String?,
    val clientId: String?,
    val clientSecret: String?,
    val clientCredentials: ClientCredentials,
    val username: String?,
    val password: String?,
    val scope: String?,
    val customRequestParameters: List<CustomParameter>,
    val useIdToken: Boolean,
    val acquireAutomatically: Boolean,
) {
    /** How the client id and secret are sent to the token endpoint. */
    enum class ClientCredentials { BASIC, IN_BODY, NONE }

    /** One value of a `Custom Request Parameters` entry; an array gives one parameter per value. */
    data class CustomParameter(
        val name: String,
        val value: String,
        val use: ParameterUse,
    )

    enum class ParameterUse { IN_AUTH_REQUEST, IN_TOKEN_REQUEST, EVERYWHERE }

    internal companion object {
        /** Reads the configuration [id] from [node], adding what cannot be understood to [problems]. */
        fun parse(
            id: String,
            node: JsonNode,
            problems: MutableList<String>,
        ): AuthConfig {
            fun text(key: String) = node.get(key)?.let(::textOf)

            fun flag(
                key: String,
                default: Boolean,
            ): Boolean {
                val value = node.get(key) ?: return default
                return when {
                    value.isBoolean -> value.booleanValue()

                    value.isString && value.stringValue().lowercase() in
                        setOf(
                            "true",
                            "false",
                        )
                    -> value.stringValue().toBoolean()

                    else -> default.also { problems += "Auth \"$id\": \"$key\" must be true or false" }
                }
            }
            return AuthConfig(
                id = id,
                type = text(EnvKeys.Auth.TYPE),
                grantType = text(EnvKeys.Auth.GRANT_TYPE),
                tokenUrl = text(EnvKeys.Auth.TOKEN_URL),
                clientId = text(EnvKeys.Auth.CLIENT_ID),
                clientSecret = text(EnvKeys.Auth.CLIENT_SECRET),
                clientCredentials = clientCredentials(id, text(EnvKeys.Auth.CLIENT_CREDENTIALS), problems),
                username = text(EnvKeys.Auth.USERNAME),
                password = text(EnvKeys.Auth.PASSWORD),
                scope = text(EnvKeys.Auth.SCOPE),
                customRequestParameters =
                    customParameters(
                        id,
                        node.get(EnvKeys.Auth.CUSTOM_REQUEST_PARAMETERS),
                        problems,
                    ),
                useIdToken = flag(EnvKeys.Auth.USE_ID_TOKEN, false),
                acquireAutomatically = flag(EnvKeys.Auth.ACQUIRE_AUTOMATICALLY, true),
            )
        }

        private fun clientCredentials(
            id: String,
            value: String?,
            problems: MutableList<String>,
        ): ClientCredentials =
            when (value?.trim()?.lowercase()) {
                null, EnvKeys.Auth.CREDENTIALS_BASIC -> {
                    ClientCredentials.BASIC
                }

                EnvKeys.Auth.CREDENTIALS_IN_BODY -> {
                    ClientCredentials.IN_BODY
                }

                EnvKeys.Auth.CREDENTIALS_NONE -> {
                    ClientCredentials.NONE
                }

                else -> {
                    problems +=
                        "Auth \"$id\": unknown Client Credentials \"$value\" (basic, in body or none); basic is used"
                    ClientCredentials.BASIC
                }
            }

        private fun customParameters(
            id: String,
            node: JsonNode?,
            problems: MutableList<String>,
        ): List<CustomParameter> {
            if (node == null) return emptyList()
            if (!node.isObject) {
                problems += "Auth \"$id\": Custom Request Parameters must be an object"
                return emptyList()
            }
            return node.properties().flatMap { (name, value) ->
                val values = if (value.isArray) value.values() else listOf(value)
                values.mapNotNull { customParameter(id, name, it, problems) }
            }
        }

        private fun customParameter(
            id: String,
            name: String,
            node: JsonNode,
            problems: MutableList<String>,
        ): CustomParameter? {
            if (!node.isObject) return CustomParameter(name, textOf(node), ParameterUse.EVERYWHERE)
            val value = node.get(EnvKeys.Auth.PARAMETER_VALUE)
            if (value == null) {
                problems += "Auth \"$id\": custom parameter \"$name\" has no Value"
                return null
            }
            val use =
                when (
                    node
                        .get(EnvKeys.Auth.PARAMETER_USE)
                        ?.let(::textOf)
                        ?.trim()
                        ?.lowercase()
                ) {
                    null, EnvKeys.Auth.USE_EVERYWHERE -> {
                        ParameterUse.EVERYWHERE
                    }

                    EnvKeys.Auth.USE_IN_AUTH_REQUEST -> {
                        ParameterUse.IN_AUTH_REQUEST
                    }

                    EnvKeys.Auth.USE_IN_TOKEN_REQUEST -> {
                        ParameterUse.IN_TOKEN_REQUEST
                    }

                    else -> {
                        problems += "Auth \"$id\": custom parameter \"$name\" has an unknown Use; Everywhere is used"
                        ParameterUse.EVERYWHERE
                    }
                }
            return CustomParameter(name, textOf(value), use)
        }
    }
}

/** A JSON value as text: strings as they are, everything else as JSON. */
internal fun textOf(node: JsonNode): String = if (node.isString) node.stringValue() else node.toString()
