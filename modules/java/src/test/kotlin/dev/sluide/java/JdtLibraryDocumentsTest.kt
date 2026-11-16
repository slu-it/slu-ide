package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdtLibraryDocumentsTest {
    @TempDir
    lateinit var root: Path

    private val string =
        "jdt://contents/java.base/java.lang/String.class?=app/%5C/usr%5C/lib%5C/jvm=/javadoc_location=/x"

    private fun hash(uri: String) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)

    private fun nameOf(uri: String): String? =
        JdtLibraryDocuments
            .of(uri, root)
            ?.file
            ?.fileName
            ?.toString()

    @Test
    fun `a class URI is fetched with classFileContents into a file named after the class in the hash's folder`() {
        val document = assertNotNull(JdtLibraryDocuments.of(string, root))

        assertEquals("java/classFileContents", document.method)
        assertEquals(mapOf("uri" to string), document.params)
        assertEquals(root.resolve(hash(string)).resolve("String.java"), document.file)
    }

    @Test
    fun `the same URI gives the same file and another URI another folder`() {
        val other = string.replace("java.lang", "java.util")

        assertEquals(JdtLibraryDocuments.of(string, root)?.file, JdtLibraryDocuments.of(string, root)?.file)
        assertTrue(
            JdtLibraryDocuments.of(other, root)?.file?.parent != JdtLibraryDocuments.of(string, root)?.file?.parent,
        )
    }

    @Test
    fun `a name that is already a java file keeps its ending and a percent-encoded name is decoded`() {
        assertEquals("Foo.java", nameOf("jdt://contents/lib.jar/p/Foo.java"))
        assertEquals("Map\$Entry.java", nameOf("jdt://contents/lib.jar/p/Map%24Entry.class"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "file:///etc/passwd",
            "http://example.com/String.class",
            "https://example.com/String.class",
            "JDT-x://contents/String.class",
            "",
            "contents/String.class",
        ],
    )
    fun `a URI that is not jdt has no document`(uri: String) {
        assertNull(JdtLibraryDocuments.of(uri, root))
    }

    @Test
    fun `the scheme is read in any case`() {
        assertEquals("A.java", nameOf("JDT://contents/lib/A.class"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "jdt://contents/../../../etc/passwd",
            "jdt://contents/..%2f..%2f..%2fetc%2fpasswd",
            "jdt://contents/%2e%2e%2f",
            "jdt://contents/%2e%2e",
            "jdt://contents/..",
            "jdt://contents/....",
            "jdt://contents/",
            "jdt:",
            "jdt://",
            "jdt://contents/a/%00%2f%5c",
            "jdt://contents/a/%2F",
            "jdt://contents/a/%5C",
        ],
    )
    fun `hostile URIs stay below the root with a plain file name`(uri: String) {
        val document = JdtLibraryDocuments.of(uri, root)

        val file = assertNotNull(document).file
        val normalized = file.toAbsolutePath().normalize()
        assertTrue(normalized.startsWith(root.toAbsolutePath().normalize()), "$file is outside $root")
        assertEquals(root.resolve(hash(uri)), file.parent)
        assertTrue(Regex("[A-Za-z0-9_$.-]+").matches(file.fileName.toString()), file.fileName.toString())
        assertTrue(file.fileName.toString().endsWith(".java"))
    }

    @Test
    fun `a name of only dots or nothing becomes Class_java`() {
        assertEquals("Class.java", nameOf("jdt://contents/%2e%2e"))
        assertEquals("Class.java", nameOf("jdt://contents/"))
        assertEquals("Class.java", nameOf("jdt:"))
        assertEquals("Class.java", nameOf("jdt://contents/a/%00"))
    }

    @Test
    fun `NUL, slash and backslash in the name are dropped, the rest is kept`() {
        assertEquals("ab.java", nameOf("jdt://contents/x/a%00b%5C.class"))
        assertEquals("ab.java", nameOf("jdt://contents/x/a%2Fb.class"))
        assertEquals("ab.java", nameOf("jdt://contents/x/a b.class"))
    }

    @Test
    fun `the query and the fragment are not part of the name`() {
        assertEquals("A.java", nameOf("jdt://contents/lib/A.class?=x/%5Cy#frag/Other.class"))
    }

    @Test
    fun `a name of 10 kB is cut to 100 characters before the ending is added`() {
        val name = nameOf("jdt://contents/lib/" + "a".repeat(10_000) + ".class")

        assertEquals("a".repeat(100) + ".java", name)
    }

    @Test
    fun `only one trailing class ending is dropped and a bare one leaves the default name`() {
        assertEquals("A.class.java", nameOf("jdt://contents/lib/A.class.class"))
        assertEquals("Class.java", nameOf("jdt://contents/lib/.class"))
        assertEquals("Classes.java", nameOf("jdt://contents/lib/Classes"))
    }

    @Test
    fun `a URI without an authority or without a path after it`() {
        assertEquals("Foo.java", nameOf("jdt:Foo.class"))
        assertEquals("Class.java", nameOf("jdt://contents"))
    }

    @Test
    fun `a percent sign at the end of the name is dropped`() {
        assertEquals("a.java", nameOf("jdt://contents/lib/a%"))
        assertEquals("a4.java", nameOf("jdt://contents/lib/a%4"))
        assertEquals("a-.java", nameOf("jdt://contents/lib/a%2D"))
    }

    @Test
    fun `a broken percent sequence does not fail`() {
        assertEquals("a.java", nameOf("jdt://contents/lib/a%.class"))
        assertEquals("azz.java", nameOf("jdt://contents/lib/a%zz.class"))
        assertEquals("a-1.java", nameOf("jdt://contents/lib/a%-1.class"))
    }
}
