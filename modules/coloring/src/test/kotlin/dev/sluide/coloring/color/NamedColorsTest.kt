package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamedColorsTest {
    @Test
    fun `there are 148 distinct names`() {
        assertEquals(148, NamedColors.all.size)
        assertEquals(
            148,
            NamedColors.all
                .map { it.first }
                .toSet()
                .size,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "rebeccapurple,663399",
        "lightgoldenrodyellow,fafad2",
        "darkolivegreen,556b2f",
        "papayawhip,ffefd5",
        "cornflowerblue,6495ed",
        "red,ff0000",
        "black,000000",
        "white,ffffff",
        "gray,808080",
        "grey,808080",
        "darkslategrey,2f4f4f",
        "mediumspringgreen,00fa9a",
        "yellowgreen,9acd32",
        "tan,d2b48c",
        "teal,008080",
        "Aliceblue,f0f8ff",
    )
    fun `spot checks`(
        name: String,
        hex: String,
    ) {
        val v = hex.toInt(16)
        assertEquals(Rgb8(v shr 16, (v shr 8) and 0xff, v and 0xff), NamedColors.byName(name))
    }

    @Test
    fun `unknown name is null`() {
        assertNull(NamedColors.byName("transparent"))
        assertNull(NamedColors.byName("nope"))
    }

    @ParameterizedTest
    @CsvSource(
        "0,255,255,aqua",
        "255,0,255,fuchsia",
        "169,169,169,darkgray",
        "128,128,128,gray",
        "102,51,153,rebeccapurple",
    )
    fun `nameOf prefers the alphabetically first alias`(
        r: Int,
        g: Int,
        b: Int,
        name: String,
    ) {
        assertEquals(name, NamedColors.nameOf(Rgb8(r, g, b)))
    }

    @Test
    fun `nameOf needs an exact opaque match`() {
        assertNull(NamedColors.nameOf(Rgb8(255, 0, 0, 128)))
        assertNull(NamedColors.nameOf(Rgb8(254, 0, 0)))
    }
}
