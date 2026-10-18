package dev.sluide.httpclient.env

/**
 * The keys and choice values of the environment files, shared by the parsers ([Environments], [AuthConfig],
 * [SslConfig]) and the visual editor, so that both read and write the same names.
 */
internal object EnvKeys {
    const val SECURITY = "Security"
    const val AUTH = "Auth"
    const val SSL_CONFIGURATION = "SSLConfiguration"

    /** Keys of an auth configuration (`Security.Auth.<id>`). */
    object Auth {
        const val TYPE = "Type"
        const val GRANT_TYPE = "Grant Type"
        const val TOKEN_URL = "Token URL"
        const val CLIENT_ID = "Client ID"
        const val CLIENT_SECRET = "Client Secret"
        const val CLIENT_CREDENTIALS = "Client Credentials"
        const val USERNAME = "Username"
        const val PASSWORD = "Password"
        const val SCOPE = "Scope"
        const val CUSTOM_REQUEST_PARAMETERS = "Custom Request Parameters"
        const val USE_ID_TOKEN = "Use ID Token"
        const val ACQUIRE_AUTOMATICALLY = "Acquire Automatically"

        /** Keys of a custom request parameter given as an object. */
        const val PARAMETER_VALUE = "Value"
        const val PARAMETER_USE = "Use"

        /** The only supported value of [TYPE]. */
        const val TYPE_OAUTH2 = "OAuth2"

        /** Values of [GRANT_TYPE] (compared ignoring case). */
        const val GRANT_CLIENT_CREDENTIALS = "Client Credentials"
        const val GRANT_PASSWORD = "Password"

        /** Values of [CLIENT_CREDENTIALS] (compared ignoring case). */
        const val CREDENTIALS_BASIC = "basic"
        const val CREDENTIALS_IN_BODY = "in body"
        const val CREDENTIALS_NONE = "none"

        /** Values of [PARAMETER_USE] (compared ignoring case). */
        const val USE_EVERYWHERE = "everywhere"
        const val USE_IN_AUTH_REQUEST = "in auth request"
        const val USE_IN_TOKEN_REQUEST = "in token request"
    }

    /** Keys of `SSLConfiguration`. */
    object Ssl {
        const val CLIENT_CERTIFICATE = "clientCertificate"
        const val CLIENT_CERTIFICATE_KEY = "clientCertificateKey"
        const val HAS_CERTIFICATE_PASSPHRASE = "hasCertificatePassphrase"
        const val VERIFY_HOST_CERTIFICATE = "verifyHostCertificate"

        /** Keys of a certificate or key given as an object instead of a path. */
        const val PATH = "path"
        const val FORMAT = "format"
    }
}
