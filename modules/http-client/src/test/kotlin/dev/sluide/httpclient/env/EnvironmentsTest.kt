package dev.sluide.httpclient.env

import dev.sluide.httpclient.env.AuthConfig.ClientCredentials
import dev.sluide.httpclient.env.AuthConfig.CustomParameter
import dev.sluide.httpclient.env.AuthConfig.ParameterUse
import dev.sluide.httpclient.env.SslConfig.CertificateFile
import dev.sluide.httpclient.env.SslConfig.CertificateFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentsTest {
    @TempDir
    lateinit var root: Path

    private fun load(
        public: String? = null,
        private: String? = null,
    ): Environments {
        val publicFile = public?.let { root.resolve(EnvironmentFiles.PUBLIC).also { file -> file.writeText(it) } }
        val privateFile = private?.let { root.resolve(EnvironmentFiles.PRIVATE).also { file -> file.writeText(it) } }
        return Environments.load(EnvironmentFiles(publicFile, privateFile))
    }

    @Test
    fun `lists the environments of both files`() {
        val environments =
            load(
                public = """{"dev": {"host": "a"}, "prod": {"host": "b"}}""",
                private = """{"local": {"host": "c"}, "dev": {"token": "t"}}""",
            )

        assertEquals(listOf("dev", "prod", "local"), environments.names)
        assertTrue(environments.problems.isEmpty())
    }

    @Test
    fun `reads files that start with a byte order mark`() {
        val environments =
            load(
                public = "\uFEFF{\"dev\": {\"host\": \"a\"}}",
                private = "\uFEFF{\"dev\": {\"token\": \"t\"}}",
            )

        assertTrue(environments.problems.isEmpty(), environments.problems.toString())
        assertEquals("""{"host":"a","token":"t"}""", environments["dev"]?.values.toString())
    }

    @Test
    fun `reads Windows-1252 files as the editor does, and others with replacement characters`() {
        val publicFile = root.resolve(EnvironmentFiles.PUBLIC)
        publicFile.writeBytes("{\"dev\": {\"user\": \"Jörg\"}}".toByteArray(Charset.forName("windows-1252")))
        // 0x81 is undefined in Windows-1252.
        val privateFile = root.resolve(EnvironmentFiles.PRIVATE)
        privateFile.writeBytes("{\"dev\": {\"token\": \"a".toByteArray() + 0x81.toByte() + "\"}}".toByteArray())

        val environments = Environments.load(EnvironmentFiles(publicFile, privateFile))

        assertTrue(environments.problems.isEmpty(), environments.problems.toString())
        assertEquals("""{"user":"Jörg","token":"a${Char(0xFFFD)}"}""", environments["dev"]?.values.toString())
    }

    @Test
    fun `deep merges the private values over the public ones`() {
        val dev =
            load(
                public = """{"dev": {"host": "a", "user": {"name": "x", "roles": [1, 2]}, "port": 80}}""",
                private = """{"dev": {"user": {"password": "p", "roles": [3]}, "port": 8080}}""",
            )["dev"]

        assertNotNull(dev)
        assertEquals(
            """{"host":"a","user":{"name":"x","roles":[3],"password":"p"},"port":8080}""",
            dev.values.toString(),
        )
        assertTrue(dev.problems.isEmpty())
    }

    @Test
    fun `works with only one of the files`() {
        assertEquals(listOf("dev"), load(private = """{"dev": {}}""").names)
        assertEquals(listOf("dev"), load(public = """{"dev": {}}""").names)
        assertEquals(emptyList(), Environments.load(EnvironmentFiles(null, null)).names)
    }

    @Test
    fun `reports broken files and keeps the other one`() {
        val environments = load(public = """{"dev": {"host": """, private = """{"local": {}}""")

        assertEquals(listOf("local"), environments.names)
        assertTrue(environments.problems.single().startsWith(EnvironmentFiles.PUBLIC))
    }

    @Test
    fun `reports files and environments of the wrong shape`() {
        val environments = load(public = "[1]", private = """{"dev": 5}""")

        assertEquals(listOf("${EnvironmentFiles.PUBLIC}: expected an object of environments"), environments.problems)
        assertEquals(
            listOf("${EnvironmentFiles.PRIVATE}: environment \"dev\" must be an object"),
            environments["dev"]?.problems,
        )
    }

    @Test
    fun `ignores shared values`() {
        val environments = load(public = """{"${'$'}shared": {"a": 1}, "dev": {}}""")

        assertEquals(listOf("dev"), environments.names)
        assertEquals(listOf("\"\$shared\" is not supported and ignored"), environments.problems)
    }

    @Test
    fun `reads auth configurations merged from both files`() {
        val dev =
            load(
                public =
                    """
                    {"dev": {"Security": {"Auth": {"api": {
                        "Type": "OAuth2",
                        "Grant Type": "Client Credentials",
                        "Token URL": "{{issuer}}/token",
                        "Client ID": "client",
                        "Client Credentials": "in body",
                        "Scope": "read write",
                        "Custom Request Parameters": {
                            "audience": "https://api",
                            "resource": ["r1", "r2"],
                            "prompt": {"Value": "login", "Use": "In Auth Request"},
                            "x": {"Value": "y", "Use": "In Token Request"}
                        },
                        "Use ID Token": true,
                        "Acquire Automatically": false
                    }}}}}
                    """.trimIndent(),
                private = """{"dev": {"Security": {"Auth": {"api": {"Client Secret": "secret"}}}}}""",
            )["dev"]

        assertNotNull(dev)
        assertEquals(
            AuthConfig(
                id = "api",
                type = "OAuth2",
                grantType = "Client Credentials",
                tokenUrl = "{{issuer}}/token",
                clientId = "client",
                clientSecret = "secret",
                clientCredentials = ClientCredentials.IN_BODY,
                username = null,
                password = null,
                scope = "read write",
                customRequestParameters =
                    listOf(
                        CustomParameter("audience", "https://api", ParameterUse.EVERYWHERE),
                        CustomParameter("resource", "r1", ParameterUse.EVERYWHERE),
                        CustomParameter("resource", "r2", ParameterUse.EVERYWHERE),
                        CustomParameter("prompt", "login", ParameterUse.IN_AUTH_REQUEST),
                        CustomParameter("x", "y", ParameterUse.IN_TOKEN_REQUEST),
                    ),
                useIdToken = true,
                acquireAutomatically = false,
            ),
            dev.auth["api"],
        )
        assertTrue(dev.problems.isEmpty())
        assertNull(dev.values.get("Security"))
    }

    @Test
    fun `defaults auth flags and client credentials`() {
        val auth =
            load(public = """{"dev": {"Security": {"Auth": {"a": {"Type": "OAuth2"}}}}}""")["dev"]?.auth?.get("a")

        assertNotNull(auth)
        assertEquals(ClientCredentials.BASIC, auth.clientCredentials)
        assertEquals(false, auth.useIdToken)
        assertEquals(true, auth.acquireAutomatically)
    }

    @Test
    fun `reports auth values it does not understand`() {
        val dev =
            load(
                public =
                    """{"dev": {"Security": {"Auth": {"a": {"Client Credentials": "header", "Use ID Token": 3}, "b": 1}}}}""",
            )["dev"]

        assertNotNull(dev)
        assertEquals(setOf("a"), dev.auth.keys)
        assertEquals(
            listOf(
                "Auth \"b\" must be an object",
                "Auth \"a\": unknown Client Credentials \"header\" (basic, in body or none); basic is used",
                "Auth \"a\": \"Use ID Token\" must be true or false",
            ),
            dev.problems,
        )
    }

    @Test
    fun `reports a Security Auth that is not an object`() {
        val dev = load(public = """{"dev": {"Security": {"Auth": [1]}}}""")["dev"]

        assertNotNull(dev)
        assertEquals(listOf("Security.Auth must be an object"), dev.problems)
        assertTrue(dev.auth.isEmpty())
    }

    @Test
    fun `an empty or blank file reports no problem`() {
        for (text in listOf("", "  \n")) {
            val environments = load(public = text)

            assertTrue(environments.problems.isEmpty(), "'$text': ${environments.problems}")
            assertTrue(environments.names.isEmpty())
        }
    }

    @Test
    fun `reports SSL settings it does not understand`() {
        fun problems(ssl: String) = load(private = """{"dev": {"SSLConfiguration": $ssl}}""")["dev"]?.problems

        assertEquals(listOf("SSLConfiguration must be an object"), problems("[]"))
        assertEquals(
            listOf("SSLConfiguration: \"verifyHostCertificate\" must be true or false"),
            problems("""{"verifyHostCertificate": "no"}"""),
        )
        val needsPath = listOf("SSLConfiguration: \"clientCertificate\" needs a path")
        assertEquals(needsPath, problems("""{"clientCertificate": ""}"""))
        assertEquals(needsPath, problems("""{"clientCertificate": {"format": "pem"}}"""))
        assertEquals(needsPath, problems("""{"clientCertificate": {"path": 5}}"""))
        assertEquals(
            listOf("SSLConfiguration: unknown format \"pfx\" of \"clientCertificateKey\" (PEM, P12 or DER)"),
            problems("""{"clientCertificateKey": {"path": "k", "format": "pfx"}}"""),
        )
    }

    @Test
    fun `reads the SSL configuration from the private file with paths relative to it`() {
        val dev =
            load(
                public = """{"dev": {"host": "a"}}""",
                private =
                    """
                    {"dev": {"SSLConfiguration": {
                        "clientCertificate": "certs/client.pem",
                        "clientCertificateKey": {"path": "/keys/client.p12", "format": "p12"},
                        "hasCertificatePassphrase": true,
                        "verifyHostCertificate": false
                    }}}
                    """.trimIndent(),
            )["dev"]

        assertNotNull(dev)
        assertEquals(
            SslConfig(
                clientCertificate = CertificateFile(root.resolve("certs/client.pem"), null),
                clientCertificateKey = CertificateFile(Path.of("/keys/client.p12"), CertificateFormat.P12),
                hasCertificatePassphrase = true,
                verifyHostCertificate = false,
            ),
            dev.ssl,
        )
        assertNull(dev.values.get("SSLConfiguration"))
        assertTrue(dev.problems.isEmpty())
    }

    @Test
    fun `verifies host certificates by default`() {
        val ssl = load(private = """{"dev": {"SSLConfiguration": {}}}""")["dev"]?.ssl

        assertEquals(SslConfig(null, null, hasCertificatePassphrase = false, verifyHostCertificate = true), ssl)
    }

    @Test
    fun `ignores the SSL configuration of the public file`() {
        val dev = load(public = """{"dev": {"SSLConfiguration": {"verifyHostCertificate": false}}}""")["dev"]

        assertNotNull(dev)
        assertNull(dev.ssl)
        assertEquals(1, dev.problems.size)
    }

    @Test
    fun `reports a broken file with its name and the parser's message, whichever file it is`() {
        val broken = load(public = """{"dev": {}}""", private = """{"dev": {"host": """)

        assertEquals(listOf("dev"), broken.names)
        assertEquals(1, broken.problems.size)
        assertTrue(
            broken.problems.single().startsWith("${EnvironmentFiles.PRIVATE}: Unexpected end-of-input"),
            broken.problems.toString(),
        )
    }

    @Test
    fun `reports a file that cannot be read with its name and the reason`() {
        val folder = root.resolve("folder").also { it.toFile().mkdir() }
        val environments = Environments.load(EnvironmentFiles(null, folder))

        assertEquals(emptyList(), environments.names)
        assertEquals(1, environments.problems.size)
        assertTrue(environments.problems.single().startsWith("folder: "), environments.problems.toString())
        assertTrue(environments.problems.single().length > "folder: ".length, environments.problems.toString())
    }

    @Test
    fun `the private values of an environment are those of the private file alone`() {
        root.resolve(EnvironmentFiles.PUBLIC).writeText("""{"dev": {"host": "a"}}""")
        root.resolve(EnvironmentFiles.PRIVATE).writeText("""{"dev": {"token": "t"}, "other": 5, "[]": []}""")
        val files = EnvironmentFiles(root.resolve(EnvironmentFiles.PUBLIC), root.resolve(EnvironmentFiles.PRIVATE))

        assertEquals("""{"token":"t"}""", Environments.privateValues(files, "dev").toString())
        assertNull(Environments.privateValues(files, "missing"))
        assertNull(Environments.privateValues(files, "other"))
        assertNull(Environments.privateValues(files, "[]"))
        assertNull(Environments.privateValues(EnvironmentFiles(files.public, null), "dev"))
        root.resolve(EnvironmentFiles.PRIVATE).writeText("[1]")
        assertNull(Environments.privateValues(files, "dev"))
    }

    @Test
    fun `a Security without Auth gives no auth configurations and no problem`() {
        val dev = load(public = """{"dev": {"Security": {"Other": 1}, "host": "a"}}""")["dev"]

        assertNotNull(dev)
        assertTrue(dev.auth.isEmpty())
        assertTrue(dev.problems.isEmpty())
        assertEquals("""{"host":"a"}""", dev.values.toString())
    }

    @Test
    fun `a value of the private file replaces a value of the other type and the other way round`() {
        val dev =
            load(
                public = """{"dev": {"a": 1, "b": {"x": 1}, "c": {"x": 1}}}""",
                private = """{"dev": {"a": {"y": 2}, "b": 5, "c": {"y": 2}, "d": {"z": 3}}}""",
            )["dev"]

        assertNotNull(dev)
        assertEquals("""{"a":{"y":2},"b":5,"c":{"x":1,"y":2},"d":{"z":3}}""", dev.values.toString())
    }

    @Test
    fun `an SSL configuration of a wrong type does not hide the other settings and keeps the secure defaults`() {
        val ssl =
            load(
                private =
                    """{"dev": {"SSLConfiguration": {"hasCertificatePassphrase": "yes", "verifyHostCertificate": "no"}}}""",
            )["dev"]?.ssl

        assertEquals(SslConfig(null, null, hasCertificatePassphrase = false, verifyHostCertificate = true), ssl)
    }

    @Test
    fun `a certificate that is null or given with a known format needs no path and gives no problem`() {
        val dev =
            load(
                private =
                    """
                    {"dev": {"SSLConfiguration": {
                        "clientCertificate": null,
                        "clientCertificateKey": {"path": "k.der", "format": "DER"}
                    }}}
                    """.trimIndent(),
            )["dev"]

        assertNotNull(dev)
        assertEquals(emptyList(), dev.problems)
        assertEquals(
            SslConfig(null, CertificateFile(root.resolve("k.der"), CertificateFormat.DER), false, true),
            dev.ssl,
        )
    }
}
