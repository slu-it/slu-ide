package dev.sluide.httpclient.auth

import dev.sluide.httpclient.env.AuthConfig
import dev.sluide.httpclient.env.AuthConfig.ClientCredentials
import dev.sluide.httpclient.env.AuthConfig.ParameterUse
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.vars.DynamicVariables
import dev.sluide.httpclient.vars.UnresolvedVariablesException
import dev.sluide.httpclient.vars.VariableResolver
import java.net.URI
import java.net.URISyntaxException

/** An auth configuration cannot be used or its token cannot be obtained; [message] is meant for the user. */
internal class OAuth2Exception(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * An OAuth2 configuration with all its templates resolved, ready for token requests. Equal configurations may share a
 * token, so a changed value (e.g. a new secret in the private file) leads to a new token.
 */
internal data class OAuth2Config(
    val id: String,
    val grant: Grant,
    val tokenUrl: URI,
    val clientId: String,
    val clientSecret: String?,
    val clientCredentials: ClientCredentials,
    val username: String?,
    val password: String?,
    val scope: String?,
    /** Custom parameters for the token request (`In Token Request` and `Everywhere`), in the order written. */
    val parameters: List<Pair<String, String>>,
    val useIdToken: Boolean,
    val acquireAutomatically: Boolean,
) {
    enum class Grant(
        val label: String,
        val value: String,
    ) {
        CLIENT_CREDENTIALS("Client Credentials", "client_credentials"),
        PASSWORD("Password", "password"),
    }

    /** Without the secrets, so that the configuration can show up in logs and messages. */
    override fun toString() = "OAuth2Config($id, ${grant.label}, $tokenUrl)"

    companion object {
        private val UNSUPPORTED_GRANTS = setOf("authorization code", "implicit", "device authorization")
        private val SUPPORTED = Grant.entries.joinToString(" and ") { it.label }

        /**
         * The configuration [id] of [environment], its values resolved with the variables of the environment (no
         * in-file variables, no `$auth` calls). Throws [OAuth2Exception] if it is missing, unsupported or incomplete.
         */
        fun of(
            environment: Environment?,
            id: String,
            dynamic: DynamicVariables = DynamicVariables(),
        ): OAuth2Config {
            if (environment == null) {
                throw OAuth2Exception("Auth '$id' needs an environment; choose one that has Security.Auth.$id")
            }
            val config =
                environment.auth[id]
                    ?: throw OAuth2Exception(
                        "There is no auth '$id' in Security.Auth of environment '${environment.name}'",
                    )
            return resolve(config, VariableResolver(environment, emptyList(), dynamic))
        }

        fun resolve(
            config: AuthConfig,
            resolver: VariableResolver,
        ): OAuth2Config {
            val id = config.id
            val type = config.type?.trim()
            if (type == null) throw OAuth2Exception("Auth '$id' has no Type; only OAuth2 is supported")
            if (!type.equals("OAuth2", ignoreCase = true)) {
                throw OAuth2Exception("Auth '$id' is of Type '$type'; only OAuth2 is supported")
            }
            val grant = grant(id, config.grantType)
            val unresolved = linkedSetOf<String>()

            fun resolve(text: String?): String? =
                text?.let {
                    try {
                        resolver.resolve(it)
                    } catch (e: UnresolvedVariablesException) {
                        unresolved += e.names
                        it
                    }
                }

            val tokenUrl = resolve(config.tokenUrl)?.trim()?.takeIf { it.isNotEmpty() }
            val clientId = resolve(config.clientId)?.takeIf { it.isNotEmpty() }
            val clientSecret = resolve(config.clientSecret)
            val username = resolve(config.username)
            val password = resolve(config.password)
            val scope = resolve(config.scope)?.takeIf { it.isNotBlank() }
            val parameters =
                config.customRequestParameters
                    .filter { it.use != ParameterUse.IN_AUTH_REQUEST }
                    .map { it.name to resolve(it.value).orEmpty() }
            if (unresolved.isNotEmpty()) {
                throw OAuth2Exception("Auth '$id' has unresolved variables: ${unresolved.joinToString(", ")}")
            }
            if (tokenUrl == null) throw OAuth2Exception("Auth '$id' needs a Token URL")
            if (clientId == null) throw OAuth2Exception("Auth '$id' needs a Client ID")
            if (grant == Grant.PASSWORD && (username == null || password == null)) {
                throw OAuth2Exception("Auth '$id' needs a Username and a Password for the Password grant")
            }
            return OAuth2Config(
                id = id,
                grant = grant,
                tokenUrl = tokenUri(id, tokenUrl),
                clientId = clientId,
                clientSecret = clientSecret,
                clientCredentials = config.clientCredentials,
                username = username,
                password = password,
                scope = scope,
                parameters = parameters,
                useIdToken = config.useIdToken,
                acquireAutomatically = config.acquireAutomatically,
            )
        }

        private fun grant(
            id: String,
            name: String?,
        ): Grant {
            if (name == null) throw OAuth2Exception("Auth '$id' has no Grant Type; supported are $SUPPORTED")
            val normalized = name.trim().lowercase().replace('_', ' ')
            Grant.entries.firstOrNull { it.label.lowercase() == normalized }?.let { return it }
            val what = if (normalized in UNSUPPORTED_GRANTS) "is not supported" else "is unknown"
            throw OAuth2Exception("Grant Type '$name' of auth '$id' $what; supported are $SUPPORTED")
        }

        private fun tokenUri(
            id: String,
            text: String,
        ): URI {
            val uri =
                try {
                    URI(text)
                } catch (e: URISyntaxException) {
                    throw OAuth2Exception("The Token URL of auth '$id' is invalid: ${e.message}")
                }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) {
                throw OAuth2Exception("The Token URL of auth '$id' must be an http or https URL: $text")
            }
            return uri
        }
    }
}
