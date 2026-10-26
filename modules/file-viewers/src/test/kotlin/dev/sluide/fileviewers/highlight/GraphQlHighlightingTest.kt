package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertFalse

class GraphQlHighlightingTest {
    private fun gql(text: String) = Highlighted(text, GraphQlHighlighter)

    private fun Highlighted.word(
        style: TokenStyle,
        snippet: String,
        occurrence: Int = 0,
    ) = assertExactly(style, snippet, occurrence, wholeWord = true)

    private fun Highlighted.plain(
        snippet: String,
        occurrence: Int = 0,
    ) = assertPlain(snippet, occurrence, wholeWord = true)

    private val schema =
        gql(
            """
            |# Header
            |${"\"\"\""}A user.
            |Second line${"\"\"\""}
            |type User implements Node & Entity @key(fields: "id") {
            |  "The id"
            |  id: ID!
            |  posts(first: Int = 10, tags: [String!]! = ["a"]): [[Post!]!]! @deprecated(reason: "old")
            |  type: String
            |  on: Boolean
            |}
            |union Result = User | Post
            |enum Role { ADMIN USER @deprecated }
            |input Filter { query: String = "x", input: Float = 1.5e3, flag: Boolean = true, o: In = {a: null} }
            |scalar Date
            |directive @key(fields: String!) repeatable on FIELD_DEFINITION | OBJECT
            |extend type Query { me: User }
            |schema { query: Query }
            |
            """.trimMargin(),
        )

    private val operation =
        gql(
            """
            |query Posts(${'$'}id: ID!, ${'$'}n: [Int!] = [1, -2]) @live {
            |  me: user(id: ${'$'}id, role: ADMIN, ok: false) {
            |    ...Fields
            |    ... on User @include(if: ${'$'}x) { type query input on }
            |    ... @skip(if: true) { name }
            |  }
            |}
            |fragment Fields on User { id }
            |{ anonymous }
            |
            """.trimMargin(),
        )

    @Test
    fun `spans are sorted and disjoint`() {
        schema.assertWellFormed()
        operation.assertWellFormed()
    }

    @Test
    fun `comments strings and block strings`() {
        schema.word(TokenStyle.COMMENT, "# Header")
        schema.assertExactly(TokenStyle.STRING, "\"\"\"A user.\nSecond line\"\"\"")
        schema.assertExactly(TokenStyle.STRING, "\"id\"")
        schema.assertExactly(TokenStyle.STRING, "\"old\"")
    }

    @Test
    fun `definition keywords and heads`() {
        for (keyword in listOf("type", "union", "enum", "input", "scalar", "directive", "extend", "schema")) {
            schema.assertExactly(TokenStyle.KEYWORD, keyword, 0, wholeWord = true)
        }
        schema.word(TokenStyle.KEYWORD, "implements")
        schema.word(TokenStyle.KEYWORD, "repeatable")
        schema.word(TokenStyle.KEYWORD, "on", 1)
        operation.word(TokenStyle.KEYWORD, "query")
        operation.word(TokenStyle.KEYWORD, "fragment")
        operation.word(TokenStyle.KEYWORD, "on", 0)
        operation.word(TokenStyle.KEYWORD, "on", 2)
        operation.word(TokenStyle.PROPERTY, "anonymous")
        operation.plain("Posts")
        operation.plain("Fields", 0)
    }

    @Test
    fun `fields named like keywords stay properties`() {
        schema.word(TokenStyle.PROPERTY, "type", 1)
        schema.word(TokenStyle.PROPERTY, "on", 0)
        schema.word(TokenStyle.PROPERTY, "query", 1)
        schema.word(TokenStyle.PROPERTY, "input", 1)
        operation.word(TokenStyle.PROPERTY, "type")
        operation.word(TokenStyle.PROPERTY, "query", 1)
        operation.word(TokenStyle.PROPERTY, "input")
        operation.word(TokenStyle.PROPERTY, "on", 1)
    }

    @Test
    fun `types in definitions and variables`() {
        schema.word(TokenStyle.TYPE, "User", 0)
        schema.word(TokenStyle.TYPE, "Node")
        schema.word(TokenStyle.TYPE, "Entity")
        schema.word(TokenStyle.TYPE, "ID")
        schema.word(TokenStyle.TYPE, "Int")
        schema.word(TokenStyle.TYPE, "Post", 0)
        schema.word(TokenStyle.TYPE, "Result")
        schema.word(TokenStyle.TYPE, "Role")
        schema.word(TokenStyle.TYPE, "Date")
        schema.word(TokenStyle.TYPE, "Float")
        schema.word(TokenStyle.TYPE, "FIELD_DEFINITION")
        schema.word(TokenStyle.TYPE, "OBJECT")
        schema.word(TokenStyle.TYPE, "Query", 0)
        schema.word(TokenStyle.TYPE, "Query", 1)
        operation.word(TokenStyle.TYPE, "ID")
        operation.word(TokenStyle.TYPE, "User", 0)
        operation.word(TokenStyle.TYPE, "User", 1)
    }

    @Test
    fun `nested list and non-null types`() {
        val text = gql("type A { f: [[Post!]!]! }")
        text.word(TokenStyle.TYPE, "Post")
        text.assertExactly(TokenStyle.OPERATOR, "!", 0)
        text.assertExactly(TokenStyle.OPERATOR, "!", 2)
        text.word(TokenStyle.PROPERTY, "f")
    }

