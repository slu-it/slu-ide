package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class VhdlHighlightingTest {
    private val vhdl =
        Highlighted(
            """
            |library ieee;
            |-- comment
            |entity e is
            |  port (clk : in std_logic; d : in unsigned(7 downto 0); q : out STRING);
            |end entity;
            |ARCHITECTURE rtl OF e IS
            |  signal t : time := 10 ns;
            |begin
            |  process (clk) begin
            |    if falling_edge(clk) then q <= x"1F"; end if;
            |  end process;
            |end architecture;
            |
            """.trimMargin(),
            RSyntaxHighlighter.vhdl,
        )

    @Test
    fun `keywords in any case`() {
        for (word in listOf("library", "entity", "port", "downto", "ARCHITECTURE", "OF", "IS", "process")) {
            assertEquals(setOf(TokenStyle.KEYWORD), vhdl.stylesOf(word), word)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), vhdl.stylesOf("falling_edge"))
    }

    @Test
    fun `types, comments and numbers`() {
        for (type in listOf("std_logic", "unsigned", "STRING", "time")) {
            assertEquals(setOf(TokenStyle.TYPE), vhdl.stylesOf(type), type)
        }
        vhdl.assertExactly(TokenStyle.COMMENT, "-- comment")
        vhdl.assertExactly(TokenStyle.NUMBER, "x\"1F\"")
    }
}
