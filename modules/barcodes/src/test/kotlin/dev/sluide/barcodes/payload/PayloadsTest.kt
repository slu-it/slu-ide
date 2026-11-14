package dev.sluide.barcodes.payload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class PayloadsTest {
    private fun f(
        label: String,
        value: String,
        secret: Boolean = false,
    ) = PayloadField(label, value, secret)

    @Test
    fun `wifi`() {
        assertEquals(
            Payload(
                "Wi-Fi",
                listOf(
                    f("Network", "Home"),
                    f("Security", "WPA/WPA2/WPA3"),
                    f("Password", "pw", true),
                    f("Hidden", "Yes"),
                ),
            ),
            Payloads.parse("WIFI:T:WPA;S:Home;P:pw;H:true;;"),
        )
        assertEquals(
            Payload("Wi-Fi", listOf(f("Network", "Cafe"), f("Security", "None"))),
            Payloads.parse("WIFI:T:nopass;S:Cafe;;"),
        )
    }

    @Test
    fun `contact lists every phone`() {
        val text =
            "BEGIN:VCARD\nVERSION:3.0\nN:Doe;John;;;\nFN:John Doe\nORG:Acme\nTITLE:CTO\nTEL:1\nTEL:2\n" +
                "EMAIL:j@x.org\nURL:https://x.org\nNOTE:hi\nBDAY:19900102\nEND:VCARD"
        val payload = requireNotNull(Payloads.parse(text))
        assertEquals("Contact", payload.kind)
        assertEquals(
            listOf(
                f("Name", "John Doe"),
                f("Organization", "Acme"),
                f("Title", "CTO"),
                f("Phone", "1"),
                f("Phone", "2"),
                f("Email", "j@x.org"),
                f("Website", "https://x.org"),
                f("Note", "hi"),
            ),
            payload.fields.filter { it.label != "Birthday" },
        )
        assertEquals(f("Birthday", "1990-01-02"), payload.fields.single { it.label == "Birthday" })
    }

    @Test
    fun `email from mailto and MATMSG`() {
        val expected = Payload("Email", listOf(f("To", "a@example.org"), f("Subject", "Hi"), f("Body", "Text")))
        assertEquals(expected, Payloads.parse("mailto:a@example.org?subject=Hi&body=Text"))
        assertEquals(expected, Payloads.parse("MATMSG:TO:a@example.org;SUB:Hi;BODY:Text;;"))
    }

    @Test
    fun `phone`() {
        assertEquals(Payload("Phone", listOf(f("Number", "+4930123"))), Payloads.parse("tel:+4930123"))
    }

    @Test
    fun `sms`() {
        assertEquals(
            Payload("SMS", listOf(f("Number", "+4930123"), f("Message", "Hello"))),
            Payloads.parse("smsto:+4930123:Hello"),
        )
    }

    @Test
    fun `location`() {
        assertEquals(
            Payload(
                "Location",
                listOf(f("Latitude", "48.2"), f("Longitude", "11.5"), f("Altitude", "500"), f("Query", "cafe")),
            ),
            Payloads.parse("geo:48.2,11.5,500?q=cafe"),
        )
        assertEquals(
            Payload("Location", listOf(f("Latitude", "48.2"), f("Longitude", "11.5"))),
            Payloads.parse("geo:48.2,11.5"),
        )
    }

    @Test
    fun `calendar event`() {
        val text =
            "BEGIN:VEVENT\nSUMMARY:Party\nDTSTART:20300102T030400Z\nDTEND:20300103\n" +
                "LOCATION:Home\nDESCRIPTION:Bring cake\nEND:VEVENT"
        val start =
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .format(Instant.parse("2030-01-02T03:04:00Z").atZone(ZoneId.systemDefault()))
        val payload = requireNotNull(Payloads.parse(text))
        assertEquals("Calendar event", payload.kind)
        assertEquals(listOf("Summary", "Start", "End", "Location", "Description"), payload.fields.map { it.label })
        assertEquals(start, payload.fields[1].value)
        assertEquals("2030-01-03", payload.fields[2].value)
        assertEquals(listOf("Party", "Home", "Bring cake"), listOf(0, 3, 4).map { payload.fields[it].value })
    }

    @Test
    fun `web link`() {
        assertEquals(
            Payload("Web link", listOf(f("URL", "https://example.com/a?b=c"))),
            Payloads.parse("https://example.com/a?b=c"),
        )
        assertEquals(
            Payload("Web link", listOf(f("URL", "https://example.com"), f("Title", "Ex"))),
            Payloads.parse("MEBKM:TITLE:Ex;URL:https://example.com;;"),
        )
    }

    @Test
    fun `totp`() {
        assertEquals(
            Payload(
                "One-time password",
                listOf(
                    f("Type", "TOTP"),
                    f("Issuer", "Example Co"),
                    f("Account", "alice+gh@x.org"),
                    f("Secret", "JBSWY3DPEHPK3PXP", true),
                    f("Algorithm", "SHA256"),
                    f("Digits", "8"),
                    f("Period", "60"),
                ),
            ),
            Payloads.parse(
                "otpauth://totp/Example%20Co:alice+gh%40x.org?secret=JBSWY3DPEHPK3PXP&issuer=Example%20Co" +
                    "&algorithm=SHA256&digits=8&period=60",
            ),
        )
    }

    @Test
    fun `hotp takes the issuer from the label and shows the counter`() {
        assertEquals(
            Payload(
                "One-time password",
                listOf(
                    f("Type", "HOTP"),
                    f("Issuer", "Acme"),
                    f("Account", "bob"),
                    f("Secret", "ABC", true),
                    f("Counter", "5"),
                ),
            ),
            Payloads.parse("otpauth://hotp/Acme:bob?secret=ABC&counter=5&period=30"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "otpauth://totp/x?issuer=a", "otpauth://totp/x?secret=",
            "otpauth://foo/x?secret=A", "otpauth://totp",
        ],
    )
    fun `invalid otpauth is plain text`(text: String) {
        assertNull(Payloads.parse(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "hello world", "12345", "9780306406157", "012345678905", "example.com", "www.example.com", "WIFI:",
            "Password:abc123", "javascript:alert(1)",
            "BEGIN:VCARD",
        ],
    )
    fun `plain text gives null`(text: String) {
        assertNull(Payloads.parse(text))
    }

    @Test
    fun `text over the length cap is plain text`() {
        val text = "BEGIN:VCARD" + "\nN;".repeat(16 * 1024 / 3)
        assertTrue(text.length > Payloads.MAX_PARSED_LENGTH)
        assertNull(Payloads.parse(text))
        assertNull(Payloads.parse("https://example.com/" + "a".repeat(Payloads.MAX_PARSED_LENGTH)))
    }

    @Test
    fun `text just under the cap is still parsed`() {
        assertEquals("Web link", Payloads.parse("https://example.com/" + "a".repeat(7000))?.kind)
    }

    @Test
    fun `wifi with a security type the template does not know shows it as written`() {
        assertEquals(
            Payload("Wi-Fi", listOf(f("Network", "Corp"), f("Security", "WPA2-EAP"), f("Password", "pw", true))),
            Payloads.parse("WIFI:T:WPA2-EAP;S:Corp;E:PEAP;I:me;P:pw;;"),
        )
    }

    @Test
    fun `wifi values in quotes are shown without them`() {
        assertEquals(
            Payload(
                "Wi-Fi",
                listOf(f("Network", "MyNet"), f("Security", "WPA/WPA2/WPA3"), f("Password", "pass1234", true)),
            ),
            Payloads.parse("WIFI:T:WPA;S:\"MyNet\";P:\"pass1234\";;"),
        )
    }

    @Test
    fun `a bookmark whose url is not in the text is no link`() {
        assertNull(Payloads.parse("MEBKM:TITLE:http://x;URL:example.com;;"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["geo:999,999", "geo:abc", "otpauth://totp/%zz?secret=%", "BEGIN:VEVENT\nDTSTART:zz\nEND:VEVENT"],
    )
    fun `malformed structured text does not throw`(text: String) {
        Payloads.parse(text)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com/a?b=c", "  https://example.com/a?b=c \n", "HTTP://Example.com",
            "http://localhost:8080/",
        ],
    )
    fun `httpUrl accepts one http url`(text: String) {
        assertEquals(text.trim(), Payloads.httpUrl(text)?.toString())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://a.org\nhttps://b.org", "javascript:alert(1)", "file:///etc/passwd",
            "http://", "http:///x", "ftp://x",
            "see https://example.com now", "example.com", "", "   ", "https://exa mple.com",
            "https://google.com@evil.com/", "https://user:pw@host.example/", "https://bücher.de",
        ],
    )
    fun `httpUrl rejects everything else`(text: String) {
        assertNull(Payloads.httpUrl(text))
    }
}
