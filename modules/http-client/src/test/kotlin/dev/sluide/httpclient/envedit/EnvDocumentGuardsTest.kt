package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.envedit.FileSide.PRIVATE
import dev.sluide.httpclient.envedit.FileSide.PUBLIC
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What an [EnvDocument] refuses and says about it; the edits it accepts are in [EnvDocumentTest]. */
internal class EnvDocumentGuardsTest {
    internal class Refusal(
        val name: String,
        val message: String,
        val call: (EnvDocument) -> Unit,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `refuses a wrong argument with its reason`(refusal: Refusal) {
        val error = assertFailsWith<IllegalArgumentException> { refusal.call(DOCUMENT) }

        assertEquals(refusal.message, error.message)
    }

    @Test
    fun `refuses every edit of files that cannot be read, naming the file and the reason`() {
        val broken = EnvDocument("""{"dev": {}}""", "{")

        val error = assertFailsWith<IllegalStateException> { broken.addVariable(ValuePath.ROOT, "a", ValueType.TEXT) }

        assertTrue(
            error.message.orEmpty().startsWith("The environment files cannot be edited: ${EnvironmentFiles.PRIVATE}: "),
            error.message,
        )
        assertTrue(
            broken.table.error
                .orEmpty()
                .startsWith("${EnvironmentFiles.PRIVATE}: "),
            broken.table.error,
        )
        assertEquals(
            "${EnvironmentFiles.PUBLIC}: expected an object of environments",
            EnvDocument("[]", null).table.error,
        )
        assertEquals(
            "${EnvironmentFiles.PRIVATE}: expected an object of environments",
            EnvDocument(null, "5").table.error,
        )
    }

    @Test
    fun `environments that are no objects are left out of edits of all environments`() {
        val document = EnvDocument("""{"dev": {"a": 1, "l": [1]}, "prod": [1]}""", """{"local": {"a": 2, "l": [2]}}""")

        assertEquals(
            """{"dev":{"a":1,"l":[1],"b":""},"prod":[1]}""",
            compact(document.addVariable(ValuePath.ROOT, "b", ValueType.TEXT).public),
        )
        assertEquals(
            """{"dev":{"a":1,"l":[1,""]},"prod":[1]}""",
            compact(document.appendListEntry(ValuePath.of("l"), ValueType.TEXT).public),
        )
        assertEquals(
            """{"dev":{"a":"1","l":[1]},"prod":[1]}""",
            compact(document.setType(ValuePath.of("a"), ValueType.TEXT).public),
        )
        assertEquals("""{"dev":{"l":[1]},"prod":[1]}""", compact(document.removeRow(ValuePath.of("a")).public))
    }

    @Test
    fun `a reserved name is only refused at the top of an environment`() {
        val document = EnvDocument("""{"dev": {"db": {}}}""", null)

        assertEquals(
            """{"dev":{"db":{"Security":""}}}""",
            compact(document.addVariable(ValuePath.of("db"), "Security", ValueType.TEXT).public),
        )
        assertEquals(
            """{"dev":{"db":{},"a b":""}}""",
            compact(document.addVariable(ValuePath.ROOT, "a b", ValueType.TEXT).public),
        )
    }

    @Test
    fun `an auth id and a grant type are matched as written or ignoring the case of the grant type`() {
        val document = EnvDocument("""{"dev": {}}""", null)

        val added = document.addAuth("api", "client credentials", listOf("dev")).public.orEmpty()

        assertTrue("\"Grant Type\": \"Client Credentials\"" in added, added)
    }

    private fun compact(text: String?): String = EnvJson.parse(text.orEmpty())?.toString().orEmpty()

    companion object {
        private val DOCUMENT =
            EnvDocument(
                """
                {"dev": {"db": {"user": "u"}, "url": "x",
                  "Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}, "prod": [1]}
                """.trimIndent(),
                """{"local": {}}""",
            )
        private val certificate = ValuePath.of("SSLConfiguration", "clientCertificate")
        private val text = EnvJson.nodes.stringNode("x")

        private fun refusal(
            name: String,
            message: String,
            call: (EnvDocument) -> Unit,
        ) = Arguments.of(Refusal(name, message, call))

        @JvmStatic
        fun refusals() =
            listOf(
                refusal("set the environment", "Set the values of an environment, not the environment") {
                    it.setValue("dev", ValuePath.ROOT, text)
                },
                refusal("set an object", "\"db\" is an object; set its entries") {
                    it.setValue("dev", ValuePath.of("db"), text)
                },
                refusal("set a value of an environment that is no object", prodNotEditable()) {
                    it.setValue("prod", ValuePath.of("a"), text)
                },
                refusal("remove the environment", "Remove an environment with removeEnvironment") {
                    it.remove("dev", ValuePath.ROOT)
                },
                refusal("a side of the environment", "An environment has no side") {
                    it.setSide("dev", ValuePath.ROOT, PRIVATE)
                },
                refusal(
                    "a certificate setting to the public file",
                    "Certificate settings are read from the private file only",
                ) {
                    it.setSide("dev", certificate, PUBLIC)
                },
                refusal("the side of what is not defined", "\"nope\" is not defined in environment \"dev\"") {
                    it.setSide("dev", ValuePath.of("nope"), PRIVATE)
                },
                refusal("the side of an object", "Objects have no file of their own; move their entries") {
                    it.setSide("dev", ValuePath.of("db"), PRIVATE)
                },
                refusal("the type mixed", "Mixed is not a type of a value") {
                    it.setType(ValuePath.of("url"), ValueType.MIXED)
                },
                refusal("the type of a certificate setting", "Certificate settings have fixed types") {
                    it.setType(certificate, ValueType.NUMBER)
                },
                refusal("the loss of a certificate setting", "Certificate settings have fixed types") {
                    it.isLossy(certificate, ValueType.NUMBER)
                },
                refusal("a variable without a name", "A variable needs a name") {
                    it.addVariable(ValuePath.ROOT, "", ValueType.TEXT)
                },
                refusal("a variable of the type mixed", "Mixed is not a type of a value") {
                    it.addVariable(ValuePath.ROOT, "a", ValueType.MIXED)
                },
                refusal("a variable named Security", "\"Security\" is not a variable") {
                    it.addVariable(ValuePath.ROOT, "Security", ValueType.TEXT)
                },
                refusal("a variable named SSLConfiguration", "\"SSLConfiguration\" is not a variable") {
                    it.addVariable(ValuePath.ROOT, "SSLConfiguration", ValueType.TEXT)
                },
                refusal("a variable of the auth configurations", "Auth configurations are added with addAuth") {
                    it.addVariable(ValuePath.of("Security", "Auth"), "x", ValueType.TEXT)
                },
                refusal("a field of every auth configuration", "\"Type\" is a field of every auth configuration") {
                    it.addVariable(ValuePath.of("Security", "Auth", "api"), "Type", ValueType.TEXT)
                },
                refusal("a variable that exists", "\"db.user\" already exists") {
                    it.addVariable(ValuePath.of("db"), "user", ValueType.TEXT)
                },
                refusal("a list entry of the type mixed", "Mixed is not a type of a value") {
                    it.appendListEntry(ValuePath.of("l"), ValueType.MIXED)
                },
                refusal("an environment without a name", "An environment needs a name") {
                    it.addEnvironment(" ")
                },
                refusal("an environment named like a shared one", "Environment names cannot start with \$") {
                    it.addEnvironment("\$shared")
                },
                refusal("an environment that exists", "\"local\" already exists") {
                    it.addEnvironment("local")
                },
                refusal("a new name that is taken", "\"local\" already exists") {
                    it.renameEnvironment("dev", "local")
                },
                refusal("the rename of an unknown environment", "There is no environment \"nope\"") {
                    it.renameEnvironment("nope", "x")
                },
                refusal("the removal of an unknown environment", "There is no environment \"nope\"") {
                    it.removeEnvironment("nope")
                },
                refusal("an auth without an id", "An auth configuration needs an id") {
                    it.addAuth(" ", "password", listOf("dev"))
                },
                refusal("an auth of an unknown grant type", "Unsupported grant type \"implicit\"") {
                    it.addAuth("x", "implicit", listOf("dev"))
                },
                refusal("an auth that exists", "Environment \"dev\" already has auth \"api\"") {
                    it.addAuth("api", "password", listOf("local", "dev"))
                },
                refusal("an auth in an unknown environment", "There is no environment \"nope\"") {
                    it.addAuth("x", "password", listOf("nope"))
                },
                refusal("a certificate in an environment that is no object", prodNotEditable()) {
                    it.addCertificate(listOf("prod"))
                },
            )

        private fun prodNotEditable() = "Environment \"prod\" is not an object and cannot be edited"
    }
}
