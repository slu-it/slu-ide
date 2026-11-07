package dev.sluide.languageservers

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LspUrisTest {
    @TempDir
    lateinit var folder: Path

    @ParameterizedTest
    @ValueSource(strings = ["plain.py", "with space.py", "ümlaut ä.py", "日本語/モジュール.py", "hash#percent%.py", "a+b=c.py"])
    fun `a path survives the round trip through its URI`(name: String) {
        assumeUnicodePaths(name)
        val path = folder.resolve(name)

        val uri = LspUris.of(path)

        assertEquals(path, LspUris.toPath(uri))
        assertFalse(' ' in uri, uri)
    }

    @Test
    fun `a folder's URI has no trailing slash`() {
        Files.createDirectory(folder.resolve("pkg"))

        assertFalse(LspUris.of(folder.resolve("pkg")).endsWith("/"))
    }

    @ParameterizedTest
    @CsvSource(
        "file:///tmp/a%20b/%C3%A4.py, file:///tmp/a b/ä.py",
        "file:///tmp/a%20b/%C3%A4.py, file:///tmp/a%20b/ä.py",
        "file:///tmp/a%20b/%C3%A4.py, file:/tmp/a%20b/%c3%a4.py",
        "file:///tmp/a%20b/%C3%A4.py, file://localhost/tmp/a%20b/%C3%A4.py",
        "file:///tmp/a%20b.py, file:/tmp/a%20b.py",
        "file:///tmp/x.py, file:///tmp/sub/../x.py",
    )
    fun `URIs of one file that servers encode differently normalize to the same`(
        ours: String,
        theirs: String,
    ) {
        assumeUnicodePaths(theirs)

        assertEquals(LspUris.normalize(ours), LspUris.normalize(theirs))
    }

    @ParameterizedTest
    @ValueSource(strings = ["untitled:Untitled-1", "file://server/share/x.py", "file:///broken%2"])
    fun `URIs that are no local path have none and stay as they are`(uri: String) {
        assertNull(LspUris.toPath(uri))
        assertEquals(uri, LspUris.normalize(uri))
    }

    /**
     * A JVM started without a locale (the sandbox's Gradle) encodes file names as ASCII and cannot have other paths;
     * CI, macOS and the app have UTF-8. Run with `LC_ALL=C.UTF-8` to cover them here.
     */
    private fun assumeUnicodePaths(text: String) {
        val nonAscii = text.any { it.code > 127 } || "%C3" in text || "%c3" in text
        val encoding = Charset.forName(System.getProperty("sun.jnu.encoding") ?: "UTF-8")
        assumeTrue(!nonAscii || encoding.newEncoder().canEncode("äモ"), "file names are encoded as $encoding")
    }
}
