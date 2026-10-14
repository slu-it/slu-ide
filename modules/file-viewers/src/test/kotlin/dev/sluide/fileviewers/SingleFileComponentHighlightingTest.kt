package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

/** Vue and Svelte components. */
class SingleFileComponentHighlightingTest {
    private val vue =
        Highlighted(
            """
            |<template>
            |  <button :class="kind" @click="count++">{{ count }}</button>
            |  <MyCard title="x" />
            |</template>
            |<script setup>
            |const count = ref(0)
            |if (count.value <limit && ok) go("<b>")
            |</script>
            |<style scoped>
            |.btn { color: red; }
            |</style>
            |
            """.trimMargin(),
            RSyntaxHighlighter.singleFileComponent,
        )

    private val svelte =
        Highlighted(
            """
            |<script>
            |  export let name = 'world';
            |</script>
            |{#if name}
            |  <h1 class="title">Hello {name}!</h1>
            |{/if}
            |
            """.trimMargin(),
            RSyntaxHighlighter.singleFileComponent,
        )

    @Test
    fun `Vue template, script and style`() {
        assertEquals(setOf(TokenStyle.TAG), vue.stylesOf("<template>"))
        assertEquals(setOf(TokenStyle.TAG), vue.stylesOf("<button"))
        assertEquals(setOf(TokenStyle.TAG), vue.stylesOf("<MyCard"))
        assertEquals(setOf(TokenStyle.TAG), vue.stylesOf("</template>"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), vue.stylesOf(":class"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), vue.stylesOf("@click"))
        assertEquals(setOf(TokenStyle.STRING), vue.stylesOf("\"kind\""))
        assertEquals(setOf(TokenStyle.KEYWORD), vue.stylesOf("const"))
        assertEquals(setOf(TokenStyle.NUMBER), vue.stylesOf("0"))
        assertEquals(setOf(TokenStyle.TYPE), vue.stylesOf(".btn"))
        assertEquals(setOf(TokenStyle.OPERATOR), vue.stylesOf("<limit"))
        assertEquals(setOf(TokenStyle.STRING), vue.stylesOf("\"<b>\""))
        assertEquals(setOf(TokenStyle.KEYWORD), vue.stylesOf("color"))
    }

    @Test
    fun `Svelte script and markup`() {
        assertEquals(setOf(TokenStyle.KEYWORD), svelte.stylesOf("export"))
        assertEquals(setOf(TokenStyle.STRING), svelte.stylesOf("'world'"))
        assertEquals(setOf(TokenStyle.TAG), svelte.stylesOf("<h1"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), svelte.stylesOf("class"))
    }

    @Test
    fun `spans are well-formed`() {
        vue.assertWellFormed()
        svelte.assertWellFormed()
    }
}
