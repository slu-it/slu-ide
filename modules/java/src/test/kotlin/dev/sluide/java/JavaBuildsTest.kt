package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaBuildsTest {
    @TempDir
    lateinit var root: Path

    private fun write(
        relative: String,
        text: String = "",
    ): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    private fun pom(
        relative: String,
        vararg modules: String,
        profileModules: List<String> = emptyList(),
    ): Path {
        val own =
            if (modules.isEmpty()) {
                ""
            } else {
                modules.joinToString(
                    "",
                    "<modules>",
                    "</modules>",
                ) { "<module>$it</module>" }
            }
        val profile =
            if (profileModules.isEmpty()) {
                ""
            } else {
                profileModules.joinToString("", "<profiles><profile><modules>", "</modules></profile></profiles>") {
                    "<module>$it</module>"
                }
            }
        return write(relative, "<project>$own$profile</project>")
    }

    private fun rootOf(
        file: String,
        window: Path = root,
    ) = JavaBuilds.buildRoot(root.resolve(file), window)

    private fun gradle(path: String) = JavaBuild(root.resolve(path).normalize(), BuildTool.GRADLE)

    private fun maven(path: String) = JavaBuild(root.resolve(path).normalize(), BuildTool.MAVEN)

    @Test
    fun `a Gradle multi-project file resolves to the settings folder`() {
        write("settings.gradle.kts")
        write("core/build.gradle.kts")
        write("core/src/main/java/A.java")
        assertEquals(gradle("."), rootOf("core/src/main/java/A.java"))
    }

    @Test
    fun `a Gradle build without settings resolves to its build file folder`() {
        write("app/build.gradle")
        write("app/src/A.java")
        assertEquals(gradle("app"), rootOf("app/src/A.java"))
    }

    @Test
    fun `the nearest settings file wins over an outer one`() {
        write("settings.gradle")
        write("inner/settings.gradle.kts")
        write("inner/app/build.gradle")
        assertEquals(gradle("inner"), rootOf("inner/app/A.java"))
    }

    @Test
    fun `settings win over a nearer build file among Gradle candidates`() {
        write("app/build.gradle")
        write("settings.gradle")
        assertEquals(gradle("."), rootOf("app/A.java"))
    }

    @Test
    fun `a hidden build file under a covered settings file belongs to the outer root`() {
        write("settings.gradle")
        write(".h/build.gradle")
        assertEquals(gradle("."), rootOf(".h/A.java"))
    }

    @Test
    fun `a Maven reactor two levels deep resolves to the top`() {
        pom("pom.xml", "services")
        pom("services/pom.xml", "billing")
        pom("services/billing/pom.xml")
        assertEquals(maven("."), rootOf("services/billing/src/A.java"))
    }

    @Test
    fun `a pom its parent does not list is its own root`() {
        pom("pom.xml", "other")
        pom("child/pom.xml")
        assertEquals(maven("child"), rootOf("child/A.java"))
    }

    @Test
    fun `a module listed with a dot-dot path is normalized before the comparison`() {
        pom("sub/pom.xml", "../sub/m")
        pom("sub/m/pom.xml")
        assertEquals(maven("sub"), rootOf("sub/m/A.java"))
    }

    @Test
    fun `a module listed with a sub directory path resolves to the parent`() {
        pom("pom.xml", "sub/dir")
        pom("sub/dir/pom.xml")
        assertEquals(maven("."), rootOf("sub/dir/A.java"))
    }

    @Test
    fun `a module listed only in a profile resolves to the parent`() {
        pom("pom.xml", profileModules = listOf("m"))
        pom("m/pom.xml")
        assertEquals(maven("."), rootOf("m/A.java"))
    }

    @Test
    fun `covered is the window folder and what is below it without skipped segments`() {
        assertTrue(covered(root, root))
        assertTrue(covered(root, root.resolve("a/b")))
        assertFalse(covered(root, root.resolve("a/build/b")))
        assertFalse(covered(root, root.resolve("a/.git")))
        assertFalse(covered(root.resolve("w"), root))
        assertFalse(covered(root.resolve("w"), root.resolve("w2")))
    }

    @Test
    fun `a walk ends when the visitor says so`() {
        write("a/x")
        write("b/x")
        write("c/x")
        val seen = mutableListOf<String>()
        val complete =
            walkFolders(root) { folder, _ ->
                seen += folder.fileName.toString()
                folder.fileName.toString() != "a"
            }
        assertTrue(complete)
        assertEquals(listOf(root.fileName.toString(), "a"), seen)
    }

    @Test
    fun `a pom eight folders down is a root`() {
        pom("1/2/3/4/5/6/7/8/pom.xml")
        assertEquals(maven("1/2/3/4/5/6/7/8"), rootOf("1/2/3/4/5/6/7/8/A.java"))
    }

    @Test
    fun `a pom nine folders down is loose, not imported`() {
        pom("1/2/3/4/5/6/7/8/9/pom.xml")
        assertNull(rootOf("1/2/3/4/5/6/7/8/9/A.java"))
    }

    @Test
    fun `a pom under an output folder is loose`() {
        pom("out/x/pom.xml")
        assertNull(rootOf("out/x/A.java"))
    }

    @Test
    fun `a pom behind a link to a folder is loose`() {
        pom("real/pom.xml")
        try {
            java.nio.file.Files
                .createSymbolicLink(root.resolve("linked"), root.resolve("real"))
        } catch (e: java.io.IOException) {
            org.junit.jupiter.api.Assumptions
                .assumeTrue(false, "links cannot be made here: $e")
        }
        assertNull(rootOf("linked/A.java"))
        assertEquals(maven("real"), rootOf("real/A.java"))
    }

    @Test
    fun `a reactor module in a skipped folder is loose`() {
        pom("pom.xml", "build")
        pom("build/pom.xml")
        assertNull(rootOf("build/A.java"))
    }

    @Test
    fun `a Gradle build in a hidden folder is loose`() {
        write(".hidden/settings.gradle")
        assertNull(rootOf(".hidden/src/A.java"))
    }

    @Test
    fun `an uncovered build inside a covered one is loose, not the outer root`() {
        write("settings.gradle")
        pom("out/x/pom.xml")
        write(".h/settings.gradle")
        assertNull(rootOf("out/x/A.java"))
        assertNull(rootOf(".h/A.java"))
        assertEquals(gradle("."), rootOf("app/A.java"))
    }

    @Test
    fun `a pom and Gradle files in one folder count as Gradle`() {
        pom("pom.xml")
        write("build.gradle")
        assertEquals(gradle("."), rootOf("A.java"))
    }

    @Test
    fun `a pom beside a build file counts as Gradle although settings sit above`() {
        write("settings.gradle")
        pom("app/pom.xml")
        write("app/build.gradle")
        assertEquals(gradle("."), rootOf("app/A.java"))
    }

    @Test
    fun `a nearer Maven build inside an outer Gradle build wins`() {
        write("settings.gradle")
        pom("tools/pom.xml")
        assertEquals(maven("tools"), rootOf("tools/src/A.java"))
    }

    @Test
    fun `a nearer Gradle build inside an outer Maven build wins`() {
        pom("pom.xml")
        write("tools/build.gradle")
        assertEquals(gradle("tools"), rootOf("tools/A.java"))
    }

    @Test
    fun `a loose file has no root`() {
        assertNull(rootOf("loose/A.java"))
    }

    @Test
    fun `a file outside the window folder has no root`() {
        write("pom.xml")
        val window = root.resolve("w").createDirectories()
        assertNull(JavaBuilds.buildRoot(root.resolve("A.java"), window))
    }

    @Test
    fun `a build file above the window folder is ignored`() {
        write("settings.gradle")
        pom("pom.xml", "w")
        val window = root.resolve("w").createDirectories()
        assertNull(JavaBuilds.buildRoot(window.resolve("A.java"), window))
    }

    @Test
    fun `a Maven climb stops at the window folder`() {
        pom("pom.xml", "w")
        pom("w/pom.xml")
        val window = root.resolve("w")
        assertEquals(maven("w"), JavaBuilds.buildRoot(window.resolve("A.java"), window))
    }

    @Test
    fun `the window folder itself can be the root`() {
        write("build.gradle")
        assertEquals(gradle("."), rootOf("A.java"))
    }

    @Test
    fun `a file under build output belongs to its build`() {
        write("settings.gradle")
        assertEquals(gradle("."), rootOf("app/build/generated/A.java"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project><modules><module>m</module></modules></project>",
            "<!DOCTYPE lolz [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;\">]>" +
                "<project><modules><module>&c;</module></modules></project>",
            "<project><modules><module>m</module>",
            "not xml at all",
            "",
        ],
    )
    fun `a hostile or broken parent pom lists no modules`(content: String) {
        write("pom.xml", content)
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `a pom with deeply nested elements lists no modules and does not throw`() {
        val deep = "<a>".repeat(100_000) + "</a>".repeat(100_000)
        write("pom.xml", "<project>$deep<modules><module>m</module></modules></project>")
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `a pom bigger than one MiB lists no modules`() {
        val padding = "<!-- ${"x".repeat(2 shl 20)} -->"
        write("pom.xml", "<project>$padding<modules><module>m</module></modules></project>")
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `a pom just under one MiB still lists its modules`() {
        val padding = "<!-- ${"x".repeat((1 shl 20) - 200)} -->"
        write("pom.xml", "<project>$padding<modules><module>m</module></modules></project>")
        pom("m/pom.xml")
        assertEquals(maven("."), rootOf("m/A.java"))
    }

    @Test
    fun `a pom of exactly one MiB plus a byte lists no modules`() {
        val head = "<project><modules><module>m</module></modules><!--"
        val tail = "--></project>"
        write("pom.xml", head + "x".repeat((1 shl 20) + 1 - head.length - tail.length) + tail)
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `a pom nested within our limit still lists its modules`() {
        write(
            "pom.xml",
            "<project>" + "<a>".repeat(55) + "</a>".repeat(55) + "<modules><module>m</module></modules></project>",
        )
        pom("m/pom.xml")
        assertEquals(maven("."), rootOf("m/A.java"))
    }

    @Test
    fun `a pom nested deeper than our own limit of 64 lists no modules`() {
        write(
            "pom.xml",
            "<project>" + "<a>".repeat(65) + "</a>".repeat(65) + "<modules><module>m</module></modules></project>",
        )
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `text around a module element is not part of its path`() {
        write("pom.xml", "<project><modules>junk<module>m</module>more</modules></project>")
        pom("m/pom.xml")
        assertEquals(maven("."), rootOf("m/A.java"))
    }

    @Test
    fun `a module entry may name the pom file`() {
        pom("pom.xml", "sub/pom.xml")
        pom("sub/pom.xml")
        assertEquals(maven("."), rootOf("sub/A.java"))
    }

    @Test
    fun `a module element outside modules does not list a module`() {
        write("pom.xml", "<project><dependencies><module>m</module></dependencies></project>")
        pom("m/pom.xml")
        assertEquals(maven("m"), rootOf("m/A.java"))
    }

    @Test
    fun `kotlin sources in the module folder count`() {
        write("settings.gradle")
        write("app/build.gradle")
        write("app/src/main/kotlin/K.kt")
        assertTrue(JavaBuilds.kotlinNear(root.resolve("app/src/main/java/A.java"), root))
    }

    @Test
    fun `kotlin sources only in the root count`() {
        write("settings.gradle")
        write("app/build.gradle")
        write("src/test/kotlin/K.kt")
        assertTrue(JavaBuilds.kotlinNear(root.resolve("app/src/main/java/A.java"), root))
    }

    @Test
    fun `a build file with the kotlin plugin counts`() {
        write("settings.gradle")
        write("app/build.gradle.kts", "plugins { kotlin(\"jvm\") }")
        assertTrue(JavaBuilds.kotlinNear(root.resolve("app/src/A.java"), root))
    }

    @Test
    fun `a pom with the kotlin plugin counts`() {
        pom("pom.xml")
        write("pom.xml", "<project><groupId>org.jetbrains.kotlin</groupId></project>")
        assertTrue(JavaBuilds.kotlinNear(root.resolve("src/A.java"), root))
    }

    @Test
    fun `no kotlin anywhere is not near`() {
        write("settings.gradle")
        write("app/build.gradle", "plugins { id 'java' }")
        write("app/src/main/java/A.java")
        write("other/src/main/kotlin/K.kt")
        assertFalse(JavaBuilds.kotlinNear(root.resolve("app/src/main/java/A.java"), root))
    }

    @Test
    fun `lombok in a module build file counts`() {
        write("app/build.gradle.kts", "dependencies { compileOnly(\"org.projectlombok:lombok:1.18\") }")
        assertTrue(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok in the version catalog counts`() {
        write("gradle/libs.versions.toml", "[libraries]\nlombok = { module = \"org.projectlombok:lombok\" }")
        assertTrue(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok in a pom counts`() {
        write("m/pom.xml", "<project><artifactId>lombok</artifactId></project>")
        assertTrue(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok found early stays found when later folders have none`() {
        write("pom.xml", "<project>lombok</project>")
        write("a/pom.xml", "<project/>")
        write("b/pom.xml", "<project/>")
        assertTrue(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok behind a link to a folder is not counted`() {
        val outside = root.resolveSibling("${root.fileName}-outside").also { it.createDirectories() }
        try {
            outside.resolve("pom.xml").writeText("lombok")
            java.nio.file.Files
                .createSymbolicLink(root.resolve("linked"), outside)
            assertFalse(JavaBuilds.usesLombok(root))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no lombok anywhere is not used`() {
        write("pom.xml", "<project/>")
        write("README.md", "lombok")
        assertFalse(JavaBuilds.usesLombok(root))
    }

    @ParameterizedTest
    @ValueSource(strings = ["build", "target", "bin", "out", "node_modules", ".hidden"])
    fun `lombok in a skipped folder is not counted`(folder: String) {
        write("$folder/pom.xml", "<project>lombok</project>")
        assertFalse(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok deeper than eight folders is not counted`() {
        write("1/2/3/4/5/6/7/8/pom.xml", "lombok")
        assertTrue(JavaBuilds.usesLombok(root))
        write("1/2/3/4/5/6/7/8/9/pom.xml", "lombok")
        root.resolve("1/2/3/4/5/6/7/8/pom.xml").writeText("")
        assertFalse(JavaBuilds.usesLombok(root))
    }

    @Test
    fun `lombok past the first mebibyte of a build file is not seen`() {
        write("pom.xml", " ".repeat(1 shl 20) + "lombok")
        assertFalse(JavaBuilds.usesLombok(root))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml",
            "libs.versions.toml", ".mvn/extensions.xml", ".mvn/maven.config",
        ],
    )
    fun `build files are recognized by name`(relative: String) {
        assertTrue(JavaBuilds.isBuildFile(root.resolve(relative)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["A.java", "README.md", "extensions.xml", "maven.config", "pom.xml.bak", "my.gradle"])
    fun `other files are not build files`(relative: String) {
        assertFalse(JavaBuilds.isBuildFile(root.resolve(relative)))
    }
}
