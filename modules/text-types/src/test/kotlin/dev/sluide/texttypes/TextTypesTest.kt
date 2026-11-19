package dev.sluide.texttypes

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextTypesTest {
    @Test
    fun `the list holds every type in picker order, No extension last`() {
        assertEquals(
            listOf(
                "Plain Text txt",
                "Markdown md",
                "JSON json",
                "YAML yaml",
                "XML xml",
                "HTML html",
                "SVG svg",
                "CSS css",
                "JavaScript js",
                "TypeScript ts",
                "Kotlin kt",
                "Kotlin Script kts",
                "Java java",
                "Python py",
                "Shell Script sh",
                "SQL sql",
                "HTTP Request http",
                "TOML toml",
                "Properties properties",
                "CSV csv",
                "TSV tsv",
                "Mermaid mmd",
                "KQL kql",
                "Bicep bicep",
                "Diff diff",
                "No extension null",
            ),
            TextTypes.all.map { "${it.name} ${it.extension}" },
        )
        assertEquals(TextTypes.PLAIN_TEXT, TextTypes.all.first())
        assertEquals(TextTypes.NO_EXTENSION, TextTypes.all.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["yaml", ".yaml", "YAML", ".Yaml"])
    fun `a type is found by its extension with or without the dot, ignoring case`(extension: String) {
        assertEquals(TextType("YAML", "yaml"), TextTypes.byExtension(extension))
    }

    @Test
    fun `the first and the last type with an extension are found`() {
        assertEquals(TextTypes.PLAIN_TEXT, TextTypes.byExtension("txt"))
        assertEquals(TextType("Diff", "diff"), TextTypes.byExtension("diff"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".", "yml", "ya", "yaml.", "..yaml", "null"])
    fun `an unknown extension, an empty one and No extension's null find nothing`(extension: String) {
        assertNull(TextTypes.byExtension(extension))
    }
}
