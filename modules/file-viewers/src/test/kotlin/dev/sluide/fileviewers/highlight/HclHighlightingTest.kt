package dev.sluide.fileviewers.highlight

import kotlin.test.Test
import kotlin.test.assertEquals

class HclHighlightingTest {
    /** The spans as `STYLE:text`, in order. */
    private fun spans(text: String): List<String> {
        val highlighted = Highlighted(text, HclHighlighter)
        highlighted.assertWellFormed()
        return highlighted.spans.map { "${it.style}:${text.substring(it.start, it.end)}" }
    }

    private fun check(
        text: String,
        vararg expected: String,
    ) = assertEquals(expected.toList(), spans(text))

    @Test
    fun `comments of all three kinds`() {
        check(
            "# a\n// b\n/* c\nd */ x = 1",
            "COMMENT:# a",
            "COMMENT:// b",
            "COMMENT:/* c\nd */",
            "PROPERTY:x",
            "NUMBER:1",
        )
        check("/* open\ncomment", "COMMENT:/* open\ncomment")
    }

    @Test
    fun `block types are keywords and their labels types`() {
        check(
            "resource \"aws_s3_bucket\" \"b\" {\n  lifecycle {\n    prevent_destroy = true\n  }\n}\n",
            "KEYWORD:resource",
            "TYPE:\"aws_s3_bucket\"",
            "TYPE:\"b\"",
            "KEYWORD:lifecycle",
            "PROPERTY:prevent_destroy",
            "KEYWORD:true",
        )
        check("terraform {\n  required_providers {\n  }\n}", "KEYWORD:terraform", "KEYWORD:required_providers")
        check("provider aws {}", "KEYWORD:provider", "TYPE:aws")
    }

    @Test
    fun `attributes and object keys are properties`() {
        check(
            "x = { a = 1, \"b\" = 2, c: 3 }\ny == 1",
            "PROPERTY:x",
            "PROPERTY:a",
            "NUMBER:1",
            "PROPERTY:\"b\"",
            "NUMBER:2",
            "PROPERTY:c",
            "OPERATOR::",
            "NUMBER:3",
            "OPERATOR:==",
            "NUMBER:1",
        )
        check("a-b = 1", "PROPERTY:a-b", "NUMBER:1")
    }

    @Test
    fun `strings with escapes, interpolation and directives`() {
        check("x = \"a\\\"b\"", "PROPERTY:x", "STRING:\"a\\\"b\"")
        check(
            "x = \"a\${var.b}c\"",
            "PROPERTY:x",
            "STRING:\"a",
            "OPERATOR:\${",
            "BUILTIN:var",
            "OPERATOR:}",
            "STRING:c\"",
        )
        check(
            "x = \"\${~ f(\"i\${1}\") ~}\"",
            "PROPERTY:x",
            "STRING:\"",
            "OPERATOR:\${~",
            "BUILTIN:f",
            "STRING:\"i",
            "OPERATOR:\${",
            "NUMBER:1",
            "OPERATOR:}",
            "STRING:\"",
            "OPERATOR:~}",
            "STRING:\"",
        )
        check(
            "x = \"%{ for a in b }\${a}%{ endfor }\"",
            "PROPERTY:x",
            "STRING:\"",
            "OPERATOR:%{",
            "KEYWORD:for",
            "KEYWORD:in",
            "OPERATOR:}",
            "OPERATOR:\${",
            "OPERATOR:}",
            "OPERATOR:%{",
            "KEYWORD:endfor",
            "OPERATOR:}",
            "STRING:\"",
        )
        check(
            "x = \"\${ {a=1}.a }\"",
            "PROPERTY:x",
            "STRING:\"",
            "OPERATOR:\${",
            "PROPERTY:a",
            "NUMBER:1",
            "OPERATOR:}",
            "STRING:\"",
        )
    }

    @Test
    fun `doubled dollar and percent are escapes`() {
        check("x = \"\$\${a} %%{b}\"", "PROPERTY:x", "STRING:\"\$\${a} %%{b}\"")
    }

    @Test
    fun `heredocs, also indented with interpolation`() {
        check(
            "x = <<-EOF\n  a \${b}\n  \${\n  c}\n    EOF\ny = 1",
            "PROPERTY:x",
            "STRING:<<-EOF\n  a ",
            "OPERATOR:\${",
            "OPERATOR:}",
            "STRING:\n  ",
            "OPERATOR:\${",
            "OPERATOR:}",
            "STRING:\n    EOF",
            "PROPERTY:y",
            "NUMBER:1",
        )
        check(
            "x = <<EOF\nresource \"a\" {\nEOF\nz = 2",
            "PROPERTY:x",
            "STRING:<<EOF\nresource \"a\" {\nEOF",
            "PROPERTY:z",
            "NUMBER:2",
        )
        check("x = <<EOF\nnever closed\ny = 1", "PROPERTY:x", "STRING:<<EOF\nnever closed\ny = 1")
        check("x = <<EOF", "PROPERTY:x", "STRING:<<EOF")
    }

