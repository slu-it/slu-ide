package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class YamlHighlightingTest {
    private val yaml =
        Highlighted(
            """
            |# CI configuration
            |name: build
            |on:
            |  push:
            |    branches: [ main ]
            |jobs:
            |  test:
            |    runs-on: ubuntu-latest
            |    timeout: 1.5
            |    retries: 3 # per job
            |    enabled: true
            |    parent: null
            |    steps:
            |      - uses: "actions/checkout@v4"
            |      - 'plain item'
            |      - 42
            |"quoted key": x
            |defaults: &defaults
            |  url: http://example.com:8080/path
            |copy: *defaults
            |typed: !!str 123
            |---
            |
            """.trimMargin(),
            RSyntaxHighlighter.yaml,
        )

    @Test
    fun `keys are properties`() {
        for (key in listOf("name", "\non", "push", "branches", "runs-on", "uses", "\"quoted key\"", "url")) {
            assertEquals(setOf(TokenStyle.PROPERTY), yaml.stylesOf(key), key)
        }
        assertEquals(emptySet(), yaml.stylesOf("build"))
        assertEquals(emptySet(), yaml.stylesOf("http"), "a colon inside a value does not make a key")
    }

    @Test
    fun `scalars`() {
        assertEquals(setOf(TokenStyle.NUMBER), yaml.stylesOf("1.5"))
        assertEquals(setOf(TokenStyle.NUMBER), yaml.stylesOf("3 "))
        assertEquals(setOf(TokenStyle.NUMBER), yaml.stylesOf("42"))
        assertEquals(setOf(TokenStyle.KEYWORD), yaml.stylesOf("true"))
        assertEquals(setOf(TokenStyle.KEYWORD), yaml.stylesOf("null"))
        assertEquals(setOf(TokenStyle.STRING), yaml.stylesOf("\"actions/checkout@v4\""))
        assertEquals(setOf(TokenStyle.STRING), yaml.stylesOf("'plain item'"))
        assertEquals(emptySet(), yaml.stylesOf("123"), "only whole values are numbers")
    }

    @Test
    fun `comments, anchors, aliases, tags and document markers`() {
        assertEquals(setOf(TokenStyle.COMMENT), yaml.stylesOf("# CI configuration"))
        assertEquals(setOf(TokenStyle.COMMENT), yaml.stylesOf("# per job"))
        assertEquals(setOf(TokenStyle.ANNOTATION), yaml.stylesOf("&defaults"))
        assertEquals(setOf(TokenStyle.ANNOTATION), yaml.stylesOf("*defaults"))
        assertEquals(setOf(TokenStyle.ANNOTATION), yaml.stylesOf("!!str"))
        assertEquals(setOf(TokenStyle.KEYWORD), yaml.stylesOf("---"))
    }

    @Test
    fun `line rules never apply inside comments`() {
        val text = "# key: 1\nreal: 2\n"
        val spans = RSyntaxHighlighter.yaml.highlight(text)
        assertEquals(listOf(TokenStyle.COMMENT), spans.filter { it.end <= text.indexOf('\n') }.map { it.style })
    }

    @Test
    fun `spans are well-formed`() = yaml.assertWellFormed()
}
