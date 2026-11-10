package dev.sluide.typescript

import org.eclipse.lsp4j.ConfigurationItem
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypeScriptSpecTest {
    @TempDir
    lateinit var tempDir: Path

    private val dir: Path get() = tempDir.toRealPath()
    private val fakes by lazy { TypeScriptFakes(tempDir) }
    private val installer by lazy { fakes.installer() }

    // The walk goes to the filesystem root unless a file is found on the way; tests stop at the temp folder.
    private val spec by lazy { TypeScriptSpec(installer, fakes.settings, dir) }

    private fun write(
        path: String,
        text: String = "",
    ): Path {
        val file = dir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    private fun installed(
        folder: String,
        version: String,
    ) = write("$folder/node_modules/typescript/package.json", """{"version":"$version"}""")

    private fun declare(
        folder: String,
        range: String,
    ) = write("$folder/package.json", """{"devDependencies":{"typescript":"$range"}}""")

    private fun installServer() {
        installer.install(null)
        fakes.runQueued()
    }

    @ParameterizedTest
    @CsvSource(
        "a.ts, typescript",
        "a.mts, typescript",
        "a.cts, typescript",
        "a.d.ts, typescript",
        "a.TSX, typescriptreact",
        "a.js, javascript",
        "a.mjs, javascript",
        "a.cjs, javascript",
        "a.JSX, javascriptreact",
    )
    fun `it handles TypeScript and JavaScript sources with their language ids`(
        name: String,
        languageId: String,
    ) {
        assertTrue(spec.matches(Path.of("/p", name)))
        assertEquals(languageId, spec.languageId(Path.of("/p", name)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a.json", "a.py", "ts", "a.ts.map", "a.vue", "script"])
    fun `it ignores other files`(name: String) {
        assertFalse(spec.matches(Path.of("/p", name)))
    }

    @Test
    fun `it gates documents and needs no trust`() {
        assertTrue(spec.gatesDocuments)
        assertFalse(spec.runsProjectCode)
        assertEquals("typescript", spec.id)
        assertEquals("TypeScript", spec.name)
    }

    @Test
    fun `an installed TypeScript 6 or later is served and everything consulted is watched`() {
        installed("apps/a", "7.0.2")
        write("apps/a/node_modules/@types/node/index.d.ts")
        write("apps/a/node_modules/@scope/x/index.js")
        write("apps/a/node_modules/left-pad/index.js")
        val file = write("apps/a/src/x.ts")

        val gate = spec.gate(file, dir)

        assertTrue(gate.served)
        assertNull(gate.reason)
        val a = dir.resolve("apps/a")
        assertEquals(
            listOf(
                a.resolve("src"),
                a.resolve("src/node_modules"),
                a.resolve("src/node_modules/typescript"),
                a,
                a.resolve("node_modules"),
                a.resolve("node_modules/typescript"),
                a.resolve("node_modules/@scope"),
                a.resolve("node_modules/@types"),
            ),
            gate.watched,
        )
    }

    @Test
    fun `a loose file is served`() {
        val gate = spec.gate(write("loose/x.js"), dir)

        assertTrue(gate.served)
        assertNull(gate.reason)
        assertTrue(dir in gate.watched)
        assertTrue(dir.resolve("node_modules") in gate.watched)
    }

    @Test
    fun `installed TypeScript 5 is not served`() {
        installed("apps/c", "5.4.5")

        val gate = spec.gate(write("apps/c/x.ts"), dir)

        assertFalse(gate.served)
        val reason = checkNotNull(gate.reason)
        assertEquals(dir.resolve("apps/c"), reason.folder)
        assertEquals(
            "This project uses TypeScript 5.4.5 (apps/c/node_modules). Code intelligence needs TypeScript 6 or later.",
            reason.message,
        )
        assertNull(reason.action)
    }

    @Test
    fun `a declared TypeScript 5 is not served`() {
        declare("apps/c", "^5.4.5")

        val reason = checkNotNull(spec.gate(write("apps/c/x.ts"), dir).reason)

        assertEquals(dir.resolve("apps/c"), reason.folder)
        assertEquals(
            "This project declares TypeScript ^5.4.5 in apps/c/package.json. " +
                "Code intelligence needs TypeScript 6 or later.",
            reason.message,
        )
    }

    @Test
    fun `a declared TypeScript 6 through a catalog is served`() {
        write("pnpm-workspace.yaml", "catalog:\n  typescript: ^6.0.0\n")
        declare("apps/b", "catalog:")

        val gate = spec.gate(write("apps/b/x.ts"), dir)

        assertTrue(gate.served)
    }

    @Test
    fun `a catalog entry that is too old names the catalog file`() {
        write("pnpm-workspace.yaml", "catalog:\n  typescript: ^5.0.0\n")
        declare("apps/c", "catalog:")

        val reason = checkNotNull(spec.gate(write("apps/c/x.ts"), dir).reason)

        assertEquals(
            "This project declares TypeScript ^5.0.0 in pnpm-workspace.yaml. " +
                "Code intelligence needs TypeScript 6 or later.",
            reason.message,
        )
    }

    @Test
    fun `an undecidable range is not served and says so`() {
        declare("apps/c", "latest")

        val reason = checkNotNull(spec.gate(write("apps/c/x.ts"), dir).reason)

        assertEquals(dir.resolve("apps/c"), reason.folder)
        assertEquals(
            "Can't tell which TypeScript apps/c uses (\"latest\" in apps/c/package.json). " +
                "Code intelligence needs TypeScript 6 or later; pin a range such as ^7.0.0.",
            reason.message,
        )
    }

    @Test
    fun `a catalog without a typescript entry names the range and the catalog`() {
        write("pnpm-workspace.yaml", "catalog:\n  react: ^19\n")
        declare("apps/c", "catalog:")

        val gate = spec.gate(write("apps/c/x.ts"), dir)

        assertEquals(
            "Can't tell which TypeScript apps/c uses (\"catalog:\" in apps/c/package.json, " +
                "no typescript entry in pnpm-workspace.yaml). " +
                "Code intelligence needs TypeScript 6 or later; pin a range such as ^7.0.0.",
            gate.reason?.message,
        )
        assertTrue(dir in gate.watched)
    }

    @Test
    fun `an unparsable installed version is undecidable`() {
        installed("apps/c", "next")

        val gate = spec.gate(write("apps/c/x.ts"), dir)

        assertFalse(gate.served)
        assertTrue(checkNotNull(gate.reason).message.startsWith("Can't tell which TypeScript apps/c uses (\"next\""))
    }

    @Test
    fun `a Deno project is not served`() {
        write("deno/deno.json", "{}")

        val reason = checkNotNull(spec.gate(write("deno/x.ts"), dir).reason)

        assertEquals(dir.resolve("deno"), reason.folder)
        assertEquals(
            "This is a Deno project (deno.json); Thoreau doesn't run Deno's language server.",
            reason.message,
        )
    }

    @Test
    fun `the project folder itself is named by its name and outside paths are absolute`() {
        val project = dir.resolve("work")
        declare("work", "latest")
        val inside = checkNotNull(spec.gate(write("work/x.ts"), project).reason)
        assertTrue(inside.message.startsWith("Can't tell which TypeScript work uses (\"latest\" in package.json)"))

        val outside = checkNotNull(spec.gate(write("work/x.ts"), dir.resolve("other")).reason)
        val json = project.resolve("package.json")
        val home = Path.of(System.getProperty("user.home"))
        // The temporary folder may lie under the home folder, where paths are shortened to ~.
        val shown = if (json.startsWith(home)) "~/" + home.relativize(json) else json.toString()
        assertTrue(outside.message.contains("in $shown)"), outside.message)
    }

    @ParameterizedTest
    @CsvSource(
        "package.json, true",
        "pnpm-workspace.yaml, true",
        "deno.json, true",
        "deno.jsonc, true",
        "node_modules, true",
        "tsconfig.json, false",
        "x.ts, false",
    )
    fun `package and workspace files affect the gate`(
        name: String,
        affects: Boolean,
    ) {
        assertEquals(affects, spec.affectsWorkspaceFolders(dir.resolve("a").resolve(name)))
    }

    @ParameterizedTest
    @CsvSource(
        "tsconfig.json, true",
        "tsconfig.base.json, true",
        "jsconfig.json, true",
        "jsconfig.app.json, true",
        "package.json, false",
        "tsconfig.ts, false",
        "tsconfig.jsonc, false",
    )
    fun `tsconfig and jsconfig files keep their diagnostics`(
        name: String,
        keeps: Boolean,
    ) {
        assertEquals(keeps, spec.keepsDiagnostics(dir.resolve(name)))
    }

    @Test
    fun `the nearest config wins, tsconfig before jsconfig, and the search stops at the project folder`() {
        write("tsconfig.json")
        write("a/jsconfig.json")
        write("a/b/tsconfig.json")
        write("a/b/jsconfig.json")
        val project = dir.resolve("a")

        assertEquals(listOf(dir.resolve("a/b/tsconfig.json")), spec.configFiles(write("a/b/c/x.ts"), project))
        assertEquals(listOf(dir.resolve("a/jsconfig.json")), spec.configFiles(write("a/y.ts"), project))
        assertEquals(emptyList(), spec.configFiles(write("z/q.ts"), project))
        assertEquals(emptyList(), spec.configFiles(write("a/other.ts"), dir.resolve("a/b")))
    }

    @Test
    fun `type acquisition is off for both sections and other sections get an empty object`() {
        val off =
            mapOf(
                "tsserver" to mapOf("automaticTypeAcquisition" to mapOf("enabled" to false)),
                "disableAutomaticTypeAcquisition" to true,
            )

        for (section in listOf("js/ts", "typescript")) {
            assertEquals(off, spec.configuration(ConfigurationItem().apply { this.section = section }))
        }
        assertEquals(emptyMap<String, Any?>(), spec.configuration(ConfigurationItem().apply { section = "editor" }))
        assertEquals(emptyMap<String, Any?>(), spec.configuration(ConfigurationItem()))
    }

    @Test
    fun `an installed project shows its version and where TypeScript was found`() {
        installServer()
        val ts = installed("apps/a", "7.0.1")

        val status = checkNotNull(spec.workspaceStatus(write("apps/a/x.ts"), null, dir))

        assertEquals("TypeScript project: apps/a · TypeScript 7.0.1", status.text)
        assertEquals(dir.resolve("apps/a"), status.folder)
        assertEquals(
            "TypeScript 7.0.1 from ${ts.parent}\nServer: TypeScript 7.0.2 (Thoreau's download)",
            status.tooltip,
        )
    }

    @Test
    fun `a project newer than the server says so`() {
        installServer()
        installed("apps/a", "7.1.0")

        val status = checkNotNull(spec.workspaceStatus(write("apps/a/x.ts"), null, dir))

        assertEquals("TypeScript project: apps/a · TypeScript 7.1.0 · newer than the server", status.text)
    }

    @Test
    fun `a declared project shows its range as not installed`() {
        installServer()
        declare("apps/b", "~6.0.3")

        val status = checkNotNull(spec.workspaceStatus(write("apps/b/x.ts"), null, dir))

        assertEquals("TypeScript project: apps/b · TypeScript ~6.0.3 (not installed)", status.text)
        assertEquals(dir.resolve("apps/b"), status.folder)
    }

    @Test
    fun `a loose file shows the server version and is not clickable`() {
        installServer()

        val status = checkNotNull(spec.workspaceStatus(write("loose/x.js"), null, dir))

        assertEquals("No TypeScript project · server 7.0.2", status.text)
        assertNull(status.folder)
    }

    @Test
    fun `a custom server is named as such`() {
        installServer()
        val custom = write("bin/tsc")
        custom.toFile().setExecutable(true)
        fakes.settings.apply(custom.toString())

        val status = checkNotNull(spec.workspaceStatus(write("loose/x.js"), null, dir))

        assertEquals("No TypeScript project · custom server", status.text)
    }

    @Test
    fun `a document that is not served has no status`() {
        declare("apps/c", "^5.0.0")

        assertNull(spec.workspaceStatus(write("apps/c/x.ts"), null, dir))
    }

    @ParameterizedTest
    @CsvSource(
        "foo, true",
        "\$el, true",
        "_x1, true",
        "ünï, true",
        "a\u200Db, true",
        "#secret, true",
        "#\$x, true",
        "class, true",
        "await, true",
        "let, true",
        "1abc, false",
        "foo bar, false",
        "a-b, false",
        "#, false",
        "##a, false",
        "#1a, false",
    )
    fun `identifiers follow the ECMAScript rules, with private names and reserved words as members`(
        name: String,
        valid: Boolean,
    ) {
        assertEquals(valid, spec.isIdentifier(name))
    }

    @Test
    fun `a project without an installed server says so in its tooltip`() {
        val ts = installed("apps/a", "7.0.1")

        val status = checkNotNull(spec.workspaceStatus(write("apps/a/x.ts"), null, dir))

        assertEquals("TypeScript project: apps/a · TypeScript 7.0.1", status.text)
        assertEquals("TypeScript 7.0.1 from ${ts.parent}\nServer: not installed", status.tooltip)
    }

    @Test
    fun `a loose file without a server has no server part`() {
        val status = checkNotNull(spec.workspaceStatus(write("loose/x.js"), null, dir))

        assertEquals("No TypeScript project", status.text)
        assertTrue(status.tooltip.orEmpty().endsWith("Server: not installed"))
    }

    @Test
    fun `a custom server never counts as older than the project`() {
        installServer()
        installed("apps/a", "7.1.0")
        val custom = write("bin/tsc")
        custom.toFile().setExecutable(true)
        fakes.settings.apply(custom.toString())

        val status = checkNotNull(spec.workspaceStatus(write("apps/a/x.ts"), null, dir))

        assertEquals("TypeScript project: apps/a · TypeScript 7.1.0", status.text)
        assertTrue(status.tooltip.orEmpty().endsWith("Server: custom server (custom path)"))
    }

    @Test
    fun `the tooltip of a declared project names where it is declared, a catalog entry its catalog`() {
        installServer()
        val json = declare("apps/b", "~6.0.3")
        write("pnpm-workspace.yaml", "catalog:\n  typescript: ^6.1.0\n")
        declare("apps/c", "catalog:")

        val direct = checkNotNull(spec.workspaceStatus(write("apps/b/x.ts"), null, dir))
        val catalog = checkNotNull(spec.workspaceStatus(write("apps/c/x.ts"), null, dir))

        assertEquals(
            "TypeScript ~6.0.3 declared in $json\nServer: TypeScript 7.0.2 (Thoreau's download)",
            direct.tooltip,
        )
        assertEquals(
            "TypeScript ^6.1.0 declared in ${dir.resolve("pnpm-workspace.yaml")}\n" +
                "Server: TypeScript 7.0.2 (Thoreau's download)",
            catalog.tooltip,
        )
    }

    @Test
    fun `a project above the window folder has no link`() {
        installed(".", "7.0.2")
        val project = dir.resolve("work")

        val status = checkNotNull(spec.workspaceStatus(write("work/x.ts"), null, project))

        assertTrue(status.text.contains("TypeScript 7.0.2"))
        assertNull(status.folder)
    }

    @Test
    fun `a catalog nobody defines says there is no catalog`() {
        declare("apps/c", "catalog:")

        val reason = checkNotNull(spec.gate(write("apps/c/x.ts"), dir).reason)

        assertEquals(
            "Can't tell which TypeScript apps/c uses (\"catalog:\" in apps/c/package.json, no catalog found). " +
                "Code intelligence needs TypeScript 6 or later; pin a range such as ^7.0.0.",
            reason.message,
        )
    }

    @Test
    fun `a catalog entry that is undecidable names its value and the catalog`() {
        write("pnpm-workspace.yaml", "catalog:\n  typescript: latest\n")
        declare("apps/c", "catalog:")

        val reason = checkNotNull(spec.gate(write("apps/c/x.ts"), dir).reason)

        assertEquals(
            "Can't tell which TypeScript apps/c uses (\"catalog:\" in apps/c/package.json, " +
                "\"latest\" in pnpm-workspace.yaml). " +
                "Code intelligence needs TypeScript 6 or later; pin a range such as ^7.0.0.",
            reason.message,
        )
    }

    @Test
    fun `the empty name is no identifier`() {
        assertFalse(spec.isIdentifier(""))
    }
}
