package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class CSharpHighlightingTest {
    private val q = "\"\"\""
    private val dollar = '$'

    private val cs =
        Highlighted(
            """
            |global using System;
            |[Serializable]
            |public record Person(string Name) { public required string Id { get; init; } }
            |file class Hidden { }
            |var file = 1;
            |var record = 1; var get = 2; var set = record;
            |public record struct Point(int X);
            |public record class Named { }
            |public record Brace
            |{
            |}
            |var x = init; var y = f(get);
            |public class C {
            |    [Obsolete("x")]
            |    [Flags, Serializable]
            |    public async Task<int> Run() {
            |        var s = $dollar"hello {name}";
            |        var v = @"verbatim \ ""q$q;
            |        var r = $q
            |            raw "quoted" text
            |            $q;
            |        await Task.Delay(1);
            |        var p = person with { Age = 3 };
            |        if (o is int i when i > 3) { }
            |        string n = nameof(p); // end
            |        arr[i] = 2;
            |    }
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.csharp,
        )

    @Test
    fun `contextual keywords`() {
        for (word in listOf("global", "required", "init", "async", "await", "with", "when", "nameof")) {
            assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf(word), word)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("file class"), "file before a type is a modifier")
        assertEquals(emptySet(), cs.stylesOf("file", occurrence = 1), "file as a name stays plain")
    }

    @Test
    fun `record and the accessors are keywords only where they are`() {
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("record Person"))
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("record struct Point"))
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("record class Named"))
        cs.assertPlain("record", occurrence = 1)
        cs.assertPlain("get", occurrence = 1)
        cs.assertPlain("set")
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("get;"))
        cs.assertExactly(TokenStyle.KEYWORD, "record", occurrence = 5, wholeWord = true) // brace on the next line
        cs.assertPlain("init", occurrence = 1, wholeWord = true)
        cs.assertPlain("get", occurrence = 2, wholeWord = true)
    }

    @Test
    fun `strings, interpolated, verbatim and raw`() {
        assertEquals(setOf(TokenStyle.STRING), cs.stylesOf("$dollar\"hello {name}\""))
        cs.assertExactly(TokenStyle.STRING, "@\"verbatim \\ \"\"q\"\"\"")
        assertEquals(setOf(TokenStyle.STRING), cs.stylesOf("$q\n            raw \"quoted\" text\n            $q"))
        assertEquals(setOf(TokenStyle.KEYWORD), cs.stylesOf("await"), "code after the raw string is lexed again")
        cs.assertExactly(TokenStyle.COMMENT, "// end")
    }

    @Test
    fun `attributes on their own line are annotations`() {
        cs.assertExactly(TokenStyle.ANNOTATION, "[Serializable]")
        cs.assertExactly(TokenStyle.ANNOTATION, "[Flags, Serializable]")
        assertEquals(setOf(TokenStyle.ANNOTATION), cs.stylesOf("[Obsolete"))
        assertEquals(setOf(TokenStyle.STRING), cs.stylesOf("\"x\""))
        cs.assertExactlyAt(TokenStyle.ANNOTATION, cs.find("(\"x\")]") + 5, 1, "closing bracket of [Obsolete(…)]")
        assertEquals(emptySet(), cs.stylesOf("[i]"), "an index is no attribute")
    }
}
