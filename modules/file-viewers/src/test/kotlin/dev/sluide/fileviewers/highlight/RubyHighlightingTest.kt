package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class RubyHighlightingTest {
    private val d = "$"

    private val ruby =
        Highlighted(
            """
            |#!/usr/bin/env ruby
            |# frozen_string_literal: true
            |class Person < Struct.new(:name)
            |  attr_reader :age
            |  DEFAULTS = { name: "Bob", city: 'x', :zip => 1 }.freeze
            |  def initialize(name, age: 3, **opts)
            |    @name = name
            |    @@count ||= nil
            |    ${d}stdout.puts "hello #{name}"
            |    words = %w[a b c]
            |    syms = %i[x y z]
            |    x = ok ? :yes : :no
            |    items.each(&:to_s)
            |    Foo::Bar.baz
            |  end
            |end
            |=begin
            |block
            |=end
            |
            """.trimMargin(),
            RSyntaxHighlighter.ruby,
        )

    @Test
    fun `keywords and builtins`() {
        for (keyword in listOf("class", "def", "end", "nil")) {
            assertEquals(setOf(TokenStyle.KEYWORD), ruby.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), ruby.stylesOf("attr_reader"))
    }

    @Test
    fun `symbols and variables are annotations`() {
        for (annotation in listOf(":age", ":zip", ":yes", ":no", ":to_s", "@name", "@@count", "${d}stdout")) {
            assertEquals(setOf(TokenStyle.ANNOTATION), ruby.stylesOf(annotation), annotation)
        }
        ruby.assertExactly(TokenStyle.ANNOTATION, ":name")
    }

    @Test
    fun `hash keys and keyword arguments are properties`() {
        ruby.assertExactly(TokenStyle.PROPERTY, "name", occurrence = 1)
        ruby.assertExactly(TokenStyle.PROPERTY, "city")
        ruby.assertExactly(TokenStyle.PROPERTY, "age", occurrence = 1)
        // A scope and a comment are no keys.
        assertEquals(setOf(TokenStyle.OPERATOR), ruby.stylesOf("::"))
        assertEquals(setOf(TokenStyle.COMMENT), ruby.stylesOf("# frozen_string_literal: true"))
    }

    @Test
    fun `percent literals are strings`() {
        ruby.assertExactly(TokenStyle.STRING, "%w[a b c]")
        ruby.assertExactly(TokenStyle.STRING, "%i[x y z]")
        assertEquals(setOf(TokenStyle.STRING), ruby.stylesOf("\"hello #{name}\""))
    }

    @Test
    fun `a modulo is no percent literal`() {
        val text = Highlighted("a = d%i[1]\ne = f % i[0]\n", RSyntaxHighlighter.ruby)
        assertEquals(setOf(TokenStyle.OPERATOR), text.stylesOf("%", 0))
        assertEquals(setOf(TokenStyle.OPERATOR), text.stylesOf("%", 1))
    }

    @Test
    fun `comments and shebang`() {
        assertEquals(setOf(TokenStyle.COMMENT), ruby.stylesOf("#!/usr/bin/env ruby"))
        assertEquals(setOf(TokenStyle.COMMENT), ruby.stylesOf("block"))
    }

    @Test
    fun `spans are well-formed`() = ruby.assertWellFormed()
}
