package dev.sluide.plantuml.engine

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import dev.sluide.plantuml.onlyDrawn
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PlantUML runs in its SANDBOX profile (ADR 0193): no local file is read, no URL fetched, the bundled stdlib works.
 * The expected results are what PlantUML 1.2026.10 answered under SANDBOX when the engine was built: its own error
 * texts for includes, imports, sprites, styles and themes, an empty value for `%load_json`, no image for `<img:>`, 0 for
 * `%file_exists`; for URLs neither a connection nor a DNS lookup (traced with strace).
 */
class SandboxTest {
    @TempDir
    lateinit var outside: Path

    /** A folder under the JVM's working directory, which PlantUML resolves relative paths against without a dir. */
    private lateinit var underWorkingDir: Path

    @BeforeEach
    fun files() {
        Files.writeString(outside.resolve("secret.iuml"), "class OUTSIDE_INCLUDE_MARKER\n")
        Files.writeString(outside.resolve("subs.iuml"), "!startsub S\nclass OUTSIDE_SUB_MARKER\n!endsub\n")
        Files.writeString(outside.resolve("data.json"), """{"v": "OUTSIDE_JSON_MARKER"}""")
        Files.writeString(outside.resolve("style.css"), "participant { BackGroundColor #ABCDEF }\n")
        Files.writeString(outside.resolve("puml-theme-local.puml"), "skinparam backgroundColor #ABCDEF\n")
        Files.write(outside.resolve("pic.png"), png())
        ZipOutputStream(Files.newOutputStream(outside.resolve("lib.zip"))).use {
            it.putNextEntry(ZipEntry("zipped.iuml"))
            it.write("class OUTSIDE_ZIP_MARKER\n".toByteArray())
            it.closeEntry()
        }
        underWorkingDir =
            Files.createTempDirectory(Files.createDirectories(workingDir.resolve("build")), "sandbox-test")
        Files.writeString(underWorkingDir.resolve("secret.iuml"), "class WORKDIR_INCLUDE_MARKER\n")
    }

    @AfterEach
    fun removeWorkingDirFiles() {
        underWorkingDir.toFile().deleteRecursively()
    }

    private val workingDir: Path = Path.of("").toAbsolutePath()

