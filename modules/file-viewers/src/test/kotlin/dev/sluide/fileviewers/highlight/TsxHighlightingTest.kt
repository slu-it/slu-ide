package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class TsxHighlightingTest {
    private val tsx =
        Highlighted(
            """
            |type Props = { readonly title: string };
            |type Fn = <T>(x: T) => T;
            |const el = <div className="x">hi</div>;
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
        // After `= <` the lexer reads markup; the rules style it like any other JSX.
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("<div"))
        assertEquals(setOf(TokenStyle.ATTRIBUTE), tsx.stylesOf("className"))
        assertEquals(setOf(TokenStyle.TAG), tsx.stylesOf("</div>"))
    }

    @Test
    fun `generics are no tags`() {
        assertEquals(setOf(TokenStyle.OPERATOR, TokenStyle.TYPE), tsx.stylesOf("<boolean>"))
        assertEquals(emptySet(), tsx.stylesOf("<T>"))
    }

    @Test
    fun `spans are well-formed`() = tsx.assertWellFormed()
}
