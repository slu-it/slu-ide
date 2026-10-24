package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.TokenStyle
import dev.sluide.fileviewers.bicep.MAIN_BICEP
import dev.sluide.fileviewers.bicep.PARAMS_BICEP
import dev.sluide.fileviewers.highlight.Highlighted
import kotlin.test.Test
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
    private fun Highlighted.assertExactlyAfter(
        style: TokenStyle,
        snippet: String,
        after: String = "",
    ) = assertExactlyAt(style, find(snippet, after), snippet.length, "$snippet after $after")

    /** [snippet] (the first after [after]) has no style. */
    private fun Highlighted.assertPlainAfter(
        snippet: String,
        after: String = "",
    ) = assertPlainAt(find(snippet, after), snippet.length, "$snippet after $after")

    private val main = bicep(MAIN_BICEP)

    @Test
    fun `spans are sorted, disjoint and within the text`() {
        main.assertWellFormed()
        bicep(PARAMS_BICEP).assertWellFormed()
    }

    @Test
    fun `line and block comments`() {
        main.assertExactlyAfter(TokenStyle.COMMENT, "// Storage for the app")
        main.assertExactlyAfter(TokenStyle.COMMENT, "/* The storage account\n   and its container */")
        main.assertExactlyAfter(TokenStyle.COMMENT, "// resource fake 'x' = {}")
        bicep("/* open\ncomment").assertExactlyAfter(TokenStyle.COMMENT, "/* open\ncomment")
    }

    @Test
    fun `keywords`() {
        for (word in listOf("targetScope", "metadata", "import", "param", "type", "func", "var", "resource")) {
            main.assertExactlyAfter(TokenStyle.KEYWORD, word, after = "\n$word")
        }
        main.assertExactlyAfter(TokenStyle.KEYWORD, "false", after = "|| ")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "null", after = "?? ")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "for", after = "= [")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "in", after = "for name ")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "as", after = "tagSet ")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "existing")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "module", after = "\nmodule")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "if", after = "network.bicep'")
        main.assertExactlyAfter(TokenStyle.KEYWORD, "output", after = "\noutput")
    }

    @Test
    fun `built-in types and declared type names`() {
        main.assertExactlyAfter(TokenStyle.TYPE, "string", after = "param location")
        main.assertExactlyAfter(TokenStyle.TYPE, "array", after = "containerNames")
        main.assertExactlyAfter(TokenStyle.TYPE, "object", after = "param config")
        main.assertExactlyAfter(TokenStyle.TYPE, "int", after = "'cost-center'")
        main.assertExactlyAfter(TokenStyle.TYPE, "skuName")
        main.assertExactlyAfter(TokenStyle.TYPE, "tagSet", after = "type ")
    }

    @Test
    fun `decorators`() {
        for (decorator in listOf("@description", "@secure", "@minLength", "@sys.description", "@batchSize")) {
            main.assertExactlyAfter(TokenStyle.ANNOTATION, decorator)
        }
        main.assertExactlyAfter(TokenStyle.NUMBER, "12", after = "@minLength")
    }

    @Test
    fun `functions are builtins, members are plain`() {
        for (function in listOf("resourceGroup", "uniqueString", "empty", "length", "map", "sayHello")) {
            main.assertExactlyAfter(TokenStyle.BUILTIN, function, after = "\nfunc")
        }
        // A conversion function named like a type; a namespace.
        main.assertExactlyAfter(TokenStyle.BUILTIN, "string", after = "var count = ")
        main.assertPlainAfter("az", after = "var group")
        main.assertExactlyAfter(TokenStyle.BUILTIN, "resourceGroup", after = "az.")
        // After a dot, `::` or `.?`, keywords and types are names of members.
        main.assertPlainAfter("location", after = "resourceGroup().")
        main.assertPlainAfter("type", after = "storage.t")
        main.assertPlainAfter("properties.primaryEndpoints.blob")
        main.assertPlainAfter("blobs", after = "storage::")
        main.assertPlainAfter("name", after = "config.?")
        main.assertPlainAfter("prefix", after = "var prefix")
    }

    @Test
    fun `interpolations are code between operators`() {
        main.assertExactlyAfter(TokenStyle.STRING, "'app")
        main.assertExactlyAfter(TokenStyle.OPERATOR, "\${", after = "'app")
        main.assertExactlyAfter(TokenStyle.BUILTIN, "uniqueString")
        main.assertExactlyAfter(TokenStyle.OPERATOR, "}", after = ".id)")
        main.assertExactlyAfter(TokenStyle.STRING, "'", after = ".id)}")
        main.assertExactlyAfter(TokenStyle.STRING, "'kv-")
        main.assertPlainAfter("prefix", after = "'kv-")
    }

    @Test
    fun `interpolations nest, with strings and braces inside`() {
        val nested = bicep("var nested = 'a\${concat('b\${prefix}c', '}', {x: 1}.x)}d'\n")
        nested.assertExactlyAfter(TokenStyle.STRING, "'a")
        nested.assertExactlyAfter(TokenStyle.OPERATOR, "\${", after = "'a")
        nested.assertExactlyAfter(TokenStyle.BUILTIN, "concat")
        nested.assertExactlyAfter(TokenStyle.STRING, "'b")
        nested.assertExactlyAfter(TokenStyle.OPERATOR, "\${", after = "'b")
        nested.assertPlainAfter("prefix")
        nested.assertExactlyAfter(TokenStyle.OPERATOR, "}", after = "prefix")
        nested.assertExactlyAfter(TokenStyle.STRING, "c'")
        nested.assertExactlyAfter(TokenStyle.STRING, "'}'")
        nested.assertExactlyAfter(TokenStyle.PROPERTY, "x", after = "{x")
        nested.assertExactlyAfter(TokenStyle.NUMBER, "1")
        nested.assertPlainAfter("x", after = "}.")
        nested.assertExactlyAfter(TokenStyle.OPERATOR, "}", after = ".x)")
        nested.assertExactlyAfter(TokenStyle.STRING, "d'")
        nested.assertWellFormed()
    }

    @Test
    fun `escapes and multi-line strings have no interpolation`() {
        main.assertExactlyAfter(TokenStyle.STRING, "'it\\'s \\\${not} interpolated'")
        main.assertExactlyAfter(TokenStyle.STRING, "'''\nNo \${interpolation} here, it's 'raw'\n'''")
    }

    @Test
    fun `unterminated strings end with their line, multi-line ones with the text`() {
        val open = bicep("var a = 'x\${b\nvar c = 'd\nvar e = 1\nvar f = '''\nrest")
        open.assertExactlyAfter(TokenStyle.STRING, "'x")
        open.assertExactlyAfter(TokenStyle.KEYWORD, "var", after = "\n")
        open.assertExactlyAfter(TokenStyle.STRING, "'d")
        open.assertExactlyAfter(TokenStyle.NUMBER, "1")
        open.assertExactlyAfter(TokenStyle.STRING, "'''\nrest")
    }

    @Test
    fun `object keys are properties, plain and quoted`() {
        for (key in listOf("owner", "name", "location", "sku", "minimumTlsVersion", "parent", "params", "dependsOn")) {
            main.assertExactlyAfter(TokenStyle.PROPERTY, key, after = "  $key:")
        }
        main.assertExactlyAfter(TokenStyle.PROPERTY, "'cost-center'")
        // A key named like a keyword is a key.
        main.assertExactlyAfter(TokenStyle.PROPERTY, "type", after = "    type:")
        // Values are no keys, nor are the branches of a ternary.
        main.assertPlainAfter("location", after = ": location")
        main.assertExactlyAfter(TokenStyle.NUMBER, "1", after = "'APP' ? ")
        val inline = bicep("var o = { a: 1, 'b': 2, c: x ? y : z }\nvar p = [x : y]\n")
        inline.assertExactlyAfter(TokenStyle.PROPERTY, "a", after = "{ ")
        inline.assertExactlyAfter(TokenStyle.PROPERTY, "'b'")
        inline.assertExactlyAfter(TokenStyle.PROPERTY, "c")
        inline.assertPlainAfter("y")
        inline.assertPlainAfter("x", after = "[")
        val decorated = bicep("type t = { @minValue(1) a: int, @secure() b: string, @sealed c: object }\n")
        decorated.assertExactlyAfter(TokenStyle.PROPERTY, "a", after = ") ")
        decorated.assertExactlyAfter(TokenStyle.PROPERTY, "b", after = "() ")
        decorated.assertExactlyAfter(TokenStyle.PROPERTY, "c", after = "@sealed ")
        decorated.assertExactlyAfter(TokenStyle.NUMBER, "1")
    }

    @Test
    fun `spreads are no member access`() {
        val spread = bicep("var t = { ...tags, ...null }\n")
        spread.assertPlainAfter("...tags")
        spread.assertExactlyAfter(TokenStyle.KEYWORD, "null")
    }

    @Test
    fun `assertions, imports and extended parameter files`() {
        val more = bicep("import * as types from 'types.bicep'\nassert ok = true\nextends './base.bicepparam'\n")
        for (word in listOf("import", "as", "from", "assert", "true", "extends")) {
            more.assertExactlyAfter(TokenStyle.KEYWORD, word)
        }
        more.assertPlainAfter("types", after = "as ")
    }

    @Test
    fun `numbers and operators`() {
        for (operator in listOf("!", "&&", "||", "=~", "??", ".?", "=>")) {
            main.assertExactlyAfter(TokenStyle.OPERATOR, operator)
        }
        val ternary = bicep("var t = a != b ? 1 : 0 == c?.d\n")
        ternary.assertExactlyAfter(TokenStyle.OPERATOR, "!=")
        ternary.assertExactlyAfter(TokenStyle.OPERATOR, "?")
        ternary.assertExactlyAfter(TokenStyle.OPERATOR, ":")
        ternary.assertExactlyAfter(TokenStyle.OPERATOR, "==")
        ternary.assertExactlyAfter(TokenStyle.OPERATOR, "?.")
        ternary.assertExactlyAfter(TokenStyle.NUMBER, "0")
        ternary.assertPlainAfter("=")
        ternary.assertPlainAfter("t")
    }

    @Test
    fun `parameter files`() {
        val params = bicep(PARAMS_BICEP)
        params.assertExactlyAfter(TokenStyle.KEYWORD, "using")
        params.assertExactlyAfter(TokenStyle.STRING, "'./main.bicep'")
        params.assertExactlyAfter(TokenStyle.KEYWORD, "param")
        params.assertPlainAfter("location")
        params.assertExactlyAfter(TokenStyle.STRING, "'westeurope'")
        params.assertExactlyAfter(TokenStyle.BUILTIN, "readEnvironmentVariable")
        params.assertExactlyAfter(TokenStyle.COMMENT, "// Secrets come from the environment")
    }

    @Test
    fun `Markdown fences and notebooks know bicep`() {
        assertSame(BicepHighlighter, Highlighters.forLanguage("bicep"))
        assertSame(BicepHighlighter, Highlighters.forLanguage("bicepparam"))
    }
}
