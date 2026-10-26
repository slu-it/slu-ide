package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class FortranHighlightingTest {
    private val fortran =
        Highlighted(
            """
            |program demo
            |  implicit none
            |  integer, intent(in) :: n
            |  real, intent(inout), target :: t
            |  block_size = 3 + target + in
            |  block
            |  end block
            |  blk: block
            |  end block blk
            |  real(8) :: x = 1.5d0, y = 2.0_dp
            |  ! comment
            |  character(len=*), parameter :: s = "hi"
            |  do i = 1, 10
            |    if (a .and. .not. b) print *, i
            |  end do
            |end program demo
            |PROGRAM Shout
            |  INTEGER, POINTER :: p
            |END PROGRAM
            |
            """.trimMargin(),
            RSyntaxHighlighter.fortran,
        )

    @Test
    fun `keywords in any case`() {
        for (word in listOf("program", "implicit", "integer", "do", "end", "PROGRAM", "INTEGER", "END")) {
            fortran.assertExactly(TokenStyle.KEYWORD, word, wholeWord = true)
        }
        fortran.assertExactly(TokenStyle.KEYWORD, "intent")
        fortran.assertExactly(TokenStyle.KEYWORD, "in", wholeWord = true)
        fortran.assertExactly(TokenStyle.KEYWORD, "inout", wholeWord = true)
        fortran.assertExactly(TokenStyle.KEYWORD, "target", occurrence = 0, wholeWord = true)
        fortran.assertExactly(TokenStyle.KEYWORD, "block", occurrence = 0, wholeWord = true)
        fortran.assertExactly(TokenStyle.KEYWORD, "block", occurrence = 1, wholeWord = true)
        fortran.assertExactly(TokenStyle.KEYWORD, "block", occurrence = 2, wholeWord = true) // named
        fortran.assertExactly(TokenStyle.KEYWORD, "block", occurrence = 3, wholeWord = true) // end block name
        fortran.assertPlain("target", occurrence = 1, wholeWord = true)
        fortran.assertPlain("in", occurrence = 1, wholeWord = true)
        assertEquals(emptySet(), fortran.stylesOf("block_size"), "a longer name")
        fortran.assertExactly(TokenStyle.KEYWORD, "POINTER")
    }

    @Test
    fun `comments, strings and numbers`() {
        fortran.assertExactly(TokenStyle.COMMENT, "! comment")
        fortran.assertExactly(TokenStyle.STRING, "\"hi\"")
        for (number in listOf("1.5d0", "2.0_dp", "10")) {
            fortran.assertExactly(TokenStyle.NUMBER, number, wholeWord = true)
        }
        assertEquals(setOf(TokenStyle.OPERATOR), fortran.stylesOf(".and."))
        assertEquals(setOf(TokenStyle.OPERATOR), fortran.stylesOf(".not."))
    }
}
