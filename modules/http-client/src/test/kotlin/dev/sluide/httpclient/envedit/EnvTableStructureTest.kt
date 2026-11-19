package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rows of the [EnvTable]: which environments have entries and fields, where the files disagree in shape. */
class EnvTableStructureTest {
    private fun table(
        public: String? = null,
        private: String? = null,
    ) = EnvDocument(public, private).table

    private fun List<Row>.row(label: String) = single { it.label == label }

    @Test
    fun `a list has as many entries as its longest copy, whichever environment has it`() {
        val longFirst = table(public = """{"a": {"l": [1, 2, 3]}, "b": {"l": [1]}, "c": {}}""").variables.row("l")
        val longLast = table(public = """{"a": {"l": [1]}, "b": {"l": [1, 2, 3]}, "c": {"l": []}}""").variables.row("l")
        val onlyLast = table(public = """{"a": {}, "b": {"l": [1, 2]}}""").variables.row("l")
        val empty = table(public = """{"a": {"l": []}, "b": {}}""").variables.row("l")

        assertEquals(listOf("[0]", "[1]", "[2]"), longFirst.children.map { it.label })
        assertEquals(listOf("[0]", "[1]", "[2]"), longLast.children.map { it.label })
        assertEquals(listOf("[0]", "[1]"), onlyLast.children.map { it.label })
        assertEquals(emptyList(), empty.children)
        assertEquals(Cell.UNDEFINED, longFirst.cells.getValue("c"))
        assertEquals(
            Cell.UNDEFINED,
            longLast.children
                .last()
                .cells
                .getValue("a"),
        )
    }

    @Test
    fun `an environment that is no object in a file has no values and no cells`() {
        val table =
            table(
                public = """{"dev": {"a": 1, "Security": {"Auth": {"x": {"Type": "t"}}}}, "prod": {"b": 2}}""",
                private = """{"prod": [1], "dev": {"c": 3}}""",
            )

        assertEquals(setOf("prod"), table.readOnlyEnvironments)
        assertEquals(listOf("a", "c"), table.variables.map { it.label })
        assertEquals(
            Cell.UNDEFINED,
            table.variables
                .row("a")
                .cells
                .getValue("prod"),
        )
        assertEquals(
            setOf("dev"),
            table.auth
                .single()
                .rows
                .first()
                .applicable,
        )
    }

    @Test
    fun `an auth section or id that is no object leaves the groups of the others alone`() {
        val table =
            table(
                public =
                    """
                    {"a": {"Security": {"Auth": {"x": {"Type": "t"}, "y": 1}}},
                     "b": {"Security": {"Auth": 5}},
                     "c": {"Security": "text"},
                     "d": {"Security": {"Auth": {"x": 1}}}}
                    """.trimIndent(),
            )

        assertEquals(listOf("x"), table.auth.map { it.id })
        assertEquals(
            setOf("a"),
            table.auth
                .single()
                .rows
                .row("Type")
                .applicable,
        )
        assertEquals(
            listOf(
                "${EnvironmentFiles.PUBLIC}: Auth \"y\" of environment \"a\" must be an object",
                "${EnvironmentFiles.PUBLIC}: Security.Auth of environment \"b\" must be an object",
                "${EnvironmentFiles.PUBLIC}: Security of environment \"c\" must be an object",
                "${EnvironmentFiles.PUBLIC}: Auth \"x\" of environment \"d\" must be an object",
            ),
            table.problems,
        )
    }

    @Test
    fun `Username and Password apply where the grant type is Password, however it is written`() {
        val table =
            table(
                public =
                    """
                    {"a": {"Security": {"Auth": {"x": {"Grant Type": " PASSWORD "}}}},
                     "b": {"Security": {"Auth": {"x": {"Grant Type": "Client Credentials"}}}},
                     "c": {"Security": {"Auth": {"x": {"Type": "t"}}}},
                     "d": {"Security": {"Auth": {"x": {"Grant Type": 5}}}},
                     "e": {"Security": {"Auth": {"x": {"Grant Type": "Password"}}}}}
                    """.trimIndent(),
            )
        val rows = table.auth.single().rows

        assertEquals(setOf("a", "e"), rows.row("Username").applicable)
        assertEquals(setOf("a", "e"), rows.row("Password").applicable)
        assertEquals(setOf("a", "b", "c", "d", "e"), rows.row("Scope").applicable)
        assertEquals(setOf("a", "b", "c", "d", "e"), rows.row("Client Secret").applicable)
    }

    @Test
    fun `offers choices for a field only while all its values are of its type`() {
        val same = table(public = """{"a": {"Security": {"Auth": {"x": {"Grant Type": "Password"}}}}}""")
        val mixed =
            table(
                public =
                    """
                    {"a": {"Security": {"Auth": {"x": {"Grant Type": "Password"}}}},
                     "b": {"Security": {"Auth": {"x": {"Grant Type": 5}}}}}
                    """.trimIndent(),
            )

        assertEquals(
            EnvSchema.GRANT_TYPES,
            same.auth
                .single()
                .rows
                .row("Grant Type")
                .choices,
        )
        assertNull(
            same.auth
                .single()
                .rows
                .row("Type")
                .choices,
        )
        assertEquals(
            ValueType.MIXED,
            mixed.auth
                .single()
                .rows
                .row("Grant Type")
                .type,
        )
        assertNull(
            mixed.auth
                .single()
                .rows
                .row("Grant Type")
                .choices,
        )
    }

    @Test
    fun `the certificate rows are shown when only the public file has settings, and not for read-only environments`() {
        val onlyPublic = table(public = """{"dev": {"SSLConfiguration": {"clientCertificate": "c"}}}""")
        val readOnly =
            table(
                public = """{"prod": 5}""",
                private = """{"prod": {"SSLConfiguration": {"clientCertificate": "c"}}}""",
            )

        assertNotNull(onlyPublic.certificate)
        assertEquals(1, onlyPublic.problems.size)
        assertNull(readOnly.certificate)
        assertEquals(1, readOnly.problems.size)
    }

    @Test
    fun `a certificate row is read-only and offers no choices where the values are of another type`() {
        val table =
            table(
                private =
                    """
                    {"a": {"SSLConfiguration": {"clientCertificate": {"path": "p", "format": "PEM"},
                                                "verifyHostCertificate": true}},
                     "b": {"SSLConfiguration": {"clientCertificate": "q", "verifyHostCertificate": "yes"}}}
                    """.trimIndent(),
            )
        val rows = assertNotNull(table.certificate)

        assertEquals(ValueType.TEXT, rows.row("Certificate").type)
        assertEquals(false, rows.row("Certificate").readOnly)
        assertEquals(listOf("", "PEM", "P12", "DER"), rows.row("Certificate format").choices)
        assertEquals(ValueType.MIXED, rows.row("Verify host certificate").type)
        assertTrue(rows.row("Verify host certificate").readOnly)
        assertNull(rows.row("Verify host certificate").choices)
        assertEquals(setOf("a", "b"), rows.row("Key").applicable)
    }
}
