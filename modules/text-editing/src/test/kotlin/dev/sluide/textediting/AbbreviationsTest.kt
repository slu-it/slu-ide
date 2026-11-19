package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbbreviationsTest {
    @Test
    fun `a numbered word is an abbreviation only before a digit`() {
        assertTrue(Abbreviations.isAbbreviation("No", '5'))
        assertTrue(Abbreviations.isAbbreviation("fig", '3'))
        assertFalse(Abbreviations.isAbbreviation("No", 'x'))
        assertFalse(Abbreviations.isAbbreviation("Fig", null))
        assertFalse(Abbreviations.isAbbreviation("paid", '5'))
    }

    @Test
    fun `a single capital initial is an abbreviation, except I`() {
        assertTrue(Abbreviations.isAbbreviation("J", 'R'))
        assertTrue(Abbreviations.isAbbreviation("É", null))
        assertFalse(Abbreviations.isAbbreviation("I", 'x'))
        assertFalse(Abbreviations.isAbbreviation("j", 'r'))
        assertFalse(Abbreviations.isAbbreviation("JK", 'r'))
    }

    @Test
    fun `an entry matches in any case`() {
        assertTrue(Abbreviations.isAbbreviation("DR", null))
        assertTrue(Abbreviations.isAbbreviation("etc", null))
        assertFalse(Abbreviations.isAbbreviation("doctor", null))
    }

    @Test
    fun `a multi-part entry needs the word before or after to match too`() {
        val german = Abbreviations.GERMAN
        assertTrue(Abbreviations.isAbbreviation("b", null, german, previous = "Z"))
        assertFalse(Abbreviations.isAbbreviation("b", null, german, previous = "q"))
        assertFalse(Abbreviations.isAbbreviation("b", null, german, previous = null))
        assertTrue(Abbreviations.isAbbreviation("z", null, german, following = "B"))
        assertFalse(Abbreviations.isAbbreviation("z", null, german, following = "x"))
        assertFalse(Abbreviations.isAbbreviation("z", null, german, following = null))
        assertFalse(Abbreviations.isAbbreviation("z", null, german))
    }
}
