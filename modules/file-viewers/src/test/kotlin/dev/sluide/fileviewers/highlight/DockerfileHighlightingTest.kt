package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerfileHighlightingTest {
    private val d = "$"

    private val dockerfile =
        Highlighted(
            """
            |# syntax=docker/dockerfile:1
            |ARG VERSION=3.12
            |FROM python:$d{VERSION}-slim AS build
            |ENV PATH="/app/bin:${d}PATH" HOME=${d}APP_HOME
            |COPY --from=build /src /app
            |RUN ["pip", "install", "-r", 'requirements.txt']
            |HEALTHCHECK CMD curl -f http://localhost/ || exit 1
            |SHELL ["/bin/bash", "-c"]
            |from alpine as final
            |
            """.trimMargin(),
            RSyntaxHighlighter.dockerfile,
        )

    @Test
    fun `instructions, also those the lexer misses, and build stage names`() {
        for (keyword in listOf("ARG", "FROM", "ENV", "COPY", "RUN", "HEALTHCHECK", "CMD", "SHELL", "from alpine")) {
            assertEquals(setOf(TokenStyle.KEYWORD), dockerfile.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), dockerfile.stylesOf("AS"))
        assertEquals(setOf(TokenStyle.KEYWORD), dockerfile.stylesOf("as final"))
        assertEquals(emptySet(), dockerfile.stylesOf("build"))
        val stage = Highlighted("FROM --platform=linux/amd64 node:20 AS deps\n", RSyntaxHighlighter.dockerfile)
        assertEquals(setOf(TokenStyle.KEYWORD), stage.stylesOf("AS"))
    }

    @Test
    fun `variables outside strings, strings and comments`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), dockerfile.stylesOf("$d{VERSION}"))
        assertEquals(setOf(TokenStyle.ANNOTATION), dockerfile.stylesOf("${d}APP_HOME"))
        assertEquals(setOf(TokenStyle.STRING), dockerfile.stylesOf("\"/app/bin:${d}PATH\""))
        assertEquals(setOf(TokenStyle.STRING), dockerfile.stylesOf("'requirements.txt'"))
        assertEquals(setOf(TokenStyle.COMMENT), dockerfile.stylesOf("# syntax=docker/dockerfile:1"))
    }

    @Test
    fun `spans are well-formed`() = dockerfile.assertWellFormed()
}
