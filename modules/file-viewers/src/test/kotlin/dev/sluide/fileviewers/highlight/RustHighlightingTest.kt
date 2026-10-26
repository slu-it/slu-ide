package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class RustHighlightingTest {
    private val rust =
        Highlighted(
            """
            |#![allow(dead_code)]
            |#[derive(Debug, Clone)]
            |#[cfg(feature = "x")]
            |pub struct Point<'a> { x: &'a str }
            |macro_rules! sq { (${'$'}x:expr) => { ${'$'}x * ${'$'}x }; }
            |impl<'a> Point<'a> {
            |    pub async fn run(&self) -> Result<(), Box<dyn Error>> {
            |        let c = 'x'; let nl = '\n'; let q = '\'';
            |        let r = r#"raw "quoted" text"#;
            |        println!("{} it's", sq!(3));
            |        let l: &'static str = "s";
            |        'outer: loop { break 'outer; }
            |        foo().await?; // end
            |        if a != (b) {}
            |        let v = vec![1, 2];
            |    }
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.rust,
        )

    @Test
    fun `lifetimes and labels do not become character literals`() {
        rust.assertExactly(TokenStyle.TYPE, "'a", occurrence = 0)
        rust.assertExactly(TokenStyle.TYPE, "'static")
        rust.assertExactly(TokenStyle.TYPE, "'outer", occurrence = 0)
        rust.assertExactly(TokenStyle.TYPE, "'outer", occurrence = 1)
        rust.assertExactly(TokenStyle.TYPE, "str", wholeWord = true) // text after a lifetime is lexed normally
        rust.assertExactly(TokenStyle.STRING, "\"s\"")
        rust.assertExactly(TokenStyle.COMMENT, "// end")
    }

    @Test
    fun `character literals and strings stay strings`() {
        rust.assertExactly(TokenStyle.STRING, "'x'")
        rust.assertExactly(TokenStyle.STRING, "'\\n'")
        rust.assertExactly(TokenStyle.STRING, "'\\''")
        rust.assertExactly(TokenStyle.STRING, "r#\"raw \"quoted\" text\"#")
        rust.assertExactly(TokenStyle.STRING, "\"{} it's\"")
    }

    @Test
    fun `keywords, attributes and macros`() {
        for (word in listOf("async", "await", "dyn", "pub", "fn", "impl", "loop")) {
            assertEquals(setOf(TokenStyle.KEYWORD), rust.stylesOf(word), word)
        }
        rust.assertExactly(TokenStyle.KEYWORD, "macro_rules!")
        rust.assertExactly(TokenStyle.ANNOTATION, "#![allow(dead_code)]")
        rust.assertExactly(TokenStyle.ANNOTATION, "#[derive(Debug, Clone)]")
        rust.assertExactly(TokenStyle.ANNOTATION, "#[cfg")
        rust.assertExactly(TokenStyle.STRING, "\"x\"")
        rust.assertExactly(TokenStyle.BUILTIN, "println!")
        rust.assertExactly(TokenStyle.BUILTIN, "vec!")
        assertEquals(emptySet(), rust.stylesOf("!=").minus(TokenStyle.OPERATOR), "!= is no macro call")
        assertEquals(setOf(TokenStyle.TYPE), rust.stylesOf("Result"))
    }
}
