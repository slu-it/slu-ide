# 0161. TypeScript and JavaScript through the TypeScript 7 server

- Status: accepted
- Date: 2026-11-10

## Context
The goal: code intelligence for TypeScript 6 and 7 projects (and the JavaScript beside them), explicitly not for 5 and
lower, also in monorepos whose projects use different TypeScript versions without mixing their codebases. Two spikes
(TypeScript 7.0.2, linux-arm64) decided the design.

**Server and install.** The server is the statically linked binary `package/lib/tsc` in npm's per-platform package
`@typescript/typescript-<os>-<arch>` (about 9 MB tgz, with the `lib.*.d.ts` files beside it). It runs without Node
(`tsc --lsp --stdio`, even with an empty environment) and answers diagnostics (push and pull), hover, definition,
references, rename, completion, signature help, code actions, formatting, symbols, semantic tokens and inlay hints. It
is fast: 20 ms to the first diagnostics, about 100 ms for a 3,000-file project. npm's integrity is `sha512-<base64>`
(`dist.integrity`).

**Monorepos.** One instance with one workspace folder isolates projects by their nearest tsconfig (options, libs,
globals); it looks for tsconfigs above the window folder too and declares no `workspaceFolders` support. A project
whose files were never opened is never loaded.

**No compatibility mode.** The server always uses its own 7.0 compiler. Options TypeScript 6 deprecated (`baseUrl`,
`moduleResolution: node`, ...) are hard errors in 7, and `ignoreDeprecations` does not help. The server pushes them as
diagnostics on the project's own `tsconfig.json`, also when an `extends` base sets the option, and keeps serving
while ignoring them. TypeScript 5 projects are what the user excluded; the server's behaviour there is not promised.

**Other findings.**
- `didClose` of a never-opened document crashes the server (Go panic); double `didClose`, `didChange` of an unopened
  document and double `didOpen` are harmless (ADR 0160 tracks what a connection opened).
- With npm on the PATH the server runs **automatic type acquisition** in the background (`npm install
  types-registry@latest`, then `@types/<package>`; it writes `~/.npm` and `~/.cache`). The configuration answer turns
  it off; `initializationOptions` do not.
- tsconfig `plugins` are ignored: no child process, plugin code is not run.
- The server registers one watcher `<root>/**/*`. A package added to an existing `node_modules` (excluded from the
  project watch) is seen only after a `didChangeWatchedFiles` for the new folder or its `package.json`.

