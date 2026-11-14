package dev.sluide.barcodes.payload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ContactTest {
    @Test
    fun `round trips values with commas semicolons and a newline`() {
        val contact =
            Contact("Jane Q. Doe", "Acme, Inc.; R&D\nSouth", "+49 30 1234", "jane@example.org", "https://example.org/a")
        assertEquals(contact, Contact.parse(contact.text()))
    }

    @Test
    fun `writes a vCard 3 with CRLF and the name split on the last space`() {
        val text = Contact("Jane Q Doe", "", "", "", "").text()
        assertEquals("BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane Q;;;\r\nFN:Jane Q Doe\r\nEND:VCARD\r\n", text)
    }

    @Test
    fun `escapes the organization`() {
        val text = Contact("A B", "a,b;c\\d\ne", "", "", "").text()
        assertTrue(text.contains("ORG:a\\,b\\;c\\\\d\\ne\r\n"), text)
    }

    @Test
    fun `one word name`() {
        val contact = Contact("Madonna", "", "", "", "")
        assertTrue(contact.text().contains("N:Madonna;;;;\r\n"))
        assertEquals(contact, Contact.parse(contact.text()))
    }

    @Test
    fun `reads a MECARD`() {
        assertEquals(
            Contact("John Doe", "", "123", "j@x.org", ""),
            Contact.parse("MECARD:N:Doe,John;TEL:123;EMAIL:j@x.org;;"),
        )
    }

    @Test
    fun `reads a vCard 2_1 with a typed phone`() {
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\nN:Doe;John\r\nTEL;CELL:555-1\r\nORG:Acme\r\nEND:VCARD\r\n"
        assertEquals(Contact("John Doe", "Acme", "555-1", "", ""), Contact.parse(text))
    }

    @Test
    fun `other text is no contact`() {
        assertNull(Contact.parse("hello"))
        assertNull(Contact.parse("WIFI:S:x;;"))
    }
}
