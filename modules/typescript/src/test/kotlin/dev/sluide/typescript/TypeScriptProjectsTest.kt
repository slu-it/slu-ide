package dev.sluide.typescript

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypeScriptProjectsTest {
    @TempDir
    lateinit var tempDir: Path

    // The walk goes to the filesystem root; tests stop at the temp folder so ancestors cannot interfere.
    private val dir: Path get() = tempDir.toRealPath()

    private fun write(
        path: String,
        text: String,
    ): Path {
        val file = dir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    private fun installed(
        folder: String,
        version: String,
    ) = write("$folder/node_modules/typescript/package.json", """{"name":"typescript","version":"$version"}""")

    private fun resolve(path: String) = resolveTypeScript(write(path, ""), stopAt = dir)

    @Test
    fun `installed typescript beats a declaration in the same folder`() {
        write("package.json", """{"devDependencies":{"typescript":"^5.0.0"}}""")
        val json = installed(".", "7.0.2")

        val source = assertIs<TypeScriptSource.Installed>(resolve("src/a.ts"))

        assertEquals("7.0.2", source.version)
        assertEquals(json.normalize(), source.packageJson)
        assertEquals(Verdict.SUPPORTED, verdictOf(source))
    }

    @Test
    fun `the nearest folder wins`() {
        installed(".", "5.9.3")
        installed("packages/app", "7.0.0")

        val source = assertIs<TypeScriptSource.Installed>(resolve("packages/app/src/a.ts"))

        assertEquals("7.0.0", source.version)
    }

    @Test
    fun `a sub-package without a declaration uses the hoisted install of the root`() {
        write("packages/app/package.json", """{"name":"app"}""")
        installed(".", "7.0.0")

        val source = assertIs<TypeScriptSource.Installed>(resolve("packages/app/a.ts"))

        assertEquals("7.0.0", source.version)
    }

    @Test
    fun `a fresh clone has only the declaration`() {
        write("package.json", """{"devDependencies":{"typescript":"~6.0.3"},"dependencies":{"typescript":"^5"}}""")

        val source = assertIs<TypeScriptSource.Declared>(resolve("src/a.ts"))

        assertEquals("~6.0.3", source.range)
        assertEquals("~6.0.3", source.resolvedRange)
        assertEquals(Verdict.SUPPORTED, verdictOf(source))
    }

    @Test
    fun `dependencies count when devDependencies has no typescript and peer dependencies do not`() {
        write("package.json", """{"dependencies":{"typescript":"^5.1.0"},"peerDependencies":{"typescript":"^7"}}""")
        write(
            "sub/package.json",
            """{"peerDependencies":{"typescript":"^7"},"optionalDependencies":{"typescript":"^7"}}""",
        )

        val source = assertIs<TypeScriptSource.Declared>(resolve("sub/a.ts"))

        assertEquals("^5.1.0", source.range)
        assertEquals(Verdict.TOO_OLD, verdictOf(source))
    }

    @Test
    fun `a pnpm symlink is followed to the real package`() {
        val real = installed(".pnpm/typescript@7.0.1/node_modules/typescript", "7.0.1").parent
        val link = dir.resolve("node_modules/typescript")
        link.parent.createDirectories()
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: Exception) {
            assumeTrue(false, "no symbolic links on this platform: $e")
        }

        val source = assertIs<TypeScriptSource.Installed>(resolve("src/a.ts"))

        assertEquals("7.0.1", source.version)
    }

    @Test
    fun `a pnpm catalog resolves through pnpm-workspace`() {
        write("pnpm-workspace.yaml", "packages:\n  - 'packages/*'\ncatalog:\n  typescript: ^6.0.0\n")
        write("packages/app/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")

        val source = assertIs<TypeScriptSource.Declared>(resolve("packages/app/a.ts"))

        assertEquals("catalog:", source.range)
        assertEquals("^6.0.0", source.resolvedRange)
        assertEquals(Verdict.SUPPORTED, verdictOf(source))
    }

    @Test
    fun `catalog default and named pnpm catalogs resolve`() {
        write(
            "pnpm-workspace.yaml",
            "catalog:\n  typescript: ^5.0.0\ncatalogs:\n  next:\n    typescript: ^7.0.0\n",
        )
        write("a/package.json", """{"devDependencies":{"typescript":"catalog:default"}}""")
        write("b/package.json", """{"devDependencies":{"typescript":"catalog:next"}}""")

        val a = assertIs<TypeScriptSource.Declared>(resolve("a/x.ts"))
        val b = assertIs<TypeScriptSource.Declared>(resolve("b/x.ts"))

        assertEquals("^5.0.0", a.resolvedRange)
        assertEquals(Verdict.TOO_OLD, verdictOf(a))
        assertEquals("^7.0.0", b.resolvedRange)
        assertEquals(Verdict.SUPPORTED, verdictOf(b))
    }

    @Test
    fun `bun and yarn catalogs resolve through the root package json`() {
        write(
            "package.json",
            """{"workspaces":{"packages":["a","b"],"catalog":{"typescript":"^6.1.0"},"catalogs":{"old":{"typescript":"^5.0.0"}}}}""",
        )
        write("a/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")
        write("b/package.json", """{"devDependencies":{"typescript":"catalog:old"}}""")

        assertEquals("^6.1.0", assertIs<TypeScriptSource.Declared>(resolve("a/x.ts")).resolvedRange)
        assertEquals("^5.0.0", assertIs<TypeScriptSource.Declared>(resolve("b/x.ts")).resolvedRange)
    }

    @Test
    fun `a top-level catalog of the root package json resolves`() {
        write("package.json", """{"catalog":{"typescript":"^7.0.0"}}""")
        write("a/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")

        assertEquals("^7.0.0", assertIs<TypeScriptSource.Declared>(resolve("a/x.ts")).resolvedRange)
    }

    @Test
    fun `an unresolvable catalog is undecidable`() {
        write("a/package.json", """{"devDependencies":{"typescript":"catalog:missing"}}""")

        val source = assertIs<TypeScriptSource.Declared>(resolve("a/x.ts"))

        assertEquals(null, source.resolvedRange)
        assertEquals(Verdict.UNDECIDABLE, verdictOf(source))
    }

    @Test
    fun `deno json and jsonc make a Deno project`() {
        val config = write("deno.jsonc", "{}")
        write("other/deno.json", "{}")

        assertEquals(config, assertIs<TypeScriptSource.Deno>(resolve("src/a.ts")).config)
        assertEquals(dir.resolve("other/deno.json"), assertIs<TypeScriptSource.Deno>(resolve("other/a.ts")).config)
        assertEquals(Verdict.UNDECIDABLE, verdictOf(TypeScriptSource.Deno(config, emptyList())))
    }

    @Test
    fun `a package json declaring typescript beats a deno json in the same folder`() {
        write("deno.json", "{}")
        write("package.json", """{"devDependencies":{"typescript":"^6"}}""")

        assertIs<TypeScriptSource.Declared>(resolve("a.ts"))
    }

    @Test
    fun `a file without any hit is loose and supported`() {
        write("package.json", """{"name":"no typescript"}""")

        val source = assertIs<TypeScriptSource.Loose>(resolve("src/deep/a.js"))

        assertEquals(listOf(dir.resolve("src/deep"), dir.resolve("src"), dir), source.consulted)
        assertEquals(Verdict.SUPPORTED, verdictOf(source))
    }

    @Test
    fun `comments trailing commas and a BOM are tolerated`() {
        val bom = 0xFEFF.toChar()
        write(
            "package.json",
            "$bom// project\n{\n  /* dev */\n  \"devDependencies\": {\n    \"typescript\": \"^6.0.0\", // ts\n  },\n}\n",
        )

        assertEquals("^6.0.0", assertIs<TypeScriptSource.Declared>(resolve("a.ts")).range)
    }

    @Test
    fun `a BOM in the installed package json is tolerated`() {
        write("node_modules/typescript/package.json", "${0xFEFF.toChar()}{\"version\":\"7.0.0\"}")

        assertEquals("7.0.0", assertIs<TypeScriptSource.Installed>(resolve("a.ts")).version)
    }

    @Test
    fun `a file inside node_modules is governed by the consuming project`() {
        installed(".", "7.0.0")
        write("node_modules/zod/package.json", """{"devDependencies":{"typescript":"^5.0.0"}}""")
        installed("node_modules/zod", "5.0.0")

        val source = assertIs<TypeScriptSource.Installed>(resolve("node_modules/zod/index.d.ts"))

        assertEquals("7.0.0", source.version)
        assertEquals(listOf(dir), source.consulted)
    }

    @Test
    fun `a scoped package and a nested node_modules resolve to the outermost project too`() {
        installed("app", "7.0.0")
        write("app/node_modules/@scope/pkg/package.json", """{"dependencies":{"typescript":"^5.0.0"}}""")
        write("app/node_modules/a/node_modules/@scope/b/package.json", """{"dependencies":{"typescript":"^5.0.0"}}""")

        val scoped = assertIs<TypeScriptSource.Installed>(resolve("app/node_modules/@scope/pkg/x.d.ts"))
        val nested = assertIs<TypeScriptSource.Installed>(resolve("app/node_modules/a/node_modules/@scope/b/x.d.ts"))

        assertEquals("7.0.0", scoped.version)
        assertEquals(dir.resolve("app"), scoped.consulted.first())
        assertEquals("7.0.0", nested.version)
        assertEquals(dir.resolve("app"), nested.consulted.first())
    }

    @Test
    fun `the nearest catalog source wins over outer ones of another kind`() {
        write("pnpm-workspace.yaml", "catalog:\n  typescript: ^5.0.0\n")
        write("packages/package.json", """{"catalog":{"typescript":"^7.0.0"}}""")
        write("packages/app/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")

        val source = assertIs<TypeScriptSource.Declared>(resolve("packages/app/a.ts"))

        assertEquals("^7.0.0", source.resolvedRange)
        assertEquals(dir.resolve("packages/package.json"), source.catalogFile)
        assertEquals(listOf(dir.resolve("packages/app"), dir.resolve("packages")), source.consulted.takeLast(2))
        assertEquals(dir.resolve("packages/app"), source.consulted.first())
    }

    @Test
    fun `a nearest catalog source without a typescript entry does not fall through`() {
        write("pnpm-workspace.yaml", "catalog:\n  typescript: ^7.0.0\n")
        write("packages/pnpm-workspace.yaml", "catalog:\n  zod: ^3.0.0\n")
        write("packages/app/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")

        val source = assertIs<TypeScriptSource.Declared>(resolve("packages/app/a.ts"))

        assertEquals(null, source.resolvedRange)
        assertEquals(Verdict.UNDECIDABLE, verdictOf(source))
    }

    @Test
    fun `catalogs default is an alias of the default catalog`() {
        write("pnpm-workspace.yaml", "catalogs:\n  default:\n    typescript: ^6.0.0\n")
        write("a/package.json", """{"devDependencies":{"typescript":"catalog:"}}""")
        write("b/package.json", """{"devDependencies":{"typescript":"catalog:default"}}""")

        assertEquals("^6.0.0", assertIs<TypeScriptSource.Declared>(resolve("a/x.ts")).resolvedRange)
        assertEquals("^6.0.0", assertIs<TypeScriptSource.Declared>(resolve("b/x.ts")).resolvedRange)
    }

    @Test
    fun `an unparsable package json is skipped and the walk continues`() {
        write("package.json", """{"devDependencies":{"typescript":"^5.0.0"}}""")
        write("sub/package.json", "{ this is not json")

        val source = assertIs<TypeScriptSource.Declared>(resolve("sub/a.ts"))

        assertEquals("^5.0.0", source.range)
        assertEquals(dir.resolve("package.json"), source.packageJson)
    }

    @Test
    fun `an unreadable installed package is skipped`() {
        installed(".", "7.0.0")
        write("sub/node_modules/typescript/package.json", "garbage")

        assertEquals("7.0.0", assertIs<TypeScriptSource.Installed>(resolve("sub/a.ts")).version)
    }

    @Test
    fun `consulted lists the folders nearest first and stops at the hit`() {
        installed("packages", "7.0.0")
        write("package.json", """{"devDependencies":{"typescript":"^5"}}""")

        val source = resolve("packages/app/src/a.ts")

        assertEquals(
            listOf(dir.resolve("packages/app/src"), dir.resolve("packages/app"), dir.resolve("packages")),
            source.consulted,
        )
    }
}
