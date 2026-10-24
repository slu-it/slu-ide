package dev.sluide.gradle.trust

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleTrustCheckTest {
    @TempDir
    lateinit var root: Path

    private val check = GradleTrustCheck()

    private fun wrapper(
        folder: Path,
        url: String = "https://services.gradle.org/distributions/gradle-9.0-bin.zip",
        script: String = "#!/bin/sh\n",
    ) {
        folder.createDirectories().resolve("gradlew").writeText(script)
        folder
            .resolve("gradle/wrapper")
            .createDirectories()
            .resolve("gradle-wrapper.properties")
            .writeText("distributionUrl=${url.replace(":", "\\:")}\n")
    }

    private fun scripts(
        folder: Path,
        vararg names: String,
    ) {
        folder.createDirectories()
        names.forEach { folder.resolve(it).writeText("") }
    }

    private fun texts(folder: Path = root) = check.check(folder)!!.map { it.text }

    @Test
    fun `a folder without Gradle has no findings`() {
        scripts(root, "README.md")
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `a wrapper is reported with what it downloads`() {
        wrapper(root)
        val finding = check.check(root)!!.single()
        assertEquals("Gradle wrapper in .", finding.text)
        assertEquals(
            "downloads and runs https://services.gradle.org/distributions/gradle-9.0-bin.zip " +
                "when tasks are listed or run",
            finding.note,
        )
    }

    @Test
    fun `a wrapper in a subfolder is named by its relative folder`() {
        wrapper(root.resolve("tools/app"))
        assertEquals(listOf("Gradle wrapper in tools/app"), texts())
    }

    @Test
    fun `the fingerprint changes with the distribution URL and the gradlew content`() {
        wrapper(root)
        val original = check.check(root)!!.single().fingerprint
        assertEquals(original, check.check(root)!!.single().fingerprint)

        wrapper(root, url = "https://evil.example/gradle.zip")
        val otherUrl = check.check(root)!!.single().fingerprint
        assertNotEquals(original, otherUrl)

        wrapper(root, url = "https://evil.example/gradle.zip", script = "#!/bin/sh\nrm -rf ~\n")
        assertNotEquals(otherUrl, check.check(root)!!.single().fingerprint)
    }

    @Test
    fun `build scripts without a wrapper are reported once, with the text as fingerprint`() {
        scripts(root, "settings.gradle.kts", "build.gradle.kts")
        scripts(root.resolve("app"), "build.gradle")
        val finding = check.check(root)!!.single()
        assertEquals("Gradle build scripts in .", finding.text)
        assertEquals("run by the global gradle when tasks are listed or run", finding.note)
        assertEquals(finding.text, finding.fingerprint)
    }

    @Test
    fun `separate builds without a wrapper are separate findings`() {
        scripts(root.resolve("a"), "build.gradle")
        scripts(root.resolve("b"), "settings.gradle")
        assertEquals(listOf("Gradle build scripts in a", "Gradle build scripts in b"), texts())
    }

    @Test
    fun `a build file below a wrapper belongs to it, but every wrapper is reported`() {
        wrapper(root)
        scripts(root.resolve("app"), "build.gradle")
        wrapper(root.resolve("nested"))
        assertEquals(listOf("Gradle wrapper in .", "Gradle wrapper in nested"), texts())
    }

    @Test
    fun `the wrapper jar is part of the fingerprint`() {
        wrapper(root)
        val without = check.check(root)!!.single().fingerprint
        root.resolve("gradle/wrapper/gradle-wrapper.jar").writeText("jar")
        val with = check.check(root)!!.single().fingerprint
        assertNotEquals(without, with)
        root.resolve("gradle/wrapper/gradle-wrapper.jar").writeText("other jar")
        assertNotEquals(with, check.check(root)!!.single().fingerprint)
    }

    @Test
    fun `a walk that reaches the folder cap cannot be checked`() {
        (1..5).forEach { scripts(root.resolve("d$it"), "README") }
        assertNull(GradleTrustCheck(3).check(root))
        assertEquals(emptyList(), GradleTrustCheck(6).check(root))
    }

    @Test
    fun `build scripts in a parent of a wrapper are separate from it`() {
        scripts(root, "build.gradle")
        wrapper(root.resolve("sub"))
        assertEquals(listOf("Gradle build scripts in .", "Gradle wrapper in sub"), texts())
    }

    @Test
    fun `skipped and hidden folders are not searched`() {
        listOf(".git", "build", ".gradle", "node_modules", "out", ".hidden").forEach { wrapper(root.resolve(it)) }
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `the depth is limited`() {
        wrapper(root.resolve("1/2/3/4/5/6/7/8"))
        assertEquals(listOf("Gradle wrapper in 1/2/3/4/5/6/7/8"), texts())
        Files
            .walk(root)
            .sorted(Comparator.reverseOrder())
            .filter { it != root }
            .forEach(Files::delete)
        wrapper(root.resolve("1/2/3/4/5/6/7/8/9"))
        assertEquals(emptyList(), check.check(root))
    }

    @Test
    fun `symbolic links are not followed`() {
        val outside = root.resolve("outside").also { wrapper(it) }
        val project = root.resolve("project").createDirectories()
        Files.createSymbolicLink(project.resolve("link"), outside)
        assertEquals(emptyList(), check.check(project))
    }

    @Test
    fun `an interrupted thread stops the walk, which then cannot be checked`() {
        wrapper(root.resolve("a"))
        Thread.currentThread().interrupt()
        try {
            assertNull(check.check(root))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a folder that cannot be read cannot be checked`() {
        assertNull(check.check(root.resolve("missing")))
    }

    @Test
    fun `the check has the id and name of the Gradle tool`() {
        assertEquals("gradle", check.id)
        assertEquals("Gradle", check.name)
    }
}