    @Test
    fun `argument and field names, values and enum values`() {
        schema.word(TokenStyle.PROPERTY, "first")
        schema.word(TokenStyle.PROPERTY, "tags")
        schema.word(TokenStyle.PROPERTY, "fields")
        schema.word(TokenStyle.PROPERTY, "reason")
        schema.word(TokenStyle.PROPERTY, "posts")
        schema.word(TokenStyle.KEYWORD, "true")
        schema.word(TokenStyle.KEYWORD, "null")
        schema.assertExactly(TokenStyle.NUMBER, "10")
        schema.assertExactly(TokenStyle.NUMBER, "1.5e3")
        schema.plain("ADMIN")
        operation.assertExactly(TokenStyle.NUMBER, "-2")
        operation.word(TokenStyle.PROPERTY, "me")
        operation.word(TokenStyle.PROPERTY, "user")
        operation.word(TokenStyle.PROPERTY, "role")
        operation.plain("ADMIN")
        operation.word(TokenStyle.KEYWORD, "false")
        operation.word(TokenStyle.KEYWORD, "true")
    }

    @Test
    fun `literals inside lists and objects are keywords`() {
        val text = gql("{ f(a: [true, false, null], o: {x: [null]}) }")
        for ((word, occurrence) in listOf("true" to 0, "false" to 0, "null" to 0, "null" to 1)) {
            text.word(TokenStyle.KEYWORD, word, occurrence)
        }
        text.word(TokenStyle.PROPERTY, "x")
    }

    @Test
    fun `definitions after a closing brace on the same line`() {
        val text = gql("type A { a: Int } type B { b: Int } query { type }")
        text.word(TokenStyle.KEYWORD, "type", 1)
        text.word(TokenStyle.TYPE, "B")
        text.word(TokenStyle.PROPERTY, "b")
        text.word(TokenStyle.KEYWORD, "query")
        text.word(TokenStyle.PROPERTY, "type", 2)
    }

    @Test
    fun `variables directives and operators`() {
        operation.assertExactly(TokenStyle.ANNOTATION, "\$id", 0)
        operation.assertExactly(TokenStyle.ANNOTATION, "\$x")
        operation.assertExactly(TokenStyle.ANNOTATION, "@include")
        operation.assertExactly(TokenStyle.ANNOTATION, "@live")
        schema.assertExactly(TokenStyle.ANNOTATION, "@key", 0)
        schema.assertExactly(TokenStyle.ANNOTATION, "@deprecated", 0)
        operation.assertExactly(TokenStyle.OPERATOR, "...", 0)
        schema.assertExactly(TokenStyle.OPERATOR, "&")
        schema.assertExactly(TokenStyle.OPERATOR, "|", 1)
        schema.assertExactly(TokenStyle.OPERATOR, "=", 0)
    }

    @Test
    fun `an unclosed brace does not restyle the next definition`() {
        val text = gql("query Q {\n  user {\n    name\n\ntype X {\n  a: Int\n  type: String\n}\n")
        text.word(TokenStyle.KEYWORD, "type", 0)
        text.word(TokenStyle.TYPE, "X")
        text.word(TokenStyle.PROPERTY, "a")
        text.word(TokenStyle.TYPE, "Int")
        text.word(TokenStyle.PROPERTY, "type", 1)
        text.word(TokenStyle.TYPE, "String")
        text.word(TokenStyle.PROPERTY, "name")
    }

    @Test
    fun `a description between fields does not make the next field a type`() {
        for (keyword in listOf("type", "input")) {
            val text = gql("$keyword A {\n  a: Int\n  \"d\"\n  b(x: Int): String\n  \"\"\"e\"\"\"\n  c: Int\n}")
            text.word(TokenStyle.PROPERTY, "b")
            text.word(TokenStyle.PROPERTY, "x")
            text.word(TokenStyle.PROPERTY, "c")
            text.word(TokenStyle.TYPE, "String")
        }
    }

    @Test
    fun `stray closing brackets are ignored`() {
        val text = gql("type A {\n  f(a: Int)): String\n  g: Int\n}\n} type B { h: Int }")
        text.word(TokenStyle.PROPERTY, "g")
        text.word(TokenStyle.TYPE, "Int", 1)
        text.word(TokenStyle.TYPE, "Int", 2)
        text.word(TokenStyle.TYPE, "B")
        text.word(TokenStyle.PROPERTY, "h")
        val open = gql("query Q(\$a: Int } type B { b: Int }")
        assertFalse(TokenStyle.KEYWORD in open.stylesOf("type"), "a } without a brace leaves the parenthesis open")
        val list = gql("type A {\n  f: Int]\n  g: Int\n}")
        list.word(TokenStyle.PROPERTY, "g")
        list.word(TokenStyle.TYPE, "Int", 1)
    }

    @Test
    fun `an unclosed parenthesis in a type is reset by the next definition too`() {
        val text = gql("type A {\n  f(a: [Int: \ninterface B { g: ID }")
        text.word(TokenStyle.KEYWORD, "interface")
        text.word(TokenStyle.TYPE, "B")
        text.word(TokenStyle.PROPERTY, "g")
        text.word(TokenStyle.TYPE, "ID")
    }

    @Test
    fun `unterminated strings end with their line, block strings with the text`() {
        val text = gql("type A { f(a: \"open\n  b: Int }\n\"\"\"never\nclosed")
        text.assertExactly(TokenStyle.STRING, "\"open")
        text.word(TokenStyle.TYPE, "Int")
        text.assertExactly(TokenStyle.STRING, "\"\"\"never\nclosed")
        gql("a(b: \"x\\\"y\")").assertExactly(TokenStyle.STRING, "\"x\\\"y\"")
        gql("\"\"\"a \\\"\"\" b\"\"\" type").assertExactly(TokenStyle.STRING, "\"\"\"a \\\"\"\" b\"\"\"")
    }
}
