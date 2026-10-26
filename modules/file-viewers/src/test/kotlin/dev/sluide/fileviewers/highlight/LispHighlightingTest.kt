package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class LispHighlightingTest {
    private val lisp =
        Highlighted(
            """
            |#lang racket
            |;;; comment
            |(defun fact (n)
            |  "Factorial."
            |  (if (<= n 1) 1 (* n (fact (- n 1)))))
            |(define (f x) (lambda (y) (+ x y)))
            |(let ((x 5)) (format t "~a~%" x) #t #f)
            |(setq foo :keyword)
            |#| block
            |comment |#
            |(cond ((null lst) nil) (else (car lst)))
            |
            """.trimMargin(),
            RSyntaxHighlighter.lisp,
        )

    @Test
    fun `special forms and definitions are keywords`() {
        for (keyword in listOf("defun", "if", "define", "lambda", "let", "setq", "else")) {
            assertEquals(setOf(TokenStyle.KEYWORD), lisp.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), lisp.stylesOf("car"))
    }

    @Test
    fun `booleans and lang line of Racket`() {
        for (keyword in listOf("#lang", "#t", "#f")) {
            lisp.assertExactly(TokenStyle.KEYWORD, keyword)
        }
    }

    @Test
    fun `keyword arguments are annotations`() {
        lisp.assertExactly(TokenStyle.ANNOTATION, ":keyword")
    }

    @Test
    fun `comments, block comments and strings`() {
        assertEquals(setOf(TokenStyle.COMMENT), lisp.stylesOf(";;; comment"))
        assertEquals(setOf(TokenStyle.COMMENT), lisp.stylesOf("#| block"))
        assertEquals(setOf(TokenStyle.COMMENT), lisp.stylesOf("comment |#"))
        assertEquals(setOf(TokenStyle.STRING), lisp.stylesOf("\"Factorial.\""))
    }

    @Test
    fun `spans are well-formed`() = lisp.assertWellFormed()
}