    @Test
    fun `numbers, literals, calls and reference roots`() {
        check(
            "x = [1, 2.5e3, 1e-2, null, false, max(a), var.b, local.c, module.d, each.key, path.module]",
            "PROPERTY:x",
            "NUMBER:1",
            "NUMBER:2.5e3",
            "NUMBER:1e-2",
            "KEYWORD:null",
            "KEYWORD:false",
            "BUILTIN:max",
            "BUILTIN:var",
            "BUILTIN:local",
            "BUILTIN:module",
            "BUILTIN:each",
            "BUILTIN:path",
        )
        check("x = provider::aws::arn_parse(a)", "PROPERTY:x", "BUILTIN:provider::aws::arn_parse")
    }

    @Test
    fun `for expressions and conditionals`() {
        check(
            "x = [for k, v in m : v if k != null]",
            "PROPERTY:x",
            "KEYWORD:for",
            "KEYWORD:in",
            "OPERATOR::",
            "KEYWORD:if",
            "OPERATOR:!=",
            "KEYWORD:null",
        )
        check(
            "x = { for k, v in m : k => v... }",
            "PROPERTY:x",
            "KEYWORD:for",
            "KEYWORD:in",
            "OPERATOR::",
            "OPERATOR:=>",
            "OPERATOR:...",
        )
        check(
            "x = a >= 1 && !b ? c : d",
            "PROPERTY:x",
            "OPERATOR:>=",
            "NUMBER:1",
            "OPERATOR:&&",
            "OPERATOR:!",
            "OPERATOR:?",
            "OPERATOR::",
        )
        check("x = { a = b ? c : d }", "PROPERTY:x", "PROPERTY:a", "OPERATOR:?", "OPERATOR::")
    }

    @Test
    fun `words after a dot are members, never keywords`() {
        check("x = var.for", "PROPERTY:x", "BUILTIN:var")
        check("x = [a.resource, b.in, c.0.null, d.*.if]", "PROPERTY:x", "NUMBER:0", "OPERATOR:*")
    }

    @Test
    fun `an unclosed paren does not swallow the next top-level block`() {
        check(
            "a = f(\nresource \"x\" \"y\" {\n  b = 1\n}\nz = 3",
            "PROPERTY:a",
            "BUILTIN:f",
            "KEYWORD:resource",
            "TYPE:\"x\"",
            "TYPE:\"y\"",
            "PROPERTY:b",
            "NUMBER:1",
            "PROPERTY:z",
            "NUMBER:3",
        )
        check("a = [1,\nb = 2", "PROPERTY:a", "NUMBER:1", "PROPERTY:b", "NUMBER:2")
        check("x = \"\${a\ny = 1", "PROPERTY:x", "STRING:\"", "OPERATOR:\${", "PROPERTY:y", "NUMBER:1")
        check(
            "resource \"a\" \"b\" {\n  c = [\n  lifecycle {\n    d = 1\n  }\n  \"e\" = 2\n}",
            "KEYWORD:resource",
            "TYPE:\"a\"",
            "TYPE:\"b\"",
            "PROPERTY:c",
            "KEYWORD:lifecycle",
            "PROPERTY:d",
            "NUMBER:1",
            "PROPERTY:\"e\"",
            "NUMBER:2",
        )
        check(
            "a = \"\${f(\nlocals {\n  b = 1\n}",
            "PROPERTY:a",
            "STRING:\"",
            "OPERATOR:\${",
            "BUILTIN:f",
            "KEYWORD:locals",
            "PROPERTY:b",
            "NUMBER:1",
        )
    }

    @Test
    fun `an unclosed block does not restyle the next block`() {
        check(
            "a \"b\" {\n  c = {\nd \"e\" {\n  f = 1\n}",
            "KEYWORD:a",
            "TYPE:\"b\"",
            "PROPERTY:c",
            "KEYWORD:d",
            "TYPE:\"e\"",
            "PROPERTY:f",
            "NUMBER:1",
        )
    }

    @Test
    fun `object keys with a colon on their own lines`() {
        check(
            "x = {\n  a: 1\n  b: c ? d : e\n}",
            "PROPERTY:x",
            "PROPERTY:a",
            "OPERATOR::",
            "NUMBER:1",
            "PROPERTY:b",
            "OPERATOR::",
            "OPERATOR:?",
            "OPERATOR::",
        )
    }

    @Test
    fun `unterminated strings end with their line`() {
        check("a = \"open\nb = 1", "PROPERTY:a", "STRING:\"open", "PROPERTY:b", "NUMBER:1")
    }
}
