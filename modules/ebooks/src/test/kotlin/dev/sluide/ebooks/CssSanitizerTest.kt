package dev.sluide.ebooks

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CssSanitizerTest {
    private val uris =
        ResourceUris(
            mapOf(
                "a.png" to Resource("image/png", TestEpub.PNG),
                "fonts/f.woff" to Resource("font/woff", byteArrayOf(1, 2, 3)),
                "evil.png" to Resource("image/png\" onerror=\"x", TestEpub.PNG),
            ),
        )

    private fun clean(css: String) = CssSanitizer.sanitize(css, "", uris)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a{background:url(https://evil.example/x",
            "a{background:url(\"https://evil.example/x",
            "a{background:url('https://evil.example/x'",
            "a{content:\"x\nbackground:url(https://evil.example/x);}",
            "a{background:u\\72l(x'https://evil.example/y)}",
            "a{background:\\75 rl(https://evil.example/x)}",
            "a{background:u\\72 l(https://evil.example/x)}",
            "a{background:ur\\6c (https://evil.example/x)}",
            "a{background:URL( https://evil.example/x )}",
            "a{background:url(  \"https://evil.example/x\"  )}",
            "a{background:url(x y https://evil.example/x)}",
            "a{background:-webkit-image-set(\"https://evil.example/x\" 1x)}",
            "a{background:IMAGE-SET(url(https://evil.example/x) 1x)}",
            "a{background:cross-fade(url(https://evil.example/x), red)}",
            "a{background:image(\"https://evil.example/x\")}",
            "a{background:src(\"https://evil.example/x\")}",
            "@import url(https://evil.example/x.css);a{}",
            "@IMPORT 'https://evil.example/x.css'",
            "@import \"https://evil.example/x.css\" screen;a{color:red}",
            "/* open comment url(https://evil.example/x)",
        ],
    )
    fun `remote loads are removed in every spelling`(css: String) {
        val result = clean(css)
        assertFalse("evil" in result, result)
        assertTrue(CssSanitizer.isClean(result), result)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a{color:red;background:ima/**/ge(\"https://evil.example/1\")}",
            "a{color:red;background:cross/**/-fade(url(https://evil.example/1), red)}",
            "a{color:red;background:sr/**/c(\"https://evil.example/1\")}",
            "a{color:red;background:ur/**/l(https://evil.example/1)}",
            "a{color:red;background:image-/**/set(\"https://evil.example/1\" 1x)}",
        ],
    )
    fun `a comment between the parts of a name does not join them`(css: String) {
        val result = clean(css)
        assertFalse(Regex("(?i)(image|cross-fade|src|url|image-set)\\(").containsMatchIn(result), result)
        // Not refused as a whole by the final check: the rewrite alone made it safe.
        assertTrue("color:red" in result, result)
    }

    @Test
    fun `urls to resources of the book become data URIs, any other becomes none`() {
        assertEquals("a{b:url(\"data:image/png;base64,${base64(TestEpub.PNG)}\")}", clean("a{b:url(a.png)}"))
        assertEquals("a{b:url(\"data:font/woff;base64,AQID\")}", clean("a{b:url( 'fonts/f.woff' )}"))
        assertEquals("a{b:none}", clean("a{b:url(missing.png)}"))
        assertEquals("a{b:none}", clean("a{b:url(//host/x.png)}"))
        assertEquals("a{b:none}", clean("a{b:url(evil.png)}"), "a resource whose media type is not plain is unusable")
    }

    private fun base64(bytes: ByteArray) =
        java.util.Base64
            .getEncoder()
            .encodeToString(bytes)

    @Test
    fun `data URIs of the book are kept for images and fonts only`() {
        assertEquals("a{b:url(\"data:image/png;base64,AAAA\")}", clean("a{b:url(data:image/png;base64,AAAA)}"))
        assertEquals("a{b:none}", clean("a{b:url(data:text/html;base64,AAAA)}"))
        assertEquals("a{b:none}", clean("a{b:url(\"data:image/png;base64,AAAA\\\" onerror=\\\"x\")}"))
    }

    @Test
    fun `a closing style tag cannot leave the style element`() {
        val result = clean("a{}</style><script>alert(1)</script>")
        assertFalse("</" in result)
        assertTrue("<\\/style>" in result)
    }

    @Test
    fun `ordinary CSS stays and comments and imports go`() {
        assertEquals(
            " p.a > b { margin: 0 auto; content: \"\\201C\" } @media (max-width: 1em) { i { color: red } }",
            clean(
                "/* c */p.a > b { margin: 0 auto; content: \"\\201C\" } @media (max-width: 1em) { i { color: red } }",
            ),
        )
        assertEquals("a{}", clean("@import 'x.css';a{}"))
    }

    @Test
    fun `unclosed comments and urls are scanned once`() {
        val started = System.nanoTime()
        clean("/*".repeat(200_000))
        clean("url(".repeat(200_000))
        clean("url(\"".repeat(100_000))
        clean("a{b:\"".repeat(100_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000, "linear time")
    }

    @Test
    fun `the final check refuses what the sanitiser would never write`() {
        assertFalse(CssSanitizer.isClean("a{b:url(https://evil.example/x)}"))
        assertFalse(CssSanitizer.isClean("a{b:url('x')}"))
        assertFalse(CssSanitizer.isClean("a{b:image-set(x)}"))
        assertFalse(CssSanitizer.isClean("a{b:image(\"x\")}"))
        assertFalse(CssSanitizer.isClean("a{b:-webkit-cross-fade(x)}"))
        assertFalse(CssSanitizer.isClean("a{b:src(\"x\")}"))
        assertFalse(CssSanitizer.isClean("@import 'x';"))
        assertFalse(CssSanitizer.isClean("a{b:\\75rl(x)}"))
        assertTrue(CssSanitizer.isClean("a{b:url(\"data:image/png;base64,AAAA\");content:\"url(x) \\\\ @import\"}"))
    }

    @Test
    fun `a spelling the rewrite leaves alone but the final check refuses gives no CSS at all`() {
        // Not a function call, so the rewrite keeps it; only isClean stops it.
        assertEquals("", clean("a{animation-name:foo-image-set}"))
        assertEquals("", clean("a{b:foo-image(x)}"))
        assertEquals("", clean("a{b:foo-cross-fade(x)}"))
        assertEquals("", clean("a{b:foo-src(x)}"))
        assertEquals("a{b:c}", clean("a{b:c}"))
    }

    @Test
    fun `a data URI is made once per resource`() {
        assertSame(uris.dataUri("a.png", ""), uris.dataUri("./a.png", ""))
    }
}