## Decision
**Server and install.** Thoreau runs the TypeScript 7 server `tsc --lsp --stdio`, never the project's TypeScript. It
installs it like ty (ADR 0143, 0144, 0159): the pinned version from npm's per-platform package (linux-x64,
linux-aarch64, mac-aarch64), with its size and sha512 SRI checked, only on a click, into `~/.sluide/tools/typescript`;
updates (npm's `dist-tags.latest`), rollback, delete and a custom path (`typescript.server.path`) as for ty. The only
host is `registry.npmjs.org`, for metadata and tarballs, never in the background.

**One instance per window,** with the window folder as its only workspace folder. Isolation comes from the server's
tsconfig model; no ADR 0151 workspace folder per project.

**Files.** `.ts .mts .cts` are `typescript`, `.tsx` `typescriptreact`, `.js .mjs .cjs` `javascript`, `.jsx`
`javascriptreact` (case-insensitive; `.d.ts` is `ts`), inside the window folder. A file inside `node_modules` (go to
definition into a dependency) resolves to the project that consumes it: the walk starts at the folder holding the
outermost `node_modules`. Shebang-only scripts, Vue and Svelte are out of scope.

**Version gate per document** (ADR 0160, `TypeScriptSpec.gate`, logic in `TypeScriptProjects.kt`). The walk goes from
the file's folder up to the filesystem root, as Node does, which finds a hoisted install above a package that only
declares TypeScript (npm and yarn workspaces). In each folder:
1. `node_modules/typescript/package.json`: the **installed** version, exact; the walk ends.
2. Else a `package.json` with `typescript` in `devDependencies`, then `dependencies` (not peer): the **declared**
   range. Only the first such declaration counts, and the walk goes on, because an installed TypeScript further up
   wins over it (an installed 5 at the root beats a nearer `^7`: that is what Node resolves).
   `catalog:` / `catalog:<name>` is looked up in `pnpm-workspace.yaml` (`catalog`, `catalogs`) or the root
   `package.json` (`catalog`, `workspaces.catalog`); the nearest source is the only one. `npm:typescript@<range>` is that
   range, `npm:@typescript/native-preview...` is 7.
3. Else a `deno.json` / `deno.jsonc`: **Deno**, the walk ends, but only if no declaration was found yet (the nearest
   config wins, so an install above it never wins); a Deno config above a declaring package is ignored.

At the end of the walk the remembered declaration counts, else the file is loose.

Verdicts: an installed major of 6 or later is served; 5 or lower, or an unparsable version, is not. A declared range is
split on `||` and each alternative's lower-bound major taken: all 6 or later is served, all 5 or lower is too old
(`>=5` counts as 5); mixed, `*`, `latest` and other tags, `workspace:`, `file:`, `link:`, git and URL specs are
undecidable and not served. No hit anywhere is a loose file and served. Deno is not served. There is **no override**
for a too-old or undecidable project (decided against).

**Not served** means no `didOpen`, `didChange`, `didSave` or `didClose` for the document, and the editor shows an
`EnvironmentProblem` banner without an action (Not Now per folder, Don't Ask Again per spec), instead of the install
banner. The reasons name the folder or file relative to the window folder (absolute, `~`-shortened outside):
- Installed too old: "This project uses TypeScript 5.4.5 (apps/c/node_modules). Code intelligence needs TypeScript 6
  or later."
- Declared too old: "This project declares TypeScript ^5.4.5 in apps/c/package.json. Code intelligence needs
  TypeScript 6 or later."
- Undecidable: "Can't tell which TypeScript apps/c uses ("latest" in apps/c/package.json). Code intelligence needs
  TypeScript 6 or later; pin a range such as ^7.0.0." (a catalog names the range as written and the catalog file).
- Deno: "This is a Deno project (deno.json); Thoreau doesn't run Deno's language server."

The gate also names the **watched folders** (ADR 0160), for every consulted folder, inside the window folder too (the
project watch runs only while a server runs): the folder, its `node_modules`, `node_modules/typescript` and every
existing `node_modules/@<scope>`, plus the folder of the catalog file. A `package.json` edit or an install then flips
the answer, and the session forwards the change to the server as a file event, which it needs to see packages added to
`node_modules`. `affectsWorkspaceFolders` accepts `package.json`, `pnpm-workspace.yaml`, `deno.json(c)` and
`node_modules`.

**Config problems** come from kept diagnostics (ADR 0160): `keepsDiagnostics` accepts `tsconfig*.json` and
`jsconfig*.json`, and `configFiles` names the nearest `tsconfig.json` or `jsconfig.json` (tsconfig first in a folder)
from the file up to the window folder. No `extends` parsing: the server reports the problem on the project's own
config. The editor shows them as a banner (see `docs/features/code-intelligence.md`).

**Automatic type acquisition off:** the configuration answer for the sections `js/ts` and `typescript` is
`{"tsserver": {"automaticTypeAcquisition": {"enabled": false}}, "disableAutomaticTypeAcquisition": true}`, every other
section `{}`. A real-binary test (gated by `SLUIDE_TEST_TSC`) checks that no child process starts with npm on the PATH,
and that one does without the answer.

**No trust check:** the binary has no JS runtime and runs no project code, and tsconfig `plugins` are ignored.

**Status line:** "TypeScript project: apps/a · TypeScript 7.0.2" (the link reveals the folder holding the project's
`node_modules` or `package.json`), "... · TypeScript ~6.0.3 (not installed)" for a declared range, "No TypeScript
project · server 7.0.2" for a loose file, " · newer than the server" appended when the installed version is above the
server's. `isIdentifier` (for rename, ADR 0149) follows ECMAScript's IdentifierName, with an optional leading `#` for private
names; reserved words are accepted on purpose, as they are valid property and method names (the compiler reports
their misuse).

**Out of scope** (see [../ideas/language-servers-later.md](../ideas/language-servers-later.md)): semantic colours
beyond today's generic mapping, shebang-only scripts, Vue and Svelte, TypeScript language-service plugins, inlay hints
in the editor.

## Consequences
- TypeScript 5 and Deno projects get nothing but a banner saying why, side by side with served projects in a monorepo.
- A project's TypeScript 6 options that 7 rejects show as config problems; the code in that project is still served.
- The server is always 7, so a project on 6 may see differences (removed options, stricter checks) the banner explains.
- A loose file and a file whose project only declares TypeScript (no install found) watch every folder up to the
  filesystem root, the home folder included, together with their `node_modules`: a change there asks the gate again
  (debounced, the same folder is registered once for all documents) and reaches the server as a file event. The cost
  is one stat per folder.
- Each gate answer costs a few stats and small reads per document on the folder worker; folders named in `watched`
  cost one `WatchService` registration each.
- Amends the install pattern of [0144](0144-python-via-ty.md) for npm packages; builds on
  [0142](0142-language-server-foundation.md), [0151](0151-workspace-folders-per-project.md),
  [0159](0159-shared-server-tool-layer.md) and [0160](0160-document-gate-and-kept-diagnostics.md).
