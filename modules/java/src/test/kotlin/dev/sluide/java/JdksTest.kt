package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdksTest {
    @TempDir
    lateinit var root: Path

    private fun jdk(
        home: Path,
        version: String = "21.0.1",
    ): Path {
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        home.resolve("release").writeText("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"$version\"\nOS_ARCH=\"x\"\n")
        return home
    }

    @Test
    fun `a folder with bin java and a release file is a JDK`() {
        val home = jdk(root.resolve("jdk"), "17.0.2")
        assertEquals(Jdk(home, "17.0.2", 17), Jdks.read(home))
    }

    @Test
    fun `no bin java is no JDK`() {
        val home = jdk(root.resolve("jdk"))
        Files.delete(home.resolve("bin/java"))
        assertNull(Jdks.read(home))
    }

    @Test
    fun `a directory named java is no bin java`() {
        val home = jdk(root.resolve("jdk"))
        Files.delete(home.resolve("bin/java"))
        home.resolve("bin/java").createDirectories()
        assertNull(Jdks.read(home))
    }

    @Test
    fun `no release file is no JDK`() {
        val home = jdk(root.resolve("jdk"))
        Files.delete(home.resolve("release"))
        assertNull(Jdks.read(home))
    }

    @ParameterizedTest
    @CsvSource("17.0.2,17", "21,21", "1.8.0_392,8", "1,1", "25-ea,25", "11.0.21+9,11")
    fun `the major version is read from the release file`(
        version: String,
        major: Int,
    ) {
        assertEquals(major, Jdks.read(jdk(root.resolve("jdk"), version))?.major)
    }

    @ParameterizedTest
    @CsvSource("abc", "''", "-1")
    fun `an unparsable version is no JDK`(version: String) {
        assertNull(Jdks.read(jdk(root.resolve("jdk"), version)))
    }

    @Test
    fun `a release file without a version is no JDK`() {
        val home = jdk(root.resolve("jdk"))
        home.resolve("release").writeText("IMPLEMENTOR=\"x\"\n")
        assertNull(Jdks.read(home))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
        21.0.1    | 'MODULES="java.base jdk.compiler jdk.jshell jdk.jdi"' | true
        21.0.1    | 'MODULES="jdk.jshell"'                               | true
        25        | 'MODULES="java.base java.desktop jdk.zipfs"'         | false
        25        | 'MODULES="java.base jdk.jshellx xjdk.jshell"'        | false
        11.0.2    | ''                                                   | true
        9         | ''                                                   | true
        1.8.0_392 | ''                                                   | false
        1.8.0_392 | 'MODULES="jdk.jshell"'                               | false""",
    )
    fun `a JDK has JShell from 9 on when its release file lists jdk jshell or no modules at all`(
        version: String,
        modules: String,
        expected: Boolean,
    ) {
        val home = root.resolve("jdk")
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n$modules\nOS_ARCH=\"x\"\n")
        val jdk = Jdks.read(home)
        assertEquals(expected, jdk?.let(Jdks::hasJShell))
    }

    @Test
    fun `a JDK whose release file is gone by now has no JShell`() {
        val home = jdk(root.resolve("jdk"))
        val jdk = Jdks.read(home)
        Files.delete(home.resolve("release"))
        assertEquals(false, jdk?.let(Jdks::hasJShell))
    }

    @Test
    fun `the macOS layouts are found`() {
        val user = root.resolve("user")
        val system = root.resolve("Library/Java/JavaVirtualMachines")
        val a = jdk(system.resolve("temurin-21.jdk/Contents/Home"), "21.0.1")
        val b = jdk(user.resolve("Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"), "17.0.9")
        val c = jdk(user.resolve(".gradle/jdks/azul-11/Contents/Home"), "11.0.2")
        assertEquals(
            listOf(Jdk(a, "21.0.1", 21), Jdk(b, "17.0.9", 17), Jdk(c, "11.0.2", 11)),
            Jdks.installed(user, macOs = true, systemFolders = listOf(system)),
        )
    }

    @Test
    fun `the macOS user folder is not searched elsewhere`() {
        val user = root.resolve("user")
        jdk(user.resolve("Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"))
        jdk(user.resolve("Library/Java/JavaVirtualMachines/direct"))
        assertEquals(emptyList(), Jdks.installed(user, macOs = false, systemFolders = emptyList()))
    }

    @Test
    fun `the Contents Home layout is only read on macOS`() {
        val user = root.resolve("user")
        jdk(user.resolve(".gradle/jdks/azul-11/Contents/Home"))
        assertEquals(emptyList(), Jdks.installed(user, macOs = false, systemFolders = emptyList()))
    }

    @Test
    fun `the user folders of every platform are found`() {
        val user = root.resolve("user")
        val homes =
            listOf(".sdkman/candidates/java/21-tem", ".gradle/jdks/j", ".jdks/k", ".asdf/installs/java/m").map {
                jdk(user.resolve(it))
            }
        val system = jdk(root.resolve("jvm/java-8"), "1.8.0_1")
        assertEquals(
            (homes + listOf(system)).map { it.toString() }.sorted(),
            Jdks
                .installed(
                    user,
                    macOs = false,
                    systemFolders = listOf(root.resolve("jvm")),
                ).map { it.home.toString() }
                .sorted(),
        )
    }

    @Test
    fun `the sdkman current link is no duplicate`() {
        val user = root.resolve("user")
        val real = jdk(user.resolve(".sdkman/candidates/java/21.0.1-tem"))
        Files.createSymbolicLink(user.resolve(".sdkman/candidates/java/current"), real.fileName)
        assertEquals(listOf(real), Jdks.installed(user, macOs = false, systemFolders = emptyList()).map { it.home })
    }

    @Test
    fun `newest major first, then by path`() {
        val user = root.resolve("user")
        val b = jdk(user.resolve(".jdks/b"), "17.0.1")
        val a = jdk(user.resolve(".jdks/a"), "17.0.1")
        val c = jdk(user.resolve(".jdks/c"), "25")
        val d = jdk(user.resolve(".jdks/d"), "8")
        assertEquals(
            listOf(c, a, b, d),
            Jdks.installed(user, macOs = false, systemFolders = emptyList()).map { it.home },
        )
    }

    @Test
    fun `folders that are no JDK and plain files are left out`() {
        val user = root.resolve("user")
        user.resolve(".jdks/empty").createDirectories()
        user.resolve(".jdks/file").writeText("x")
        assertEquals(emptyList(), Jdks.installed(user, macOs = false, systemFolders = emptyList()))
    }
}
