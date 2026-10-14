package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class TsxHighlightingTest {
    private val tsx =
        Highlighted(
            """
            |type Props = { readonly title: string };
            |
            |export const Card = ({ title }: Props) => {
            |  const [open, setOpen] = useState<boolean>(false);
            |  return <section aria-label={title}>{open && <h2>{title}</h2>}</section>;
            |};
            |
            """.trimMargin(),
            RSyntaxHighlighter.typeScriptReact,
        )

    @Test
    fun `TypeScript corrections still apply`() {
        assertEquals(setOf(TokenStyle.KEYWORD), tsx.stylesOf("type"))
        assertEquals(setOf(TokenStyle.KEYWORD), tsx.stylesOf("readonly"))
        assertEquals(setOf(TokenStyle.TYPE), tsx.stylesOf("string"))
    }

    @Test
    fun `tags and attributes`() {
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("<section"))
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("<h2>"))
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("</h2>"))
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("</section>"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), tsx.stylesOf("aria-label"))
    }

    @Test
    fun `generics are no tags`() {
        assertEquals(setOf(TokenStyle.OPERATOR, TokenStyle.TYPE), tsx.stylesOf("<boolean>"))
    }

    @Test
    fun `spans are well-formed`() = tsx.assertWellFormed()
}
