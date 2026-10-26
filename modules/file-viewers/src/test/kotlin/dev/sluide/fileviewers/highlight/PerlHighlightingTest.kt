package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PerlHighlightingTest {
    private val d = "$"

    private val perl =
        Highlighted(
            """
            |#!/usr/bin/perl
            |use strict;
            |# comment
            |my ${d}name = "World";
            |my @list = (1, 2);
            |my %hash = (a => 1);
            |print "Hello ${d}name @list\n";
            |foreach my ${d}i (@list) { next unless ${d}i =~ /^\d+$d/; }
            |sub greet { my (${d}who) = @_; return 'x'; }
            |my @w = qw(a b c);
            |my ${d}s = q{quoted} . qq[two];
            |${d}obj->q(1);
            |
            |=pod
            |
            |This is pod
            |
            |=cut
            |
            |print "done";
            |printf("%d items, %-5s and %.2f%%\n", ${d}n, ${d}s, ${d}f);
            |my ${d}out = qx(ls -l);
            |
            """.trimMargin(),
            RSyntaxHighlighter.perl,
        )

    @Test
    fun `my, use and the like are keywords`() {
        for (keyword in listOf("my", "use", "sub", "foreach", "return", "next", "unless")) {
            assertEquals(setOf(TokenStyle.KEYWORD), perl.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), perl.stylesOf("print"))
    }

    @Test
    fun `all three sigils are annotations`() {
        for (variable in listOf("${d}name", "@list", "%hash", "${d}i", "@_", "${d}who")) {
            assertEquals(setOf(TokenStyle.ANNOTATION), perl.stylesOf(variable), variable)
        }
    }

    @Test
    fun `quote-like operators are strings, a method q is not`() {
        perl.assertExactly(TokenStyle.STRING, "qw(a b c)")
        perl.assertExactly(TokenStyle.STRING, "q{quoted}")
        perl.assertExactly(TokenStyle.STRING, "qq[two]")
        assertEquals(emptySet(), perl.stylesOf("q(1)").filter { it == TokenStyle.STRING }.toSet())
    }

    @Test
    fun `format specifiers in strings are strings, not hashes`() {
        for (specifier in listOf("%d", "%-5s", "%.2f")) {
            assertEquals(setOf(TokenStyle.STRING), perl.stylesOf(specifier, occurrence = 0), specifier)
        }
        perl.assertExactly(TokenStyle.STRING, "qx(ls -l)")
    }

    @Test
    fun `comments, shebang and pod`() {
        assertEquals(setOf(TokenStyle.COMMENT), perl.stylesOf("#!/usr/bin/perl"))
        assertEquals(setOf(TokenStyle.COMMENT), perl.stylesOf("# comment"))
        assertEquals(setOf(TokenStyle.COMMENT), perl.stylesOf("=pod"))
        assertEquals(setOf(TokenStyle.COMMENT), perl.stylesOf("This is pod"))
        assertEquals(setOf(TokenStyle.COMMENT), perl.stylesOf("=cut"))
    }

    @Test
    fun `spans are well-formed`() = perl.assertWellFormed()
}
