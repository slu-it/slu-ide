package dev.sluide.plantuml.engine

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import net.sourceforge.plantuml.TitledDiagram
import net.sourceforge.plantuml.cli.GlobalConfig
import net.sourceforge.plantuml.cli.GlobalConfigKey
import net.sourceforge.plantuml.core.InstallationRequirement
import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment
import net.sourceforge.plantuml.error.PSystemError
import net.sourceforge.plantuml.klimt.color.ColorMapper
import net.sourceforge.plantuml.preproc.Defines
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import net.sourceforge.plantuml.utils.LineLocation
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import net.sourceforge.plantuml.core.Diagram as PlantUmlDiagram

/**
 * Draws one PlantUML source synchronously, on the caller's thread (the render thread of `PlantUml`, one at a time:
 * PlantUML keeps global state, here the `dot` path).
 */
internal object Drawing {
    // SANDBOX refuses every local file and every URL and keeps the bundled stdlib (ADR 0193).
    init {
        enterSandbox()
        // PlantUML's own wait for a dot that closed its output; the watchdog covers one that never does.
        GlobalConfig.getInstance().put(GlobalConfigKey.TIMEOUT_MS, 15_000L)
    }

    /** The location name PlantUML gives the source string ([SourceStringReader] passes it). */
    private const val SOURCE = "string"

    /** Larger sources are not parsed: a 100,000-line sequence diagram took 1 GB of heap. */
    private const val MAX_KIB = 512

    private val log = System.getLogger(Drawing::class.java.name)

    private val light = FileFormatOption(FileFormat.SVG)
    private val dark = light.withColorMapper(ColorMapper.DARK_MODE)

    /**
     * Puts PlantUML into its SANDBOX profile. PlantUML keeps the profile it read first in a static; a command-line
     * property comes before system properties and the environment and makes it read the profile again.
     */
    internal fun enterSandbox() {
        SecurityUtils.setCommandLineProperty("PLANTUML_SECURITY_PROFILE", "SANDBOX")
    }

    /**
     * Every page of every block of [source], errors numbered from [firstLine]; [dot] is Graphviz's `dot`, `null` for
     * the built-in layout. [givenUp] is asked before every export: once it says yes, the draw stops.
     */
    fun draw(
        source: String,
        firstLine: Int,
        dot: Path?,
        givenUp: () -> Boolean = { false },
    ): Rendering {
        val profile = SecurityUtils.getSecurityProfile()
        if (profile != SecurityProfile.SANDBOX) {
            log.log(System.Logger.Level.WARNING, "PlantUML is in its $profile profile, not SANDBOX: nothing is drawn")
            return Rendering(listOf(Diagram.Failed("PlantUML is not in its sandbox", null)), false)
        }
        // A char is at most 3 UTF-8 bytes (a surrogate pair 4 for 2 chars): short sources need no copy to measure.
        if (source.length * 3L > MAX_KIB * 1024) {
            val bytes = source.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_KIB * 1024) {
                val kib = (bytes + 1023) / 1024
                return Rendering(
                    listOf(Diagram.Failed("Too large to draw: $kib KiB, at most $MAX_KIB KiB", null)),
                    false,
                )
            }
        }
        // PlantUML keeps one dot path for the JVM: each render sets its engine's own.
        if (dot != null) GraphvizRuntimeEnvironment.getInstance().setDotExecutable(dot.toString())
        // No current folder: under SANDBOX no local file is read anyway (ADR 0193).
        val blocks = SourceStringReader(Defines.createEmpty(), source, Charsets.UTF_8, emptyList(), null).blocks
        val diagrams = mutableListOf<Diagram>()
        var builtInLayout = false
        for (block in blocks) {
            val diagram = block.diagram
            if (diagram is PSystemError) {
                diagrams += failure(diagram.firstError.error, diagram.lineLocation, firstLine)
            } else {
                if (dot == null) {
                    // Read before Smetana is forced: forcing it turns the requirement to NONE.
                    if (diagram.installationRequirement == InstallationRequirement.GRAPHVIZ_ENGINE_REQUIRED) {
                        builtInLayout = true
                    }
                    (diagram as? TitledDiagram)?.setUseSmetana(true)
                }
                for (page in 0 until diagram.nbImages) {
                    // An export may start a dot: a draw that was given up starts none after the watchdog's kill.
                    if (givenUp()) return Rendering(diagrams, builtInLayout)
                    val lightSvg = svg(diagram, page, light)
                    if (givenUp()) return Rendering(diagrams, builtInLayout)
                    diagrams += Diagram.Drawn(lightSvg, svg(diagram, page, dark))
                }
            }
        }
        return Rendering(diagrams, builtInLayout)
    }

    private fun svg(
        diagram: PlantUmlDiagram,
        page: Int,
        format: FileFormatOption,
    ): String {
        val out = ByteArrayOutputStream()
        diagram.exportDiagram(out, page, format)
        return out.toString(Charsets.UTF_8)
    }

    /**
     * PlantUML's error [message] at [location], the innermost location (inside an include, named in the message). The
     * outermost is in the source, 0-based over the whole source, unless the error is inside a stdlib file, whose
     * location has no parent (measured with `<C4/C4_Container>`): then no line of the source fits.
     */
    internal fun failure(
        message: String,
        location: LineLocation?,
        firstLine: Int,
    ): Diagram.Failed {
        val outermost = generateSequence(location, LineLocation::getParent).lastOrNull()
        val line = outermost?.takeIf { it.description == SOURCE }?.let { firstLine + it.position }
        val inside =
            location
                ?.takeUnless {
                    it.description == SOURCE
                }?.let { " (in ${it.description}, line ${it.position + 1})" }
        return Diagram.Failed(message + (inside ?: ""), line)
    }
}
