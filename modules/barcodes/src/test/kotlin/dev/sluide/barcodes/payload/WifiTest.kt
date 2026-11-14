package dev.sluide.barcodes.payload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class WifiTest {
    @ParameterizedTest
    @ValueSource(strings = ["my;net", "back\\slash", "\"quoted\"", "a:b,c", "Café ☕", "\\;\\:", "trailing\\"])
    fun `ssid and password survive the round trip`(value: String) {
        val wifi = Wifi(value, WifiSecurity.WPA, value, hidden = false)
        assertEquals(wifi, Wifi.parse(wifi.text()))
    }

    @Test
    fun `open network leaves the password out`() {
        val wifi = Wifi("Cafe", WifiSecurity.NONE, "", hidden = false)
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifi.text())
        assertEquals(wifi, Wifi.parse(wifi.text()))
    }

    @Test
    fun `password of an open network is not written`() {
        assertEquals("WIFI:T:nopass;S:Cafe;;", Wifi("Cafe", WifiSecurity.NONE, "x", hidden = false).text())
    }

    @Test
    fun `wep and hidden round trip`() {
        val wifi = Wifi("Old", WifiSecurity.WEP, "abcde", hidden = true)
        assertEquals("WIFI:T:WEP;S:Old;P:abcde;H:true;;", wifi.text())
        assertEquals(wifi, Wifi.parse(wifi.text()))
    }

    @Test
    fun `escapes the special characters in the text`() {
        val text = Wifi("a;b", WifiSecurity.WPA, "p\"q", hidden = false).text()
        assertEquals("WIFI:T:WPA;S:a\\;b;P:p\\\"q;;", text)
    }

    @Test
    fun `reads another field order`() {
        assertEquals(Wifi("Home", WifiSecurity.WPA, "secret", false), Wifi.parse("WIFI:S:Home;T:WPA;P:secret;;"))
    }

    @Test
    fun `maps sae to wpa`() {
        assertEquals(Wifi("x", WifiSecurity.WPA, "y", false), Wifi.parse("WIFI:T:SAE;S:x;P:y;;"))
    }

    @Test
    fun `a security type the template does not know is not a wifi`() {
        assertNull(Wifi.parse("WIFI:T:WPA2-EAP;S:Corp;E:PEAP;I:me;P:pw;;"))
    }

    @Test
    fun `quotes around a value are dropped but escaped ones are kept`() {
        assertEquals(
            Wifi("MyNet", WifiSecurity.WPA, "pass1234", false),
            Wifi.parse("WIFI:T:WPA;S:\"MyNet\";P:\"pass1234\";;"),
        )
        assertEquals(Wifi("\"MyNet\"", WifiSecurity.WPA, "x", false), Wifi.parse("WIFI:T:WPA;S:\\\"MyNet\\\";P:x;;"))
    }

    @Test
    fun `an escaped closing quote is not stripped`() {
        assertEquals("\"abc\"", Wifi.parse("WIFI:T:WPA;S:\"abc\\\";P:x;;")?.ssid)
    }

    @Test
    fun `field keys are trimmed`() {
        assertEquals(Wifi("x", WifiSecurity.WPA, "y", false), Wifi.parse("WIFI:S:x;\nT:WPA;P:y;;"))
    }

    @Test
    fun `missing type means open`() {
        assertEquals(Wifi("x", WifiSecurity.NONE, "", false), Wifi.parse("WIFI:S:x;;"))
    }

    @Test
    fun `other text is not a wifi`() {
        assertNull(Wifi.parse("hello"))
        assertNull(Wifi.parse("WIFI:"))
    }
}
