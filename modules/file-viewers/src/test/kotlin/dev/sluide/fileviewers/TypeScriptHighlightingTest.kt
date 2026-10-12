package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeScriptHighlightingTest {
    private val ts =
        Highlighted(
            """
            |import type { Config } from "./config";
            |
            |interface Item { readonly id: string; tags?: number[] }
            |type Id = 'a' | "b";
            |enum Kind { One = 1 }
            |
            |@Injectable()
            |export abstract class Repo<T> implements Store {
            |  private cache: unknown = null;
            |  constructor(public name: string) {}
            |  has(v: unknown): v is T { return (v as any) satisfies object; } // check
            |}
            |declare const big: bigint = 10n;
            |const label = `id ${'$'}{1}`;
            |
            """.trimMargin(),
            RSyntaxHighlighter.typeScript,
        )

    @Test
    fun keywords() {
        val keywords =
            listOf("import", "type", "from", "interface", "readonly", "enum", "export", "abstract", "class", "private")
        for (keyword in keywords) {
            assertEquals(setOf(TokenStyle.KEYWORD), ts.stylesOf(keyword), keyword)
        }
        // In context, so that e.g. "is" does not match inside another word; the rest of each snippet is unstyled.
        for (snippet in listOf("constructor(", "v is", "(v as", " satisfies ", "declare ", " null;")) {
            assertEquals(setOf(TokenStyle.KEYWORD), ts.stylesOf(snippet), snippet)
        }
    }

    @Test
    fun types() {
        for (type in listOf("string", "number", "unknown", "any", "object", "bigint")) {
            assertEquals(setOf(TokenStyle.TYPE), ts.stylesOf(type), type)
        }
    }

    @Test
    fun `decorators, strings, comments and numbers`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), ts.stylesOf("@Injectable"))
        assertEquals(setOf(TokenStyle.STRING), ts.stylesOf("\"./config\""))
        assertEquals(setOf(TokenStyle.STRING), ts.stylesOf("'a'"))
        assertEquals(setOf(TokenStyle.STRING), ts.stylesOf("`id "))
        assertEquals(setOf(TokenStyle.COMMENT), ts.stylesOf("// check"))
        assertEquals(setOf(TokenStyle.NUMBER), ts.stylesOf("10n"))
        assertEquals(emptySet(), ts.stylesOf("Repo"))
    }

    @Test
    fun `spans are well-formed`() = ts.assertWellFormed()
}
