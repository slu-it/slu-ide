package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvKeys
import dev.sluide.httpclient.env.EnvKeys.Auth
import dev.sluide.httpclient.env.EnvKeys.Ssl

/** A known field of an auth configuration or of `SSLConfiguration`. */
internal data class FieldSpec(
    val key: String,
    val type: ValueType,
    val defaultSide: FileSide = FileSide.PUBLIC,
    val choices: List<String>? = null,
)

/** The known structure of the environment files: auth fields, certificate settings and where they go by default. */
internal object EnvSchema {
    val SECURITY_AUTH = ValuePath.of(EnvKeys.SECURITY, EnvKeys.AUTH)
    val SSL = ValuePath.of(EnvKeys.SSL_CONFIGURATION)

    /** Objects that only group others and are removed from a file when they become empty there. */
    val STRUCTURE = setOf(ValuePath.of(EnvKeys.SECURITY), SECURITY_AUTH, SSL)

    /** Top-level keys of an environment that are not variables. */
    val RESERVED = setOf(EnvKeys.SECURITY, EnvKeys.SSL_CONFIGURATION)

    val GRANT_TYPES = listOf(Auth.GRANT_CLIENT_CREDENTIALS, Auth.GRANT_PASSWORD)
    val CLIENT_CREDENTIALS = listOf(Auth.CREDENTIALS_BASIC, Auth.CREDENTIALS_IN_BODY, Auth.CREDENTIALS_NONE)

    /** The keys of `SSLConfiguration` the form shows. */
    val SSL_KEYS =
        setOf(
            Ssl.CLIENT_CERTIFICATE,
            Ssl.CLIENT_CERTIFICATE_KEY,
            Ssl.HAS_CERTIFICATE_PASSPHRASE,
            Ssl.VERIFY_HOST_CERTIFICATE,
        )

    /** Certificate formats; the empty text means "not given" (told from the file). */
    val CERTIFICATE_FORMATS = listOf("PEM", "P12", "DER")

    /** The fields of an auth configuration, in the order shown. */
    val AUTH_FIELDS =
        listOf(
            FieldSpec(Auth.TYPE, ValueType.TEXT),
            FieldSpec(Auth.GRANT_TYPE, ValueType.TEXT, choices = GRANT_TYPES),
            FieldSpec(Auth.TOKEN_URL, ValueType.TEXT),
            FieldSpec(Auth.CLIENT_ID, ValueType.TEXT),
            FieldSpec(Auth.CLIENT_SECRET, ValueType.TEXT, FileSide.PRIVATE),
            FieldSpec(Auth.CLIENT_CREDENTIALS, ValueType.TEXT, choices = CLIENT_CREDENTIALS),
            FieldSpec(Auth.USERNAME, ValueType.TEXT),
            FieldSpec(Auth.PASSWORD, ValueType.TEXT, FileSide.PRIVATE),
            FieldSpec(Auth.SCOPE, ValueType.TEXT),
            FieldSpec(Auth.USE_ID_TOKEN, ValueType.YES_NO),
            FieldSpec(Auth.ACQUIRE_AUTOMATICALLY, ValueType.YES_NO),
            FieldSpec(Auth.CUSTOM_REQUEST_PARAMETERS, ValueType.OBJECT),
        )

    /** Fields that apply only where the configuration's grant type is Password. */
    val PASSWORD_GRANT_FIELDS = setOf(Auth.USERNAME, Auth.PASSWORD)

    fun authPath(id: String) = SECURITY_AUTH.key(id)

    /** The path of the certificate or key entry (a path text, or an object with `path` and `format`). */
    fun certificatePath(part: CertificatePart): ValuePath =
        SSL.key(if (part == CertificatePart.CERTIFICATE) Ssl.CLIENT_CERTIFICATE else Ssl.CLIENT_CERTIFICATE_KEY)

    /** The path of the format row of [part]; the format lives there only in the object form. */
    fun formatPath(part: CertificatePart): ValuePath = certificatePath(part).key(Ssl.FORMAT)

    /** The part whose entry is at [path], if any. */
    fun certificatePart(path: ValuePath): CertificatePart? =
        CertificatePart.entries.firstOrNull { certificatePath(it) == path }

    /** The part whose format row is at [path], if any. */
    fun formatPart(path: ValuePath): CertificatePart? = CertificatePart.entries.firstOrNull { formatPath(it) == path }

    fun isCertificate(path: ValuePath) = path.segments.firstOrNull() == SSL.segments.first()

    /** Whether [path] is the object of an auth configuration, `Security.Auth.<id>`. */
    fun isAuthConfig(path: ValuePath) = path.size == 3 && path.take(2) == SECURITY_AUTH

    /**
     * The file the schema puts a new value at [path] in: certificate settings and Client Secret and Password private,
     * the other auth fields public; null for anything else.
     */
    fun schemaSide(path: ValuePath): FileSide? {
        if (isCertificate(path)) return FileSide.PRIVATE
        if (!isAuthConfig(path.parent)) return null
        val key = (path.last as? Segment.Key)?.name
        return AUTH_FIELDS.firstOrNull { it.key == key }?.defaultSide
    }

    /** The file a new value at [path] goes to by default: [schemaSide], else public. */
    fun defaultSide(path: ValuePath): FileSide = schemaSide(path) ?: FileSide.PUBLIC
}
