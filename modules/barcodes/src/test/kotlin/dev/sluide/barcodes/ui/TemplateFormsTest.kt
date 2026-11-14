package dev.sluide.barcodes.ui

import dev.sluide.barcodes.payload.Contact
import dev.sluide.barcodes.payload.Wifi
import dev.sluide.barcodes.payload.WifiSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateFormsTest {
    @Test
    fun `an empty Wi-Fi form defaults to WPA and cannot insert without a network`() {
        val form = WifiForm.of("hello")

        assertEquals(WifiSecurity.WPA, form.security)
        assertFalse(form.canInsert)
        form.ssid = "Home"
        assertTrue(form.canInsert)
    }

    @Test
    fun `the Wi-Fi form is prefilled from a Wi-Fi text and writes it back`() {
        val original = Wifi("My;Net", WifiSecurity.WEP, "pa:ss", hidden = true)
        val form = WifiForm.of(original.text())

        assertEquals("My;Net", form.ssid)
        assertEquals(WifiSecurity.WEP, form.security)
        assertEquals("pa:ss", form.password)
        assertTrue(form.hidden)
        assertEquals(original, Wifi.parse(form.text()))
    }

    @Test
    fun `the password is disabled for an open network and left out of the text`() {
        val form = WifiForm(Wifi("Cafe", WifiSecurity.WPA, "secret", hidden = false))
        assertTrue(form.passwordEnabled)

        form.security = WifiSecurity.NONE
        assertFalse(form.passwordEnabled)
        assertEquals("", checkNotNull(Wifi.parse(form.text())).password)
    }

    @Test
    fun `the Contact form needs one filled field and is prefilled from a vCard`() {
        assertFalse(ContactForm.of("hello").canInsert)
        val form = ContactForm.of(Contact("Ada Lovelace", "Analytical Engines", "+44 1", "ada@example.com", "").text())

        assertEquals("Ada Lovelace", form.name)
        assertEquals("Analytical Engines", form.organization)
        assertEquals("ada@example.com", form.email)
        assertTrue(form.canInsert)

        val empty = ContactForm()
        empty.website = "  "
        assertFalse(empty.canInsert)
        empty.phone = "123"
        assertTrue(empty.canInsert)
    }

    @Test
    fun `the Contact form's text round trips through the parser`() {
        val form = ContactForm()
        form.name = "Ada Lovelace"
        form.email = "ada@example.com"
        form.website = "https://example.com"

        val parsed = checkNotNull(Contact.parse(form.text()))
        assertEquals("Ada Lovelace", parsed.name)
        assertEquals("ada@example.com", parsed.email)
        assertEquals("https://example.com", parsed.website)
    }

    @Test
    fun `an oversized text is not parsed for the prefill`() {
        val form = WifiForm.of("WIFI:T:WPA;S:Home;;" + "x".repeat(10_000))

        assertEquals("", form.ssid)
    }
}
