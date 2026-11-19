package dev.sluide.httpclient.envedit

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [EnvSchema]: where the known fields live, and the paths and keys of the model's entries and edits. */
internal class EnvSchemaTest {
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("authConfigs")
    fun `only Security Auth and an id is the object of an auth configuration`(
        path: ValuePath,
        expected: Boolean,
    ) {
        assertEquals(expected, EnvSchema.isAuthConfig(path))
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("sides")
    fun `puts certificate settings, Client Secret and Password in the private file, other known fields in the public`(
        path: ValuePath,
        expected: FileSide?,
    ) {
        assertEquals(expected, EnvSchema.schemaSide(path))
        assertEquals(expected ?: FileSide.PUBLIC, EnvSchema.defaultSide(path))
    }

    @Test
    fun `tells certificate settings by the first segment`() {
        assertTrue(EnvSchema.isCertificate(ValuePath.of("SSLConfiguration")))
        assertTrue(EnvSchema.isCertificate(ValuePath.of("SSLConfiguration", "x")))
        assertFalse(EnvSchema.isCertificate(ValuePath.of("x", "SSLConfiguration")))
        assertFalse(EnvSchema.isCertificate(ValuePath.ROOT))
    }

    companion object {
        private val api = ValuePath.of("Security", "Auth", "api")

        @JvmStatic
        fun authConfigs() =
            listOf(
                Arguments.of(api, true),
                Arguments.of(ValuePath.of("Security", "Auth", "other id"), true),
                Arguments.of(ValuePath.of("Security", "Auth"), false),
                Arguments.of(api.key("Type"), false),
                Arguments.of(ValuePath.of("Other", "Auth", "api"), false),
                Arguments.of(ValuePath.of("Security", "Other", "api"), false),
                Arguments.of(ValuePath.of("a", "b", "c"), false),
                Arguments.of(ValuePath.ROOT, false),
            )

        @JvmStatic
        fun sides() =
            listOf(
                Arguments.of(ValuePath.of("SSLConfiguration"), FileSide.PRIVATE),
                Arguments.of(ValuePath.of("SSLConfiguration", "anything"), FileSide.PRIVATE),
                Arguments.of(api.key("Client Secret"), FileSide.PRIVATE),
                Arguments.of(api.key("Password"), FileSide.PRIVATE),
                Arguments.of(api.key("Type"), FileSide.PUBLIC),
                Arguments.of(api.key("Scope"), FileSide.PUBLIC),
                Arguments.of(api.key("Custom Request Parameters"), FileSide.PUBLIC),
                Arguments.of(api.key("Unknown"), null),
                Arguments.of(api.index(0), null),
                Arguments.of(api.key("Custom Request Parameters").key("Type"), null),
                Arguments.of(ValuePath.of("Security", "Auth"), null),
                Arguments.of(ValuePath.of("Other", "Auth", "api", "Type"), null),
                Arguments.of(ValuePath.of("db", "user"), null),
                Arguments.of(ValuePath.of("host"), null),
                Arguments.of(ValuePath.ROOT, null),
            )
    }
}
