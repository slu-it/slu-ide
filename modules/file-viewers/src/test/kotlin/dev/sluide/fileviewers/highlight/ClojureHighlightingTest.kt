package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class ClojureHighlightingTest {
    private val clojure =
        Highlighted(
            """
            |(ns my.app
            |  (:require [clojure.string :as str]))
            |;; comment
            |(def ^:private x 42)
            |(defn greet
            |  "Docstring"
            |  [name]
            |  (let [m {:a 1 ::b 2 :c/d 3}]
            |    (println (str "Hello " name))
            |    (when-not (nil? x) true)))
            |(defn- helper [] nil)
            |{:name "x" :tags #{:a}}
            |
            """.trimMargin(),
            RSyntaxHighlighter.clojure,
        )

    @Test
    fun `definitions and special forms are keywords, nil too`() {
        for (keyword in listOf("ns", "def ", "defn ", "defn-", "let", "when-not", "true")) {
            assertEquals(setOf(TokenStyle.KEYWORD), clojure.stylesOf(keyword.trim()), keyword)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), clojure.stylesOf("nil", 1))
    }

    @Test
    fun `keywords with one or two colons are annotations`() {
        for (keyword in listOf(":require", ":as", ":private", "::b", ":c/d", ":name", ":tags")) {
            clojure.assertExactly(TokenStyle.ANNOTATION, keyword)
        }
    }

    @Test
    fun `a keyword in a set`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), clojure.stylesOf(":a}"))
    }

    @Test
    fun `println is a builtin`() {
        assertEquals(setOf(TokenStyle.BUILTIN), clojure.stylesOf("println"))
        assertEquals(setOf(TokenStyle.BUILTIN), clojure.stylesOf("str", 1))
    }

    @Test
    fun `comments and strings`() {
        assertEquals(setOf(TokenStyle.COMMENT), clojure.stylesOf(";; comment"))
        assertEquals(setOf(TokenStyle.STRING), clojure.stylesOf("\"Docstring\""))
        assertEquals(setOf(TokenStyle.NUMBER), clojure.stylesOf("42"))
    }

    @Test
    fun `spans are well-formed`() = clojure.assertWellFormed()
}
