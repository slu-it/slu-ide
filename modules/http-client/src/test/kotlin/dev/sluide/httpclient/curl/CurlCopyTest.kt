package dev.sluide.httpclient.curl

import dev.sluide.httpclient.parse.HttpFileParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlCopyTest {
    @TempDir
    lateinit var folder: Path

    /** The command of the single request of [lines] in `api.http` of the [folder], with [environment] chosen. */
    private fun copy(
        vararg lines: String,
        environment: String? = "dev",
    ): CopiedCommand {
        val file = HttpFileParser.parse(lines.joinToString("\n"))
        return CurlCopy
            .load(folder.resolve("api.http"), folder, environment, file.variables)
            .command(file.requests.single())
    }

    private fun environments() {
        folder.resolve("http-client.env.json").writeText(
            """{"dev": {"host": "https://dev.example.com", "user": "ada", "db": {"name": "main"}}}""",
        )
        folder.resolve("http-client.private.env.json").writeText(
            """{"dev": {"token": "s3cret", "db": {"password": "pw"}}, "other": {"host": "https://other"}}""",
        )
    }

    @Test
    fun `resolves the environment, file and dynamic variables`() {
        environments()

        val command =
            copy(
                "@base = {{host}}/v1",
                "",
                "POST {{base}}/users?db={{db.name}}",
                "X-Id: {{\$random.integer(5, 6)}}",
                "",
                "{\"user\": \"{{user}}\"}",
            )

        assertEquals(
            "curl 'https://dev.example.com/v1/users?db=main' \\\n  -L \\\n  -H 'X-Id: 5' \\\n" +
                "  -d '{\"user\": \"ada\"}'",
            command.keepingPrivate,
        )
        assertEquals(emptyList(), command.privateNames)
        assertEquals(command.keepingPrivate, command.withPrivate)
        assertEquals(emptyList(), command.unresolved)
    }

    @Test
    fun `private values stay variables unless included, also when used through other variables`() {
        environments()

        val command =
            copy(
                "@auth = Bearer {{token}}",
                "GET {{host}}/x",
                "Authorization: {{auth}}",
                "X-Db: {{db.password}}",
            )

        assertEquals(listOf("token", "db.password"), command.privateNames)
        assertTrue("-H 'Authorization: Bearer {{token}}'" in command.keepingPrivate, command.keepingPrivate)
        assertTrue("-H 'X-Db: {{db.password}}'" in command.keepingPrivate, command.keepingPrivate)
        assertTrue("-H 'Authorization: Bearer s3cret'" in command.withPrivate, command.withPrivate)
        assertTrue("-H 'X-Db: pw'" in command.withPrivate, command.withPrivate)
        assertEquals(emptyList(), command.unresolved)
    }

    @Test
    fun `the answer decides what is copied`() {
        val command = CopiedCommand("keep", "with", listOf("token"), emptyList())
        val asked = mutableListOf<List<String>>()

        assertTrue(command.asks)
        assertEquals(
            "with",
            command.copied {
                asked += it
                PrivateValuesAnswer.INCLUDE
            },
        )
        assertEquals("keep", command.copied { PrivateValuesAnswer.KEEP })
        assertNull(command.copied { PrivateValuesAnswer.CANCEL })
        assertEquals(listOf(listOf("token")), asked)
        assertEquals(
            "The request uses private values: token, password. Include them in the copied command?",
            privateValuesQuestion(listOf("token", "password")),
        )
    }

    @Test
    fun `without private values nothing is asked`() {
        val command = CopiedCommand("keep", "keep", emptyList(), emptyList())

        assertEquals("keep", command.copied { error("asked") })
    }

    @Test
    fun `the message names the notes and the unresolved variables`() {
        environments()

        val command = copy("GET {{host}}/x?q={{missing}}", "> {% client.log(1) %}")

        assertEquals("Copied as cURL; Response handler dropped; unresolved: missing", command.message)
        assertEquals("Copied as cURL", CopiedCommand("a", "a", emptyList(), emptyList()).message)
    }

    @Test
    fun `adds -k when the environment does not verify host certificates`() {
        folder.resolve("http-client.private.env.json").writeText(
            """{"dev": {"SSLConfiguration": {"verifyHostCertificate": false}}, "on": {}}""",
        )

        assertEquals("curl https://x \\\n  -L \\\n  -k", copy("GET https://x").keepingPrivate)
        assertEquals("curl https://x \\\n  -L", copy("GET https://x", environment = "on").keepingPrivate)
    }

    @Test
    fun `auth tokens and missing variables stay as written, only the missing ones are listed`() {
        environments()

        val command =
            copy(
                "GET {{host}}/x",
                "Authorization: Bearer {{\$auth.token(\"api\")}}",
                "X-Missing: {{missing}}",
                environment = null,
            )

        assertEquals(listOf("host", "missing"), command.unresolved)
        assertEquals(
            "curl '{{host}}/x' \\\n  -L \\\n  -H 'Authorization: Bearer {{\$auth.token(\"api\")}}' \\\n" +
                "  -H 'X-Missing: {{missing}}'",
            command.keepingPrivate,
        )
        assertEquals(false, command.asks)
    }

    @Test
    fun `body files get absolute paths`() {
        val command = copy("POST https://x", "Content-Type: text/plain", "", "< ./data/{{name}}.txt", "< ../b.txt")

        assertTrue("--data-binary '@data/{{name}}.txt'" in command.keepingPrivate, command.keepingPrivate)
        val absolute = CurlWriter.quote("@${folder.parent.resolve("b.txt")}", Shell.POSIX)
        assertTrue("--data-binary $absolute" in command.keepingPrivate, command.keepingPrivate)
    }
}
