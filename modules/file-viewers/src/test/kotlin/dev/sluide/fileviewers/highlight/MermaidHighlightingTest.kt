package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MermaidHighlightingTest {
    private fun mermaid(text: String) = Highlighted(text, RSyntaxHighlighter.mermaid)

    private val flowchart =
        mermaid(
            """
            |%%{init: {"theme": "forest"}}%%
            |flowchart LR
            |    %% a comment --> with an arrow
            |    start-node["Start --> here"] -->|Yes| B{Is it?}
            |    B -.-> C ==> D --- E
            |    D -- text --> F
            |    E -. maybe .-> G[Wait...]
            |    subgraph one
            |        direction TB
            |        x1 --o x2
            |    end
            |    classDef warm fill:#f96
            |    style D stroke:#333
            |    block --> pie
            |
            """.trimMargin(),
        )

    @Test
    fun `the diagram type and statements are keywords`() {
        flowchart.assertExactly(TokenStyle.KEYWORD, "flowchart")
        flowchart.assertExactly(TokenStyle.KEYWORD, "LR")
        for (word in listOf("subgraph", "direction", "TB", "end", "classDef", "style")) {
            flowchart.assertExactly(TokenStyle.KEYWORD, word)
        }
        assertEquals(emptySet(), flowchart.stylesOf("one"))
        assertEquals(emptySet(), flowchart.stylesOf("warm"))
    }

    @Test
    fun `the diagram type counts only on its first line, after comments and front matter`() {
        assertEquals(emptySet(), flowchart.stylesOf("block"), "a node named like a diagram type")
        assertEquals(emptySet(), flowchart.stylesOf("pie"))

        val withFrontMatter = mermaid("---\ntitle: Flow\n---\n%% note\nflowchart-elk TB\n  graph --> x\n")
        withFrontMatter.assertExactly(TokenStyle.ANNOTATION, "---")
        withFrontMatter.assertExactly(TokenStyle.ANNOTATION, "---", 1)
        withFrontMatter.assertExactly(TokenStyle.KEYWORD, "flowchart-elk")
        withFrontMatter.assertExactly(TokenStyle.KEYWORD, "TB")
        assertEquals(emptySet(), withFrontMatter.stylesOf("graph"))
    }

    @Test
    fun `statements followed by a shape or a link are node names`() {
        val text = mermaid("flowchart TD\n    title[Title] --> class\n    state --> end\n    A[end of style] --> B\n")
        assertEquals(emptySet(), text.stylesOf("title"))
        assertEquals(emptySet(), text.stylesOf("state"))
        assertEquals(emptySet(), text.stylesOf("end"))
        assertEquals(emptySet(), text.stylesOf("style"))
    }

    @Test
    fun `comments and directives`() {
        flowchart.assertExactly(TokenStyle.ANNOTATION, "%%{init: {\"theme\": \"forest\"}}%%")
        flowchart.assertExactly(TokenStyle.COMMENT, "%% a comment --> with an arrow")
    }

    @Test
    fun `links are operators, strings and link labels strings`() {
        flowchart.assertExactly(TokenStyle.OPERATOR, "-->", 2)
        flowchart.assertExactly(TokenStyle.STRING, "|Yes|")
        flowchart.assertExactly(TokenStyle.OPERATOR, "-.->")
        flowchart.assertExactly(TokenStyle.OPERATOR, "==>")
        flowchart.assertExactly(TokenStyle.OPERATOR, "---")
        flowchart.assertExactly(TokenStyle.OPERATOR, "--o")
        // Occurrences overlap: `---` has two.
        flowchart.assertExactly(TokenStyle.OPERATOR, "--", 5)
        flowchart.assertExactly(TokenStyle.OPERATOR, "-->", 3)
        flowchart.assertExactly(TokenStyle.OPERATOR, "-.", 1)
        flowchart.assertExactly(TokenStyle.OPERATOR, ".->", 1)
        assertEquals(emptySet(), flowchart.stylesOf(" text "))
        assertEquals(emptySet(), flowchart.stylesOf(" maybe "))
        flowchart.assertExactly(TokenStyle.STRING, "\"Start --> here\"")
        assertEquals(emptySet(), flowchart.stylesOf("start-node"), "hyphens in names are no arrows")
        assertEquals(emptySet(), flowchart.stylesOf("..."), "an ellipsis is no link")
        assertEquals(emptySet(), flowchart.stylesOf("x1"))
    }

    @Test
    fun `arrows of sequence diagrams`() {
        val sequence =
            mermaid(
                "sequenceDiagram\n    participant Alice\n    Alice->>Bob: Hi\n    Bob-->>Alice: Hello\n" +
                    "    Alice-)Bob: async\n    Alice-xBob: lost\n    Bob--xAlice: lost too\n" +
                    "    Note right of Bob: thinks\n    loop Every minute\n    end\n    my-xylophone->>Bob: x\n",
            )
        sequence.assertExactly(TokenStyle.KEYWORD, "sequenceDiagram")
        sequence.assertExactly(TokenStyle.KEYWORD, "participant")
        sequence.assertExactly(TokenStyle.OPERATOR, "->>")
        sequence.assertExactly(TokenStyle.OPERATOR, "-->>")
        sequence.assertExactly(TokenStyle.OPERATOR, "-)")
        sequence.assertExactly(TokenStyle.OPERATOR, "-x")
        sequence.assertExactly(TokenStyle.OPERATOR, "--x")
        sequence.assertExactly(TokenStyle.KEYWORD, "right of")
        sequence.assertExactly(TokenStyle.KEYWORD, "loop")
        assertEquals(emptySet(), sequence.stylesOf("my-xylophone"))
    }

    @Test
    fun `links of class and entity relationship diagrams`() {
        val classes = mermaid("classDiagram\n    Animal <|-- Duck\n    A ..|> B\n    C .. D\n")
        classes.assertExactly(TokenStyle.OPERATOR, "<|--")
        classes.assertExactly(TokenStyle.OPERATOR, "..|>")
        classes.assertExactly(TokenStyle.OPERATOR, "..", 1)

        val entities = mermaid("erDiagram\n    CUSTOMER ||--o{ ORDER : places\n    A }|..|{ B : x\n")
        entities.assertExactly(TokenStyle.OPERATOR, "||--o{")
        entities.assertExactly(TokenStyle.OPERATOR, "}|..|{")
    }

    @Test
    fun `mermaid code fences use it`() {
        assertSame(RSyntaxHighlighter.mermaid, RSyntaxHighlighter.forLanguage("mermaid"))
        assertSame(RSyntaxHighlighter.mermaid, RSyntaxHighlighter.forLanguage("mmd"))
        val markdown = Highlighted("# Flow\n\n```mermaid\ngraph LR\n  A --> B\n```\n", RSyntaxHighlighter.markdown)
        markdown.assertExactly(TokenStyle.KEYWORD, "graph")
        markdown.assertExactly(TokenStyle.OPERATOR, "-->")
    }

    @Test
    fun `spans are well-formed`() = flowchart.assertWellFormed()
}
