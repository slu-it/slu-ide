package dev.sluide.httpclient.send

import dev.sluide.httpclient.env.SslConfig
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.env.SslConfig.CertificateFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.nio.channels.ClosedByInterruptException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes

/**
 * A client certificate or key cannot be used; [message] is meant for the user. [passphraseRejected] if the passphrase
 * was wrong, so that it is asked for again.
 */
internal class SslConfigException(
    message: String,
    cause: Throwable? = null,
    val passphraseRejected: Boolean = false,
) : RuntimeException(message, cause)

/**
 * The [SSLContext]s of one window, one per `SSLConfiguration` (and modification time of its files), so that
 * `HttpClients.ClientKey`s of equal configurations are equal. Contexts that are replaced (because a file changed) or
 * cleared go to [discarded], e.g. to stop their HTTP clients.
 *
 * [passphrase] is asked at most once per pair of certificate and key files, when a context is first created, and
 * remembered for the session; it is asked again only if it turned out wrong. [context] blocks while that prompt is
 * open, so never call it on the FX thread; a prompt shown on the FX thread must not wait for this class. Only calls
 * for the same files wait for each other, interruptibly; [clear] never waits for a prompt.
 *
 * [trustStore] holds the certificates to trust when verifying hosts; null means the JDK's (`cacerts`).
 */
