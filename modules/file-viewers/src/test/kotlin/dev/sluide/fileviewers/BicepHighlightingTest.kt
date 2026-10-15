package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BicepHighlightingTest {
    private fun bicep(text: String) = Highlighted(text, BicepHighlighter)

    /** Where [snippet] is: its first occurrence after the first [after] (from the start if empty). */
    private fun Highlighted.find(
        snippet: String,
        after: String,
    ): Int {
        val from = if (after.isEmpty()) 0 else text.indexOf(after).also { check(it >= 0) { "not in text: $after" } }
        return text.indexOf(snippet, from).also { check(it >= 0) { "not in text: $snippet after $after" } }
    }

    /** [snippet] (the first after [after]) is exactly one span of [style], from its first to its last character. */
    private fun Highlighted.assertExactly(
        style: TokenStyle,
        snippet: String,
        after: String = "",
    ) {
        val start = find(snippet, after)
        val overlapping = spans.filter { it.start < start + snippet.length && it.end > start }
        assertEquals(listOf(HighlightSpan(start, start + snippet.length, style)), overlapping, "$snippet after $after")
    }

    /** [snippet] (the first after [after]) has no style. */
    private fun Highlighted.assertPlain(
        snippet: String,
        after: String = "",
    ) {
        val start = find(snippet, after)
        val overlapping = spans.filter { it.start < start + snippet.length && it.end > start }
        assertEquals(emptyList(), overlapping, "$snippet after $after")
    }

    private val main = bicep(MAIN_BICEP)

    @Test
    fun `spans are sorted, disjoint and within the text`() {
        main.assertWellFormed()
        bicep(PARAMS_BICEP).assertWellFormed()
    }

    @Test
    fun `line and block comments`() {
        main.assertExactly(TokenStyle.COMMENT, "// Storage for the app")
        main.assertExactly(TokenStyle.COMMENT, "/* The storage account\n   and its container */")
        main.assertExactly(TokenStyle.COMMENT, "// resource fake 'x' = {}")
        bicep("/* open\ncomment").assertExactly(TokenStyle.COMMENT, "/* open\ncomment")
    }

    @Test
    fun `keywords`() {
        for (word in listOf("targetScope", "metadata", "import", "param", "type", "func", "var", "resource")) {
            main.assertExactly(TokenStyle.KEYWORD, word, after = "\n$word")
        }
        main.assertExactly(TokenStyle.KEYWORD, "false", after = "|| ")
        main.assertExactly(TokenStyle.KEYWORD, "null", after = "?? ")
        main.assertExactly(TokenStyle.KEYWORD, "for", after = "= [")
        main.assertExactly(TokenStyle.KEYWORD, "in", after = "for name ")
        main.assertExactly(TokenStyle.KEYWORD, "as", after = "tagSet ")
        main.assertExactly(TokenStyle.KEYWORD, "existing")
        main.assertExactly(TokenStyle.KEYWORD, "module", after = "\nmodule")
        main.assertExactly(TokenStyle.KEYWORD, "if", after = "network.bicep'")
        main.assertExactly(TokenStyle.KEYWORD, "output", after = "\noutput")
    }

    @Test
    fun `built-in types and declared type names`() {
        main.assertExactly(TokenStyle.TYPE, "string", after = "param location")
        main.assertExactly(TokenStyle.TYPE, "array", after = "containerNames")
        main.assertExactly(TokenStyle.TYPE, "object", after = "param config")
        main.assertExactly(TokenStyle.TYPE, "int", after = "'cost-center'")
        main.assertExactly(TokenStyle.TYPE, "skuName")
        main.assertExactly(TokenStyle.TYPE, "tagSet", after = "type ")
    }

    @Test
    fun `decorators`() {
        for (decorator in listOf("@description", "@secure", "@minLength", "@sys.description", "@batchSize")) {
            main.assertExactly(TokenStyle.ANNOTATION, decorator)
        }
        main.assertExactly(TokenStyle.NUMBER, "12", after = "@minLength")
    }

    @Test
    fun `functions are builtins, members are plain`() {
        for (function in listOf("resourceGroup", "uniqueString", "empty", "length", "map", "sayHello")) {
            main.assertExactly(TokenStyle.BUILTIN, function, after = "\nfunc")
        }
        // A conversion function named like a type; a namespace.
        main.assertExactly(TokenStyle.BUILTIN, "string", after = "var count = ")
        main.assertPlain("az", after = "var group")
        main.assertExactly(TokenStyle.BUILTIN, "resourceGroup", after = "az.")
        // After a dot, `::` or `.?`, keywords and types are names of members.
        main.assertPlain("location", after = "resourceGroup().")
        main.assertPlain("type", after = "storage.t")
        main.assertPlain("properties.primaryEndpoints.blob")
        main.assertPlain("blobs", after = "storage::")
        main.assertPlain("name", after = "config.?")
        main.assertPlain("prefix", after = "var prefix")
    }

    @Test
    fun `interpolations are code between operators`() {
        main.assertExactly(TokenStyle.STRING, "'app")
        main.assertExactly(TokenStyle.OPERATOR, "\${", after = "'app")
        main.assertExactly(TokenStyle.BUILTIN, "uniqueString")
        main.assertExactly(TokenStyle.OPERATOR, "}", after = ".id)")
        main.assertExactly(TokenStyle.STRING, "'", after = ".id)}")
        main.assertExactly(TokenStyle.STRING, "'kv-")
        main.assertPlain("prefix", after = "'kv-")
    }

    @Test
    fun `interpolations nest, with strings and braces inside`() {
        val nested = bicep("var nested = 'a\${concat('b\${prefix}c', '}', {x: 1}.x)}d'\n")
        nested.assertExactly(TokenStyle.STRING, "'a")
        nested.assertExactly(TokenStyle.OPERATOR, "\${", after = "'a")
        nested.assertExactly(TokenStyle.BUILTIN, "concat")
        nested.assertExactly(TokenStyle.STRING, "'b")
        nested.assertExactly(TokenStyle.OPERATOR, "\${", after = "'b")
        nested.assertPlain("prefix")
        nested.assertExactly(TokenStyle.OPERATOR, "}", after = "prefix")
        nested.assertExactly(TokenStyle.STRING, "c'")
        nested.assertExactly(TokenStyle.STRING, "'}'")
        nested.assertExactly(TokenStyle.PROPERTY, "x", after = "{x")
        nested.assertExactly(TokenStyle.NUMBER, "1")
        nested.assertPlain("x", after = "}.")
        nested.assertExactly(TokenStyle.OPERATOR, "}", after = ".x)")
        nested.assertExactly(TokenStyle.STRING, "d'")
        nested.assertWellFormed()
    }

    @Test
    fun `escapes and multi-line strings have no interpolation`() {
        main.assertExactly(TokenStyle.STRING, "'it\\'s \\\${not} interpolated'")
        main.assertExactly(TokenStyle.STRING, "'''\nNo \${interpolation} here, it's 'raw'\n'''")
    }

    @Test
    fun `unterminated strings end with their line, multi-line ones with the text`() {
        val open = bicep("var a = 'x\${b\nvar c = 'd\nvar e = 1\nvar f = '''\nrest")
        open.assertExactly(TokenStyle.STRING, "'x")
        open.assertExactly(TokenStyle.KEYWORD, "var", after = "\n")
        open.assertExactly(TokenStyle.STRING, "'d")
        open.assertExactly(TokenStyle.NUMBER, "1")
        open.assertExactly(TokenStyle.STRING, "'''\nrest")
    }

    @Test
    fun `object keys are properties, plain and quoted`() {
        for (key in listOf("owner", "name", "location", "sku", "minimumTlsVersion", "parent", "params", "dependsOn")) {
            main.assertExactly(TokenStyle.PROPERTY, key, after = "  $key:")
        }
        main.assertExactly(TokenStyle.PROPERTY, "'cost-center'")
        // A key named like a keyword is a key.
        main.assertExactly(TokenStyle.PROPERTY, "type", after = "    type:")
        // Values are no keys, nor are the branches of a ternary.
        main.assertPlain("location", after = ": location")
        main.assertExactly(TokenStyle.NUMBER, "1", after = "'APP' ? ")
        val inline = bicep("var o = { a: 1, 'b': 2, c: x ? y : z }\nvar p = [x : y]\n")
        inline.assertExactly(TokenStyle.PROPERTY, "a", after = "{ ")
        inline.assertExactly(TokenStyle.PROPERTY, "'b'")
        inline.assertExactly(TokenStyle.PROPERTY, "c")
        inline.assertPlain("y")
        inline.assertPlain("x", after = "[")
        val decorated = bicep("type t = { @minValue(1) a: int, @secure() b: string, @sealed c: object }\n")
        decorated.assertExactly(TokenStyle.PROPERTY, "a", after = ") ")
        decorated.assertExactly(TokenStyle.PROPERTY, "b", after = "() ")
        decorated.assertExactly(TokenStyle.PROPERTY, "c", after = "@sealed ")
        decorated.assertExactly(TokenStyle.NUMBER, "1")
    }

    @Test
    fun `spreads are no member access`() {
        val spread = bicep("var t = { ...tags, ...null }\n")
        spread.assertPlain("...tags")
        spread.assertExactly(TokenStyle.KEYWORD, "null")
    }

    @Test
    fun `assertions, imports and extended parameter files`() {
        val more = bicep("import * as types from 'types.bicep'\nassert ok = true\nextends './base.bicepparam'\n")
        for (word in listOf("import", "as", "from", "assert", "true", "extends")) {
            more.assertExactly(TokenStyle.KEYWORD, word)
        }
        more.assertPlain("types", after = "as ")
    }

    @Test
    fun `numbers and operators`() {
        for (operator in listOf("!", "&&", "||", "=~", "??", ".?", "=>")) {
            main.assertExactly(TokenStyle.OPERATOR, operator)
        }
        val ternary = bicep("var t = a != b ? 1 : 0 == c?.d\n")
        ternary.assertExactly(TokenStyle.OPERATOR, "!=")
        ternary.assertExactly(TokenStyle.OPERATOR, "?")
        ternary.assertExactly(TokenStyle.OPERATOR, ":")
        ternary.assertExactly(TokenStyle.OPERATOR, "==")
        ternary.assertExactly(TokenStyle.OPERATOR, "?.")
        ternary.assertExactly(TokenStyle.NUMBER, "0")
        ternary.assertPlain("=")
        ternary.assertPlain("t")
    }

    @Test
    fun `parameter files`() {
        val params = bicep(PARAMS_BICEP)
        params.assertExactly(TokenStyle.KEYWORD, "using")
        params.assertExactly(TokenStyle.STRING, "'./main.bicep'")
        params.assertExactly(TokenStyle.KEYWORD, "param")
        params.assertPlain("location")
        params.assertExactly(TokenStyle.STRING, "'westeurope'")
        params.assertExactly(TokenStyle.BUILTIN, "readEnvironmentVariable")
        params.assertExactly(TokenStyle.COMMENT, "// Secrets come from the environment")
    }

    @Test
    fun `Markdown fences and notebooks know bicep`() {
        assertSame(BicepHighlighter, RSyntaxHighlighter.forLanguage("bicep"))
        assertSame(BicepHighlighter, Highlighters.forLanguage("bicep"))
        assertSame(BicepHighlighter, Highlighters.forLanguage("bicepparam"))
    }
}