    private fun png(): ByteArray =
        ByteArrayOutputStream()
            .also {
                ImageIO.write(BufferedImage(11, 7, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()

    private fun draw(line: String) = Drawing.draw("@startuml\n$line\nAlice -> Bob : after\n@enduml\n", 1, null)

    private fun assertNoMarker(rendering: Rendering) {
        val all = rendering.toString()
        assertFalse("_MARKER" in all, all)
        assertFalse("ABCDEF" in all, all)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refused")
    fun `a local file construct is refused with PlantUML's error on its line`(
        name: String,
        line: (SandboxTest) -> String,
        message: (SandboxTest) -> String,
    ) {
        val rendering = draw(line(this))

        assertEquals(Rendering(listOf(Diagram.Failed(message(this), 2)), false), rendering)
        assertNoMarker(rendering)
    }

    @Test
    fun `load_json of a local file gives an empty value`() {
        val rendering = draw("!\$d = %load_json(\"${outside.resolve("data.json")}\")\nAlice -> Bob : value \$d.v")

        rendering.onlyDrawn()
        assertNoMarker(rendering)
    }

    @Test
    fun `an image from a local file is not embedded`() {
        val rendering = draw("Alice -> Bob : <img:${outside.resolve("pic.png")}>")

        val drawn = rendering.onlyDrawn()
        assertFalse("<image" in drawn.lightSvg || "<image" in drawn.darkSvg, drawn.lightSvg)
    }

    @Test
    fun `file_exists says 0 for an existing file`() {
        val rendering = draw("Alice -> Bob : EXISTS%file_exists(\"${outside.resolve("data.json")}\")END")

        val drawn = rendering.onlyDrawn()
        assertTrue("EXISTS0END" in drawn.lightSvg, drawn.lightSvg)
    }

    @Test
    fun `no URL form reaches the network, not even one on PlantUML's own allow-list`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val connections = AtomicInteger()
            Thread {
                while (true) {
                    try {
                        // Counted before the close that ends the client's fetch.
                        server.accept().use { connections.incrementAndGet() }
                    } catch (_: IOException) {
                        return@Thread
                    }
                }
            }.apply { isDaemon = true }.start()
            val url = "http://127.0.0.1:${server.localPort}"
            // Outside SANDBOX PlantUML refuses IP addresses and loopback hosts, but fetches a URL on this allow-list
            // (SURL.isUrlOk): only SANDBOX refuses it.
            System.setProperty("plantuml.allowlist.url", "$url/")
            val rendering =
                try {
                    Drawing.draw(
                        listOf(
                            "!includeurl $url/includeurl",
                            "!include $url/include",
                            "!include_once HTTP://127.0.0.1:${server.localPort}/once",
                            "!theme remote from $url/themes",
                            "!import $url/lib.zip",
                            "sprite \$s $url/sprite.png",
                            "Alice -> Bob : <img:$url/img.png>",
                            "!\$j = %load_json(\"$url/data.json\")",
                            "class A [[$url/link]]",
                            "<style file=$url/style.css>",
                        ).joinToString("") { "@startuml\n$it\nclass After\n@enduml\n" },
                        1,
                        null,
                    )
                } finally {
                    System.clearProperty("plantuml.allowlist.url")
                }

            assertEquals(Diagram.Failed("Cannot open URL", 2), rendering.diagrams.firstOrNull(), rendering.toString())
            // A fetch only returns once the server accepted and closed the connection, so the count is final here.
            assertEquals(0, connections.get())
        }
    }

    @Test
    fun `entering the sandbox replaces a profile PlantUML already read`() {
        // PlantUML keeps the profile it read first in a static; anything that read it before the engine's setup must
        // not leave it outside SANDBOX.
        try {
            SecurityUtils.setCommandLineProperty("PLANTUML_SECURITY_PROFILE", "LEGACY")
            assertEquals(SecurityProfile.LEGACY, SecurityUtils.getSecurityProfile())

            Drawing.enterSandbox()

            assertEquals(SecurityProfile.SANDBOX, SecurityUtils.getSecurityProfile())
        } finally {
            Drawing.enterSandbox()
        }
    }

    @Test
    fun `the stdlib's C4 macros work`() {
        val rendering = draw("!include <C4/C4_Container>\nContainer(api, \"STDLIB_CFOUR\", \"Kotlin\")")

        assertTrue("STDLIB_CFOUR" in rendering.onlyDrawn().lightSvg)
    }

    @Test
    fun `the stdlib's icon sprites are drawn`() {
        val rendering = draw("!include <tupadr3/font-awesome/server>\nAlice -> Bob : <\$server> STDLIB_SPRITE")

        val drawn = rendering.onlyDrawn()
        assertTrue("STDLIB_SPRITE" in drawn.lightSvg && "<image" in drawn.lightSvg, drawn.lightSvg)
    }

    @Test
    fun `the stdlib's themes apply`() {
        val plain = draw("Alice -> Bob : THEMED")
        val themed = draw("!theme cerulean\nAlice -> Bob : THEMED")

        val drawn = themed.onlyDrawn()
        assertTrue("THEMED" in drawn.lightSvg)
        assertTrue(drawn.lightSvg != plain.onlyDrawn().lightSvg)
    }

    @Test
    fun `an image from a data URI is embedded`() {
        val data = Base64.getEncoder().encodeToString(png())
        val rendering = draw("Alice -> Bob : <img:data:image/png;base64,$data>")

        assertTrue("<image" in rendering.onlyDrawn().lightSvg)
    }

    companion object {
        private fun case(
            name: String,
            line: (SandboxTest) -> String,
            message: (SandboxTest) -> String,
        ) = Arguments.of(name, line, message)

        @JvmStatic
        fun refused(): List<Arguments> =
            listOf(
                case("include relative", { "!include ${it.relativeInWorkingDir()}" }, {
                    "cannot include ${it.relativeInWorkingDir()}"
                }),
                case("include absolute", { "!include ${it.outside.resolve("secret.iuml")}" }, {
                    "cannot include ${it.outside.resolve("secret.iuml")}"
                }),
                case(
                    "include ../",
                    { "!include ${it.relativeOutside()}" },
                    { "cannot include ${it.relativeOutside()}" },
                ),
                case("includesub", { "!includesub ${it.outside.resolve("subs.iuml")}!S" }, {
                    "cannot include ${it.outside.resolve("subs.iuml")}!S"
                }),
                case(
                    "import",
                    { "!import ${it.outside.resolve("lib.zip")}\n!include zipped.iuml" },
                    { "Cannot import" },
                ),
                case("sprite", { "sprite \$s ${it.outside.resolve("pic.png")}" }, {
                    "Cannot read: ${it.outside.resolve("pic.png")} (Assumed diagram type: sequence)"
                }),
                case("style file", { "<style file=${it.outside.resolve("style.css")}>" }, {
                    "Cannot read: ${it.outside.resolve("style.css")} (Assumed diagram type: sequence)"
                }),
                case("theme from a folder", { "!theme local from ${it.outside}" }, {
                    "Cannot load theme local in ${it.outside}"
                }),
            )
    }

    private fun relativeInWorkingDir() = workingDir.relativize(underWorkingDir.resolve("secret.iuml")).toString()

    private fun relativeOutside() =
        workingDir.relativize(outside.resolve("secret.iuml")).toString().also {
            check(it.startsWith("../")) { it }
        }
}
