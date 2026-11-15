package dev.sluide.java.trust

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenTrustCheckTest {
    @TempDir
    lateinit var root: Path

    private val check = MavenTrustCheck()

    private fun pom(
        relative: String,
        vararg modules: String,
        extra: String = "",
    ): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(
            "<project>${modules.joinToString(
                "",
                "<modules>",
                "</modules>",
            ) { "<module>$it</module>" }}$extra</project>",
        )
        return file
    }

    private fun texts() = check.check(root)!!.map { it.text }

    private fun fingerprint(): String = check.check(root)!!.single().fingerprint

    @Test
    fun `a folder without poms has no findings`() {
        root.resolve("README.md").writeText("x")
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `the check is called maven`() {
        assertEquals("maven", check.id)
        assertEquals("Maven", check.name)
    }

    @Test
    fun `a pom in the folder is a finding with its note`() {
        pom("pom.xml")
        val finding = check.check(root)!!.single()
        assertEquals("Maven build in .", finding.text)
        assertEquals("Importing it into the Java language server runs its build plugins and extensions.", finding.note)
        assertTrue(finding.fingerprint.startsWith("maven\u0000.\u0000"), finding.fingerprint)
    }

    @Test
    fun `a reactor with modules is one finding`() {
        pom("pom.xml", "a", "b/c")
        pom("a/pom.xml")
        pom("b/c/pom.xml")
        assertEquals(listOf("Maven build in ."), texts())
    }

    @Test
    fun `independent builds are separate findings named by their folder, sorted`() {
        pom("z/pom.xml")
        pom("a/pom.xml", "m")
        pom("a/m/pom.xml")
        assertEquals(listOf("Maven build in a", "Maven build in z"), texts())
    }

    @Test
    fun `a pom its parent does not list is a root of its own`() {
        pom("pom.xml", "listed")
        pom("listed/pom.xml")
        pom("unlisted/pom.xml")
        assertEquals(listOf("Maven build in .", "Maven build in unlisted"), texts())
    }

    @Test
    fun `a module of a reactor below the folder is no finding`() {
        pom("tools/pom.xml", "m")
        pom("tools/m/pom.xml")
        assertEquals(listOf("Maven build in tools"), texts())
    }

    @Test
    fun `the fingerprint changes when a module pom changes`() {
        pom("pom.xml", "a")
        pom("a/pom.xml")
        val original = fingerprint()
        assertEquals(original, fingerprint())
        pom("a/pom.xml", extra = "<build><plugins/></build>")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `the fingerprint changes when the root pom changes`() {
        pom("pom.xml")
        val original = fingerprint()
        pom("pom.xml", extra = "<x/>")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `the fingerprint changes when two module poms swap places`() {
        pom("pom.xml", "a", "b")
        pom("a/pom.xml", extra = "<x/>")
        pom("b/pom.xml", extra = "<y/>")
        val original = fingerprint()
        pom("a/pom.xml", extra = "<y/>")
        pom("b/pom.xml", extra = "<x/>")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `the fingerprint changes when the maven extensions appear and change`() {
        pom("pom.xml")
        val without = fingerprint()
        root
            .resolve(".mvn")
            .createDirectories()
            .resolve("extensions.xml")
            .writeText("<extensions/>")
        val with = fingerprint()
        assertNotEquals(without, with)
        root.resolve(".mvn/extensions.xml").writeText("<extensions><extension/></extensions>")
        assertNotEquals(with, fingerprint())
    }

    @Test
    fun `the fingerprint changes with maven config`() {
        pom("pom.xml")
        val without = fingerprint()
        root
            .resolve(".mvn")
            .createDirectories()
            .resolve("maven.config")
            .writeText("-T4")
        assertNotEquals(without, fingerprint())
    }

    @Test
    fun `the extensions of a nested root do not change another root's fingerprint`() {
        pom("a/pom.xml")
        pom("b/pom.xml")
        val before = check.check(root)!!.first { it.text.endsWith("b") }.fingerprint
        root
            .resolve("a/.mvn")
            .createDirectories()
            .resolve("extensions.xml")
            .writeText("<extensions/>")
        assertEquals(before, check.check(root)!!.first { it.text.endsWith("b") }.fingerprint)
    }

    @Test
    fun `a pom in a skipped folder is not a finding`() {
        listOf(".git", "build", "target", "bin", "out", "node_modules", ".hidden").forEach { pom("$it/pom.xml") }
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `a pom nine folders down is not found but eight down is`() {
        pom("1/2/3/4/5/6/7/8/pom.xml")
        assertEquals(listOf("Maven build in 1/2/3/4/5/6/7/8"), texts())
        root.resolve("1/2/3/4/5/6/7/8/pom.xml").deleteExisting()
        pom("1/2/3/4/5/6/7/8/9/pom.xml")
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `a walk that reaches the folder cap cannot be checked`() {
        (1..5).forEach { root.resolve("d$it").createDirectories() }
        assertNull(MavenTrustCheck(3).check(root))
        assertEquals(emptyList(), MavenTrustCheck(6).check(root))
    }

    @Test
    fun `a missing folder cannot be checked`() {
        assertNull(check.check(root.resolve("missing")))
    }

    @Test
    fun `a root pom with a DOCTYPE still gives its finding`() {
        root
            .resolve(
                "pom.xml",
            ).writeText("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><project>&e;</project>")
        assertEquals(listOf("Maven build in ."), texts())
    }

    @Test
    fun `a pom behind a link to a folder is not followed`() {
        val outside = root.resolveSibling("${root.fileName}-outside").also { it.createDirectories() }
        try {
            outside.resolve("pom.xml").writeText("<project/>")
            java.nio.file.Files
                .createSymbolicLink(root.resolve("linked"), outside)
            assertEquals(emptyList(), check.check(root))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an interrupted walk cannot be checked`() {
        pom("pom.xml")
        Thread.currentThread().interrupt()
        try {
            assertNull(check.check(root))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `the same content in extensions and maven config gives different fingerprints`() {
        pom("pom.xml")
        root
            .resolve(".mvn")
            .createDirectories()
            .resolve("extensions.xml")
            .writeText("same")
        val asExtensions = fingerprint()
        root.resolve(".mvn/extensions.xml").deleteExisting()
        root.resolve(".mvn/maven.config").writeText("same")
        assertNotEquals(asExtensions, fingerprint())
    }

    @Test
    fun `a folder named like a maven file is not part of the fingerprint`() {
        pom("pom.xml")
        val without = fingerprint()
        root.resolve(".mvn/extensions.xml").createDirectories()
        assertEquals(without, fingerprint())
    }

    @Test
    fun `a change beyond the first MiB of a pom changes the fingerprint`() {
        val padding = "x".repeat(1 shl 20)
        pom("pom.xml", extra = "<!--$padding-->")
        val original = fingerprint()
        pom("pom.xml", extra = "<!--$padding-->$padding")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `an unreadable extensions file is not the same as none`() {
        pom("pom.xml")
        val without = fingerprint()
        val extensions = root.resolve(".mvn").createDirectories().resolve("extensions.xml")
        extensions.writeText("<extensions/>")
        extensions.toFile().setReadable(false)
        // A superuser reads it anyway; then there is nothing unreadable to test.
        org.junit.jupiter.api.Assumptions
            .assumeFalse(Files.isReadable(extensions))
        try {
            assertNotEquals(without, fingerprint())
        } finally {
            extensions.toFile().setReadable(true)
        }
    }

    @Test
    fun `the fingerprint walk below a root can reach the cap the walk of the folder did not`() {
        pom("x/pom.xml")
        root.resolve("x/1/2/3/4/5/6/7/a").createDirectories()
        root.resolve("x/1/2/3/4/5/6/7/b").createDirectories()
        // The walk from the folder stops at depth 8 below it: 9 folders. The one from x reaches a and b: 10.
        assertNull(MavenTrustCheck(9).check(root))
        assertEquals(listOf("Maven build in x"), MavenTrustCheck(10).check(root)!!.map { it.text })
    }

    @Test
    fun `every Maven root of the build resolver is a finding`() {
        pom("pom.xml", "a", "b/c")
        pom("a/pom.xml")
        pom("b/c/pom.xml")
        pom("solo/pom.xml")
        pom("solo/inner/pom.xml")
        val findings = texts().toSet()
        listOf("a/A.java", "b/c/A.java", "solo/A.java", "solo/inner/A.java", "A.java").forEach {
            val build =
                dev.sluide.java.JavaBuilds
                    .buildRoot(root.resolve(it), root)
            val relative = build?.let { b -> root.relativize(b.root).toString().ifEmpty { "." } }
            assertTrue("Maven build in $relative" in findings, "$it -> $relative")
        }
    }

    @Test
    fun `the pom of a module in a skipped folder is part of the fingerprint`() {
        pom("pom.xml", "build")
        pom("build/pom.xml")
        val original = fingerprint()
        pom("build/pom.xml", extra = "<build><plugins/></build>")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `modules of modules and profile modules below a skipped folder are followed`() {
        root.resolve("pom.xml").writeText(
            "<project><profiles><profile><modules><module>.x</module></modules></profile></profiles></project>",
        )
        pom(".x/pom.xml", "deep/er")
        pom(".x/deep/er/pom.xml")
        val original = fingerprint()
        pom(".x/deep/er/pom.xml", extra = "<x/>")
        assertNotEquals(original, fingerprint())
    }

    @Test
    fun `a module cycle ends`() {
        pom("pom.xml", "a")
        pom("a/pom.xml", "..")
        assertEquals(listOf("Maven build in ."), texts())
    }

    @Test
    fun `a module outside the checked folder is not read`() {
        val window = root.resolve("w")
        pom("w/pom.xml", "../outside")
        pom("outside/pom.xml")
        val original = check.check(window)!!.single().fingerprint
        pom("outside/pom.xml", extra = "<changed/>")
        assertEquals(original, check.check(window)!!.single().fingerprint)
    }

    @Test
    fun `following modules in skipped folders counts against the folder cap`() {
        val skipped = listOf("build", "out", "target", "bin")
        pom("pom.xml", *skipped.toTypedArray())
        skipped.forEach { pom("$it/pom.xml") }
        assertNull(MavenTrustCheck(3).check(root))
        assertEquals(listOf("Maven build in ."), MavenTrustCheck(5).check(root)!!.map { it.text })
    }
}
