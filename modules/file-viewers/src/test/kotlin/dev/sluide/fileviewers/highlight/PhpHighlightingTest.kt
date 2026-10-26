package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhpHighlightingTest {
    private val d = "$"

    private val php =
        Highlighted(
            """
            |<?php
            |declare(strict_types=1);
            |#[Attribute(Attribute::TARGET_CLASS)]
            |final class Route
            |{
            |    public function __construct(public readonly string ${d}path) {}
            |}
            |enum Status: string { case Active = 'active'; }
            |#[Route('/home', name: 'home')]
            |#[Assert\Choice(['a', 'b'])]
            |final class Home
            |{
            |    # hash comment
            |    // ${d}this->foo() commented out
            |    # Foo::bar()
            |    ${d}s = "#[x]";
            |    // see #[Route] here
            |    /* #[X] */
            |    /**
            |     * #[Deprecated]
            |     */
            |    // slash comment
            |    public function index(mixed ${d}x): never
            |    {
            |        ${d}fn = fn(${d}a) => ${d}a * 2;
            |        ${d}r = match (${d}x) { 1 => "one ${d}x", default => 'two' };
            |        return static::class . ${d}this->name;
            |    }
            |}
            |?>
            |<p><?= ${d}title ?></p>
            |
            """.trimMargin(),
            RSyntaxHighlighter.php,
        )

    @Test
    fun `PHP 8 keywords and types`() {
        for (keyword in listOf("readonly", "enum", "match", "fn(", "static", "final", "class", "default")) {
            assertEquals(setOf(TokenStyle.KEYWORD), php.stylesOf(keyword), keyword)
        }
        for (type in listOf("string", "mixed", "never")) {
            assertEquals(setOf(TokenStyle.TYPE), php.stylesOf(type), type)
        }
    }

    @Test
    fun `attributes are annotations, not comments`() {
        php.assertExactly(TokenStyle.ANNOTATION, "#[Attribute(Attribute::TARGET_CLASS)]")
        php.assertExactly(TokenStyle.ANNOTATION, "#[Route('/home', name: 'home')]")
        php.assertExactly(TokenStyle.ANNOTATION, "#[Assert\\Choice(['a', 'b'])]")
    }

    @Test
    fun `hash and slash comments stay comments`() {
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("# hash comment"))
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("// slash comment"))
    }

    @Test
    fun `no attribute inside comments`() {
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("// see #[Route] here"))
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("/* #[X] */"))
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("* #[Deprecated]"))
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("// ${d}this->foo() commented out"))
        assertEquals(setOf(TokenStyle.COMMENT), php.stylesOf("# Foo::bar()"))
        assertEquals(setOf(TokenStyle.STRING), php.stylesOf("\"#[x]\""))
    }

    @Test
    fun `attributes after a parenthesis, a comma or another attribute`() {
        val inline =
            Highlighted(
                "<?php function f(#[Sensitive] ${d}a, #[A, B] ${d}b) {}\n#[A] #[B(1)] public function g() {}\n",
                RSyntaxHighlighter.php,
            )
        inline.assertExactly(TokenStyle.ANNOTATION, "#[Sensitive]")
        inline.assertExactly(TokenStyle.ANNOTATION, "#[A, B]")
        inline.assertExactly(TokenStyle.ANNOTATION, "#[A]")
        inline.assertExactly(TokenStyle.ANNOTATION, "#[B(1)]")
        // Code after an attribute on the same line is code, not comment.
        assertEquals(setOf(TokenStyle.KEYWORD), inline.stylesOf("public"))
        assertEquals(setOf(TokenStyle.KEYWORD), inline.stylesOf("function", 1))
    }

    @Test
    fun `member names are no keywords or types`() {
        val members =
            Highlighted(
                "<?php ${d}t->string('e'); ${d}r?->match(1); Foo::object(); Foo::Active; static::class; string ${d}x;\n",
                RSyntaxHighlighter.php,
            )
        members.assertExactly(TokenStyle.PROPERTY, "string")
        members.assertExactly(TokenStyle.PROPERTY, "match")
        members.assertExactly(TokenStyle.PROPERTY, "object")
        members.assertExactly(TokenStyle.PROPERTY, "Active")
        assertEquals(setOf(TokenStyle.KEYWORD), members.stylesOf("class"))
        assertEquals(setOf(TokenStyle.TYPE), members.stylesOf("string", 1))
    }

    @Test
    fun `variables are annotations`() {
        for (variable in listOf("${d}path", "${d}fn", "${d}title")) {
            assertEquals(setOf(TokenStyle.ANNOTATION), php.stylesOf(variable), variable)
        }
        assertEquals(setOf(TokenStyle.ANNOTATION), php.stylesOf("${d}this", 1))
    }

    @Test
    fun `HTML around PHP tags`() {
        assertEquals(setOf(TokenStyle.TAG), php.stylesOf("<?php"))
        assertTrue(TokenStyle.TAG in php.stylesOf("<?= "))
        assertEquals(setOf(TokenStyle.TAG), php.stylesOf("<p>"))
    }

    @Test
    fun `strings`() {
        assertEquals(setOf(TokenStyle.STRING, TokenStyle.ANNOTATION), php.stylesOf("\"one ${d}x\""))
        assertEquals(setOf(TokenStyle.STRING), php.stylesOf("'active'"))
    }

    @Test
    fun `spans are well-formed`() = php.assertWellFormed()
}