internal class SslContexts(
    private val passphrase: (SslConfig) -> CharArray? = { null },
    private val trustStore: KeyStore? = null,
    private val discarded: (SSLContext) -> Unit = {},
) {
    private data class Key(
        val config: SslConfig,
        val modified: List<FileTime?>,
    )

    private val contexts = ConcurrentHashMap<Key, SSLContext>()
    private val passphrases = ConcurrentHashMap<List<Path>, CharArray>()

    /** One lock per pair of files, held while creating their context (and prompting for their passphrase). */
    private val fileLocks = ConcurrentHashMap<List<Path>, ReentrantLock>()

    /** Guards changes of [contexts], [passphrases] and [generation]; held only briefly, never around a prompt. */
    private val storeLock = Any()

    /** Counts [clear]s, so that a context created across one is not stored. */
    private var generation = 0L

    fun context(config: SslConfig): SSLContext {
        val files = listOfNotNull(config.clientCertificate, config.clientCertificateKey).map { it.path }
        val key = Key(config, files.map(::modified))
        contexts[key]?.let { return it }
        val fileLock = fileLocks.computeIfAbsent(files) { ReentrantLock() }
        fileLock.lockInterruptibly()
        try {
            contexts[key]?.let { return it }
            val generation = synchronized(storeLock) { this.generation }
            var asked: CharArray? = null
            val ask = { passphrases[files]?.copyOf() ?: passphrase(config)?.also { asked = it.copyOf() } }
            var rejected = false
            val context =
                try {
                    create(config, ask, trustStore)
                } catch (e: SslConfigException) {
                    rejected = e.passphraseRejected
                    throw e
                } finally {
                    synchronized(storeLock) {
                        val current = this.generation == generation
                        if (rejected) passphrases.remove(files)?.fill('\u0000')
                        val keep = asked?.takeIf { !rejected && current }
                        if (keep != null) passphrases[files] = keep else asked?.fill('\u0000')
                    }
                }
            val replaced =
                synchronized(storeLock) {
                    if (this.generation != generation) return context
                    val old = contexts.keys.filter { it.config == config }.mapNotNull { contexts.remove(it) }
                    contexts[key] = context
                    old
                }
            replaced.forEach(discarded)
            return context
        } finally {
            fileLock.unlock()
        }
    }

    /** Forgets all contexts and passphrases; does not wait for running calls of [context]. */
    fun clear() {
        val cleared =
            synchronized(storeLock) {
                generation++
                passphrases.values.forEach { it.fill('\u0000') }
                passphrases.clear()
                contexts.values.toList().also { contexts.clear() }
            }
        cleared.forEach(discarded)
    }

    private fun modified(path: Path): FileTime? =
        try {
            path.getLastModifiedTime()
        } catch (_: IOException) {
            null
        }

    companion object {
        private val PEM = Regex("""-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \1-----""", RegexOption.DOT_MATCHES_ALL)
        private const val PKCS8_HINT = "convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem"
        private const val ENCRYPTION_HINT =
            "convert it with: openssl pkcs8 -topk8 -v2 aes-256-cbc -in key.pem -out key-aes.pem"

        /**
         * A context with the client certificate of [config] (if any) that trusts [trustStore] (null: the JDK's
         * `cacerts`), or every server if `verifyHostCertificate` is false; that also skips host name verification.
         * [passphrase] is called only when one is needed. Throws [SslConfigException].
         */
        fun create(
            config: SslConfig,
            passphrase: () -> CharArray?,
            trustStore: KeyStore? = null,
        ): SSLContext {
            val keyManagers =
                when {
                    config.clientCertificate != null -> keyManagers(config, config.clientCertificate, passphrase)

                    config.clientCertificateKey != null -> throw SslConfigException(
                        "SSLConfiguration has a clientCertificateKey but no clientCertificate",
                    )

                    else -> null
                }
            val trustManagers =
                if (config.verifyHostCertificate) {
                    TrustManagerFactory
                        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                        .apply { init(trustStore) }
                        .trustManagers
                } else {
                    arrayOf<TrustManager>(TrustAll)
                }
            return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
        }

        private fun keyManagers(
            config: SslConfig,
            certificate: CertificateFile,
            passphrase: () -> CharArray?,
        ): Array<KeyManager> {
            val bytes = read(certificate, "client certificate")
            val (store, password) =
                when (format(certificate, bytes)) {
                    CertificateFormat.P12 -> p12(certificate.path, bytes, config.hasCertificatePassphrase, passphrase)
                    else -> pemOrDer(config, certificate, bytes, passphrase)
                }
            try {
                return KeyManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(store, password) }
                    .keyManagers
            } catch (e: GeneralSecurityException) {
                throw SslConfigException("The key of ${certificate.path.fileName} cannot be used: ${e.message}", e)
            }
        }

        private fun p12(
            path: Path,
            bytes: ByteArray,
            hasPassphrase: Boolean,
            passphrase: () -> CharArray?,
        ): Pair<KeyStore, CharArray> {
            val password =
                if (hasPassphrase) {
                    passphrase() ?: throw SslConfigException("No passphrase for ${path.fileName} was given")
                } else {
                    CharArray(0)
                }
            val store = KeyStore.getInstance("PKCS12")
            try {
                store.load(bytes.inputStream(), password)
            } catch (e: IOException) {
                val hint =
                    if (hasPassphrase) "is the passphrase right?" else "set hasCertificatePassphrase if it has one"
                throw SslConfigException(
                    "${path.fileName} cannot be read as PKCS#12 ($hint): ${e.message}",
                    e,
                    passphraseRejected = hasPassphrase,
                )
            } catch (e: GeneralSecurityException) {
                throw SslConfigException("${path.fileName} cannot be read as PKCS#12: ${e.message}", e)
            }
            if (store.aliases().toList().none(store::isKeyEntry)) {
                throw SslConfigException("${path.fileName} contains no private key")
            }
            return store to password
        }

        private fun pemOrDer(
            config: SslConfig,
            certificate: CertificateFile,
            bytes: ByteArray,
            passphrase: () -> CharArray?,
        ): Pair<KeyStore, CharArray> {
            val chain = certificates(certificate, bytes)
            val keyFile =
                config.clientCertificateKey
                    ?: throw SslConfigException(
                        "The client certificate ${certificate.path.fileName} needs a clientCertificateKey " +
                            "(or use a P12 file with certificate and key)",
                    )
            val key = privateKey(keyFile, passphrase)
            // The certificate of the key comes first, whatever the order in the file; the others form the chain.
            val own =
                chain.firstOrNull { belongs(it, key) }
                    ?: throw SslConfigException(
                        "The key ${keyFile.path.fileName} does not belong to the certificate ${certificate.path.fileName}",
                    )
            val password = CharArray(0)
            val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            store.setKeyEntry("client", key, password, (listOf(own) + (chain - own)).toTypedArray())
            return store to password
        }

        private fun certificates(
            file: CertificateFile,
            bytes: ByteArray,
        ): List<X509Certificate> {
            val factory = CertificateFactory.getInstance("X.509")
            val ders =
                when (format(file, bytes)) {
                    CertificateFormat.PEM -> pem(bytes).filter { it.type == "CERTIFICATE" }.map { it.der }
                    else -> listOf(bytes)
                }
            if (ders.isEmpty()) throw SslConfigException("${file.path.fileName} contains no PEM CERTIFICATE")
            try {
                return ders
                    .flatMap {
                        factory.generateCertificates(
                            it.inputStream(),
                        )
                    }.filterIsInstance<X509Certificate>()
            } catch (e: CertificateException) {
                throw SslConfigException("${file.path.fileName} is no valid certificate: ${e.message}", e)
            }
        }

        private fun privateKey(
            file: CertificateFile,
            passphrase: () -> CharArray?,
        ): PrivateKey {
            val bytes = read(file, "client certificate key")
            val name = file.path.fileName
            val format = format(file, bytes)
            if (format == CertificateFormat.P12) {
                throw SslConfigException(
                    "clientCertificateKey $name must be PEM or DER; a P12 file goes in clientCertificate",
                )
            }
            if (format == CertificateFormat.DER) return derKey(file, bytes, passphrase)
            val block =
                pem(bytes).firstOrNull { it.type.endsWith("PRIVATE KEY") }
                    ?: throw SslConfigException("$name contains no PEM PRIVATE KEY")
            return when (block.type) {
                "PRIVATE KEY" -> {
                    pkcs8(file, block.der)
                }

                "ENCRYPTED PRIVATE KEY" -> {
                    decrypt(file, encryptedInfo(file, block.der), passphrase)
                }

                "RSA PRIVATE KEY" -> {
                    if (block.encrypted) {
                        throw SslConfigException(
                            "$name is an encrypted PKCS#1 key, which is not supported; $PKCS8_HINT",
                        )
                    }
                    pkcs8(file, pkcs1ToPkcs8(block.der))
                }

                "EC PRIVATE KEY" -> {
                    throw SslConfigException("$name is an EC key in SEC1 form, which is not supported; $PKCS8_HINT")
                }

                else -> {
                    throw SslConfigException("$name: ${block.type} is not supported; $PKCS8_HINT")
                }
            }
        }

        /** A DER key: PKCS#8, encrypted PKCS#8 or PKCS#1 RSA. */
        private fun derKey(
            file: CertificateFile,
            bytes: ByteArray,
            passphrase: () -> CharArray?,
        ): PrivateKey {
            runCatching { return pkcs8(file, bytes) }
            val encrypted = runCatching { encryptedInfo(file, bytes) }.getOrNull()
            if (encrypted != null) return decrypt(file, encrypted, passphrase)
            runCatching { return pkcs8(file, pkcs1ToPkcs8(bytes)) }
            throw SslConfigException("${file.path.fileName} is no PKCS#8 or PKCS#1 RSA key in DER form; $PKCS8_HINT")
        }

        private fun pkcs8(
            file: CertificateFile,
            der: ByteArray,
        ): PrivateKey {
            val spec = PKCS8EncodedKeySpec(der)
            try {
                return KeyFactory.getInstance(spec.algorithm ?: "RSA").generatePrivate(spec)
            } catch (e: GeneralSecurityException) {
                throw SslConfigException("${file.path.fileName} is no valid private key: ${e.message}", e)
            }
        }

        /** Also fails for encryptions the JDK cannot even describe, such as PBES2 with 3DES or scrypt. */
        private fun encryptedInfo(
            file: CertificateFile,
            der: ByteArray,
        ): EncryptedPrivateKeyInfo =
            try {
                EncryptedPrivateKeyInfo(der)
            } catch (e: IOException) {
                throw SslConfigException(
                    "${file.path.fileName} is encrypted in a way that is not supported or invalid (${e.message}); " +
                        ENCRYPTION_HINT,
                    e,
                )
            }

        private fun decrypt(
            file: CertificateFile,
            info: EncryptedPrivateKeyInfo,
            passphrase: () -> CharArray?,
        ): PrivateKey {
            val name = file.path.fileName
            val (factory, cipher) =
                try {
                    SecretKeyFactory.getInstance(info.algName) to Cipher.getInstance(info.algName)
                } catch (e: GeneralSecurityException) {
                    throw SslConfigException(
                        "$name uses an unsupported key encryption ${info.algName}; $ENCRYPTION_HINT",
                        e,
                    )
                }
            val password = passphrase() ?: throw SslConfigException("$name is encrypted, but no passphrase was given")
            val keySpec = PBEKeySpec(password)
            val spec =
                try {
                    cipher.init(Cipher.DECRYPT_MODE, factory.generateSecret(keySpec), info.algParameters)
                    info.getKeySpec(cipher)
                } catch (e: NoSuchAlgorithmException) {
                    throw SslConfigException(
                        "$name uses an unsupported key encryption ${info.algName}; $ENCRYPTION_HINT",
                        e,
                    )
                } catch (e: InvalidAlgorithmParameterException) {
                    throw SslConfigException(
                        "$name uses an unsupported key encryption ${info.algName}; $ENCRYPTION_HINT",
                        e,
                    )
                } catch (e: GeneralSecurityException) {
                    throw SslConfigException(
                        "$name cannot be decrypted; is the passphrase right? (${e.message})",
                        e,
                        passphraseRejected = true,
                    )
                } finally {
                    keySpec.clearPassword()
                    password.fill('\u0000')
                }
            return pkcs8(file, spec.encoded)
        }

        /** Whether [key] belongs to [certificate], tested by signing with the key and verifying with the certificate. */
        private fun belongs(
            certificate: X509Certificate,
            key: PrivateKey,
        ): Boolean {
            if (key.algorithm != certificate.publicKey.algorithm) return false
            val algorithm =
                when (key.algorithm) {
                    "RSA" -> "SHA256withRSA"
                    "EC" -> "SHA256withECDSA"
                    "EdDSA", "Ed25519", "Ed448" -> "EdDSA"
                    "DSA" -> "SHA256withDSA"
                    else -> return true
                }
            val data = ByteArray(32).also(SecureRandom()::nextBytes)
            val signature =
                Signature.getInstance(algorithm).run {
                    initSign(key)
                    update(data)
                    sign()
                }
            return try {
                Signature.getInstance(algorithm).run {
                    initVerify(certificate.publicKey)
                    update(data)
                    verify(signature)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
        }

        /** Wraps a PKCS#1 `RSAPrivateKey` in a PKCS#8 `PrivateKeyInfo` (RFC 5208) with the rsaEncryption algorithm. */
        private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            // SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
            val algorithm = HexFormat.of().parseHex("300d06092a864886f70d0101010500")
            val key = der(0x04, pkcs1)
            return der(0x30, version + algorithm + key)
        }

        private fun der(
            tag: Int,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            if (length < 0x80) {
                out.write(length)
            } else {
                val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }
                out.write(0x80 or bytes.size)
                bytes.forEach { out.write(it.toInt()) }
            }
            out.write(content)
            return out.toByteArray()
        }

        private class PemBlock(
            val type: String,
            val der: ByteArray,
            val encrypted: Boolean,
        )

        private fun pem(bytes: ByteArray): List<PemBlock> =
            PEM
                .findAll(bytes.toString(Charsets.US_ASCII))
                .map { match ->
                    val body = match.groupValues[2]
                    // Legacy OpenSSL encryption puts headers such as "Proc-Type: 4,ENCRYPTED" before the Base64.
                    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val headers = lines.takeWhile { ':' in it }
                    val base64 = lines.drop(headers.size).joinToString("")
                    val der =
                        try {
                            Base64.getDecoder().decode(base64)
                        } catch (e: IllegalArgumentException) {
                            throw SslConfigException("A PEM ${match.groupValues[1]} block is no valid Base64", e)
                        }
                    PemBlock(match.groupValues[1], der, headers.any { it.startsWith("Proc-Type") && "ENCRYPTED" in it })
                }.toList()

        /** The given format, else P12 by extension (.p12, .pfx), else PEM if the file looks like it, else DER. */
        private fun format(
            file: CertificateFile,
            bytes: ByteArray,
        ): CertificateFormat =
            file.format ?: when {
                file.path.extension.lowercase() in setOf("p12", "pfx") -> CertificateFormat.P12
                "-----BEGIN " in bytes.toString(Charsets.US_ASCII) -> CertificateFormat.PEM
                else -> CertificateFormat.DER
            }

        private fun read(
            file: CertificateFile,
            what: String,
        ): ByteArray =
            try {
                file.path.readBytes()
            } catch (e: ClosedByInterruptException) {
                // Cancelled while reading.
                throw InterruptedException().apply { initCause(e) }
            } catch (_: NoSuchFileException) {
                throw SslConfigException("The $what ${file.path} does not exist")
            } catch (e: IOException) {
                throw SslConfigException("The $what ${file.path} cannot be read: ${e.message}", e)
            }
    }

    /** Trusts every server and, as an [X509ExtendedTrustManager], also skips host name verification. */
    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
