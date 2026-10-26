package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class AssemblerHighlightingTest {
    private val asm =
        Highlighted(
            """
            |section .text
            |global _start
            |_start:
            |    mov eax, 1      ; comment
            |    mov RBX, 0x1F
            |    add ecx, 0FFh
            |.loop:
            |    msg db "hi", 0
            |    lea r12d, [rsp + 8]
            |    syscall
            |
            """.trimMargin(),
            RSyntaxHighlighter.assembler,
        )

    @Test
    fun `instructions, directives and comments`() {
        assertEquals(setOf(TokenStyle.KEYWORD), asm.stylesOf("mov"))
        assertEquals(setOf(TokenStyle.KEYWORD), asm.stylesOf("section"))
        assertEquals(setOf(TokenStyle.KEYWORD), asm.stylesOf("syscall"))
        asm.assertExactly(TokenStyle.COMMENT, "; comment")
        asm.assertExactly(TokenStyle.STRING, "\"hi\"")
    }

    @Test
    fun `registers are builtins in any case`() {
        for (register in listOf("eax", "RBX", "ecx", "r12d", "rsp")) {
            asm.assertExactly(TokenStyle.BUILTIN, register, wholeWord = true)
        }
    }

    @Test
    fun `hexadecimal numbers`() {
        for (number in listOf("0x1F", "0FFh")) asm.assertExactly(TokenStyle.NUMBER, number)
    }

    @Test
    fun `labels are types without their colon`() {
        asm.assertExactly(TokenStyle.TYPE, "_start", occurrence = 1, wholeWord = true)
        asm.assertExactly(TokenStyle.TYPE, ".loop")
    }
}
