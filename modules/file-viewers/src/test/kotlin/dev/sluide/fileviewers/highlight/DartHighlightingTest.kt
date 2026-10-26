package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DartHighlightingTest {
    private val dart =
        Highlighted(
            """
            |import 'dart:io';
            |sealed class Shape {}
            |base class A {}
            |abstract base mixin class B {}
            |var base = 1;
            |mixin M {}
            |class P { late final int x; P({required this.x}); }
            |Future<void> main() async {
            |  var s = 'hello ${'$'}name';
            |  var r = r'raw \n';
            |  var m = '''multi
            |  line''';
            |  await foo(); // end
            |  var v = switch (x) { 1 when x > 0 => 'a', _ => 'b' };
            |  @override
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.dart,
        )

    @Test
    fun `Dart 3 keywords`() {
        for (word in listOf("sealed", "mixin", "late", "required", "when", "await", "async")) {
            assertEquals(setOf(TokenStyle.KEYWORD), dart.stylesOf(word), word)
        }
        assertEquals(setOf(TokenStyle.TYPE), dart.stylesOf("Future"))
    }

    @Test
    fun `base is a keyword only before class or mixin`() {
        dart.assertExactly(TokenStyle.KEYWORD, "base", occurrence = 0)
        dart.assertExactly(TokenStyle.KEYWORD, "base", occurrence = 1)
        dart.assertPlain("base", occurrence = 2)
    }

    @Test
    fun `strings, raw strings and annotations`() {
        dart.assertExactly(TokenStyle.STRING, "'dart:io'")
        assertEquals(setOf(TokenStyle.STRING), dart.stylesOf("r'raw \\n'"), "the r belongs to the string")
        assertEquals(setOf(TokenStyle.STRING), dart.stylesOf("'''multi\n  line'''"))
        dart.assertExactly(TokenStyle.ANNOTATION, "@override")
        dart.assertExactly(TokenStyle.COMMENT, "// end")
    }
}
