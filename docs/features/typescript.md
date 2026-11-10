# TypeScript and JavaScript: the TypeScript 7 server

**Module:** `modules/typescript` (package `dev.sluide.typescript`: `TypeScriptSpec`, the version gate in
`TypeScriptProjects.kt` and `NpmRanges.kt`, `TypeScriptTool` with the pin and the platform packages, and
`TypeScriptInstaller`, `TypeScriptUpdates`, `TypeScriptSettings` over the shared server-tool layer of
`modules/server-tools`; the page in the `dev.sluide.typescript.settings` package); decisions in
[ADR 0161](../adr/0161-typescript-via-typescript-7.md) and [ADR 0159](../adr/0159-shared-server-tool-layer.md)

TypeScript and JavaScript code intelligence comes from the TypeScript 7 native language server (`tsc --lsp --stdio`),
run through the language-server client of [ADR 0142](../adr/0142-language-server-foundation.md) for files ending in
`.ts .mts .cts .tsx .js .mjs .cjs .jsx` inside the project folder. The server runs no program of the project (it has no
JavaScript runtime and ignores tsconfig `plugins`), so it needs no trust question. Thoreau downloads it only on a click;
the editor features are described in [code-intelligence.md](code-intelligence.md). One server serves the window, and it
keeps the projects of a monorepo apart by their `tsconfig.json`. Thoreau always runs its own TypeScript 7 server, never
the project's TypeScript.

## Which projects are served
Only projects that use TypeScript 6 or later, or none. For each file Thoreau walks up from its folder, like Node, and the
first one of these decides:
1. `node_modules/typescript`: its installed version. This wins wherever it is on the way up, also above a
   `package.json` that declares another version (a hoisted install of a workspace root, as Node resolves it), but not
   above a `deno.json` the walk met first.
2. A `package.json` declaring `typescript` in `devDependencies` or `dependencies`: the declared range (`catalog:` is
   looked up in `pnpm-workspace.yaml` or the root `package.json`; `npm:typescript@...` aliases work). It counts when
   no installed TypeScript is found above it.
3. A `deno.json` or `deno.jsonc`: a Deno project, and the walk ends there (an install above it never wins), unless
   a `package.json` below it declares TypeScript.

Served: installed 6 or later; a declared range whose alternatives are all 6 or later; a file with no TypeScript found
(a loose file). Not served: installed 5 or lower, a range of 5 or lower, a range Thoreau cannot decide (`latest`,
`*`, mixed ranges such as `^5 || ^7`, `workspace:`, `file:`, git and URL specs, a catalog without a `typescript`
entry), and Deno projects. A not-served file is never sent to the server: no hover, completion or diagnostics. A
banner says why (no action; Not Now per folder, Don't Ask Again for TypeScript):
- "This project uses TypeScript 5.4.5 (apps/c/node_modules). Code intelligence needs TypeScript 6 or later."
- "This project declares TypeScript ^5.4.5 in apps/c/package.json. Code intelligence needs TypeScript 6 or later."
- "Can't tell which TypeScript apps/c uses ("latest" in apps/c/package.json). Code intelligence needs TypeScript 6 or
  later; pin a range such as ^7.0.0."
- "This is a Deno project (deno.json); Thoreau doesn't run Deno's language server."

Paths are relative to the project folder (absolute outside it, `~`-shortened under the home folder). The install banner does not show for such a file. Fix the
project (change `package.json`, run the install) and the file is served without a restart: the folders on the way up,
their `node_modules`, `node_modules/typescript` and the `@scope` folders are watched.

## Monorepos
Projects on different TypeScript versions live side by side: each file is judged by the TypeScript it resolves like Node does (its
own `node_modules`, else one hoisted above it, else its declaration), so an app on TypeScript 7 is served while its
neighbour on 5 shows the banner. A file inside `node_modules` (go to
definition into a dependency) is judged like the project that uses it.

## Config problems
TypeScript 7 rejects options TypeScript 6 deprecated (`baseUrl`, `moduleResolution: node`, ...). The server reports them
on the nearest `tsconfig.json` or `jsconfig.json` above the file (also when an `extends` base sets them), and a banner in
the editors of that project lists them ([code-intelligence.md](code-intelligence.md)).

## Status line
`TypeScript project: apps/a · TypeScript 7.0.2`, `TypeScript project: apps/b · TypeScript ~6.0.3 (not installed)` (a
declared range), `No TypeScript project · server 7.0.2` (loose file; `custom server` with a custom path). When the
project's version is above the server's, `· newer than the server` follows. A click reveals the folder where
TypeScript was found; the tooltip names its path and the server's version and origin. Not-served files have none.

## Settings → Languages → TypeScript
As for [Python](python.md), with npm's registry: **Install** (the pinned version, a download of about 9 MB, size and
sha512 checked, then `tsc --version` and one `initialize` answer), **Check for Updates** (npm's latest version, on
click only), **Update**, **Install <pin>**, **Roll Back**, **Delete…** (asks first) and the **TypeScript executable**:
Thoreau's download or a custom path (`typescript.server.path` in `~/.sluide/settings.properties`). Thoreau's builds:
Linux x64 and aarch64, macOS on Apple silicon.

## Files and network
- `~/.sluide/tools/typescript/`: one folder per installed version, the pointers `current` and `previous` (ADR 0143).
- Host: `registry.npmjs.org` (Check for Updates and the downloads), only on a click.
- The server's automatic type acquisition is switched off: it would run `npm install` in the background.
- Pin, checksums and how to bump them: [external-artifacts.md](../external-artifacts.md), section "TypeScript".
