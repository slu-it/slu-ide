package dev.sluide.httpclient.env

import tools.jackson.databind.JsonNode
import java.nio.file.Path

/** `SSLConfiguration` of an environment, read from the private environment file only. */
internal data class SslConfig(
    val clientCertificate: CertificateFile?,
    val clientCertificateKey: CertificateFile?,
    val hasCertificatePassphrase: Boolean,
    val verifyHostCertificate: Boolean,
) {
    /** A certificate or key file; [format] is null when not given (to be told from the file). */
    data class CertificateFile(
        val path: Path,
        val format: CertificateFormat?,
    )

    enum class CertificateFormat { PEM, P12, DER }

    internal companion object {
        /** Reads [node]; relative paths are resolved against [folder], the folder of the private file. */
        fun parse(
            node: JsonNode,
            folder: Path,
            problems: MutableList<String>,
        ): SslConfig? {
            if (!node.isObject) {
                problems += "SSLConfiguration must be an object"
                return null
            }

            fun flag(
                key: String,
                default: Boolean,
            ): Boolean {
                val value = node.get(key) ?: return default
                if (value.isBoolean) return value.booleanValue()
                problems += "SSLConfiguration: \"$key\" must be true or false"
                return default
            }
            return SslConfig(
                clientCertificate =
                    file(
                        node.get(EnvKeys.Ssl.CLIENT_CERTIFICATE),
                        EnvKeys.Ssl.CLIENT_CERTIFICATE,
                        folder,
                        problems,
                    ),
                clientCertificateKey =
                    file(
                        node.get(EnvKeys.Ssl.CLIENT_CERTIFICATE_KEY),
                        EnvKeys.Ssl.CLIENT_CERTIFICATE_KEY,
                        folder,
                        problems,
                    ),
                hasCertificatePassphrase = flag(EnvKeys.Ssl.HAS_CERTIFICATE_PASSPHRASE, false),
                verifyHostCertificate = flag(EnvKeys.Ssl.VERIFY_HOST_CERTIFICATE, true),
            )
        }

        private fun file(
            node: JsonNode?,
            key: String,
            folder: Path,
            problems: MutableList<String>,
        ): CertificateFile? {
            if (node == null || node.isNull) return null
            val path = if (node.isObject) node.get(EnvKeys.Ssl.PATH) else node
            if (path == null || !path.isString || path.stringValue().isBlank()) {
                problems += "SSLConfiguration: \"$key\" needs a path"
                return null
            }
            val formatName = node.get(EnvKeys.Ssl.FORMAT)?.let(::textOf)
            val format =
                formatName?.let { name ->
                    CertificateFormat.entries.firstOrNull { it.name.equals(name, true) }
                }
            if (formatName != null && format == null) {
                problems += "SSLConfiguration: unknown format \"$formatName\" of \"$key\" (PEM, P12 or DER)"
            }
            return CertificateFile(folder.resolve(path.stringValue()).normalize(), format)
        }
    }
}
